package com.allan.rate_limiter_lab.strategy;

import com.allan.rate_limiter_lab.config.RateLimiterProperties;
import com.allan.rate_limiter_lab.dto.AccountState;
import com.allan.rate_limiter_lab.exception.AccountLockedException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
public class FixedWindowStrategy implements RateLimitStrategy {

  private final RedisTemplate<String, String> redisTemplate;
  private final RateLimiterProperties properties;
  private final DefaultRedisScript<List> fixedWindowScript;

  public FixedWindowStrategy(RedisTemplate<String, String> redisTemplate, RateLimiterProperties properties) {
    this.redisTemplate = redisTemplate;
    this.properties = properties;

    // All 3 operations now inside one atomic Lua script:
    // 1. Lock check (hard barrier)
    // 2. INCR window counter
    // 3. Lock set if limit reached
    // Nothing can execute between these steps — race condition eliminated.
    String script =
        "local windowKey     = KEYS[1]\n" +
        "local lockKey       = KEYS[2]\n" +
        "local stateKey      = KEYS[3]\n" +
        "local windowTtl     = tonumber(ARGV[1])\n" +
        "local maxAttempts   = tonumber(ARGV[2])\n" +
        "local cooldownTtl   = tonumber(ARGV[3])\n" +
        "local warnThreshold = tonumber(ARGV[4])\n" +
        "local nowMillis     = ARGV[5]\n" +

        // Hard barrier: already locked, reject immediately
        "if redis.call('EXISTS', lockKey) == 1 then\n" +
        "  return {-1, 0, 0}\n" +
        "end\n" +

        // Atomic increment
        "local count = redis.call('INCR', windowKey)\n" +

        // Set TTL on first request so window auto-expires
        "if count == 1 then\n" +
        "  redis.call('EXPIRE', windowKey, windowTtl)\n" +
        "end\n" +

        // Update master state
        "redis.call('HSET', stateKey, 'total_failed_attempts', count)\n" +
        "redis.call('HSET', stateKey, 'last_activity', nowMillis)\n" +

        // Lock if limit reached
        "if count >= maxAttempts then\n" +
        "  redis.call('SET', lockKey, '1', 'EX', cooldownTtl)\n" +
        "  redis.call('HSET', stateKey, 'is_locked', 'true')\n" +
        "  redis.call('HSET', stateKey, 'lock_expires_at', nowMillis)\n" +
        "  return {count, 1, 0}\n" +
        "end\n" +

        // Signal warned state
        "if count >= warnThreshold then\n" +
        "  return {count, 0, 1}\n" +
        "end\n" +

        "return {count, 0, 0}";

    this.fixedWindowScript = new DefaultRedisScript<>(script, List.class);
  }

  @Override
  public AlgorithmType getAlgorithmType() {
    return AlgorithmType.FIXED_WINDOW;
  }

  @Override
  public AccountState checkAndRecord(String username) {
    long windowId = Instant.now().getEpochSecond() / properties.windowSizeSeconds();

    String windowKey = "rl:" + username + ":fixed:" + windowId;
    String lockKey   = "rl:" + username + ":locked";
    String stateKey  = "rl:" + username + ":state";

    long nowMillis = Instant.now().toEpochMilli();

    List result = redisTemplate.execute(
        fixedWindowScript,
        List.of(windowKey, lockKey, stateKey),
        String.valueOf(properties.windowSizeSeconds()),
        String.valueOf(properties.maxAttempts()),
        String.valueOf(properties.cooldownSeconds()),
        String.valueOf(properties.warnedThreshold()),
        String.valueOf(nowMillis)
    );

    long count  = ((Number) result.get(0)).longValue();
    long locked = ((Number) result.get(1)).longValue();

    if (count == -1) {
      throw new AccountLockedException("Account is currently locked. Please wait for the cooldown period to expire.");
    }

    if (locked == 1) {
      AccountState state = new AccountState();
      state.setTotalFailedAttempts((int) count);
      state.setLocked(true);
      state.setLockExpiresAt(Instant.now().plusSeconds(properties.cooldownSeconds()));
      throw new AccountLockedException("Account locked due to too many failed attempts.");
    }

    AccountState state = new AccountState();
    state.setTotalFailedAttempts((int) count);
    state.setLocked(false);
    state.setLastActivity(Instant.ofEpochMilli(nowMillis));
    return state;
  }
}