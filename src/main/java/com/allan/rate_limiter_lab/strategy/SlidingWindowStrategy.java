package com.allan.rate_limiter_lab.strategy;

import com.allan.rate_limiter_lab.config.RateLimiterProperties;
import com.allan.rate_limiter_lab.dto.AccountState;
import com.allan.rate_limiter_lab.exception.AccountLockedException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
public class SlidingWindowStrategy implements RateLimitStrategy {

  private final RedisTemplate<String, String> redisTemplate;
  private final RateLimiterProperties properties;
  private final DefaultRedisScript<List> slidingWindowScript;

  public SlidingWindowStrategy(RedisTemplate<String, String> redisTemplate, RateLimiterProperties properties) {
    this.redisTemplate = redisTemplate;
    this.properties = properties;

    // Lock check, ZADD, ZCARD, and lock set all inside one atomic Lua script.
    // Previously lock check and lock set were outside the Lua block — requests
    // could slip past the limit between those two separate Java calls.
    String script =
        "local slidingKey    = KEYS[1]\n" +
        "local lockKey       = KEYS[2]\n" +
        "local stateKey      = KEYS[3]\n" +
        "local nowMillis     = tonumber(ARGV[1])\n" +
        "local windowStart   = tonumber(ARGV[2])\n" +
        "local requestId     = ARGV[3]\n" +
        "local windowTtl     = tonumber(ARGV[4])\n" +
        "local maxAttempts   = tonumber(ARGV[5])\n" +
        "local cooldownTtl   = tonumber(ARGV[6])\n" +
        "local warnThreshold = tonumber(ARGV[7])\n" +

        // Hard barrier: already locked, reject immediately
        "if redis.call('EXISTS', lockKey) == 1 then\n" +
        "  return {-1, 0, 0}\n" +
        "end\n" +

        // Remove entries outside the sliding window, add current request
        "redis.call('ZREMRANGEBYSCORE', slidingKey, '-inf', windowStart)\n" +
        "redis.call('ZADD', slidingKey, nowMillis, requestId)\n" +
        "local count = redis.call('ZCARD', slidingKey)\n" +
        "redis.call('EXPIRE', slidingKey, windowTtl)\n" +

        // Update master state
        "redis.call('HSET', stateKey, 'total_failed_attempts', count)\n" +
        "redis.call('HSET', stateKey, 'last_activity', tostring(nowMillis))\n" +

        // Lock if limit reached
        "if count >= maxAttempts then\n" +
        "  redis.call('SET', lockKey, '1', 'EX', cooldownTtl)\n" +
        "  redis.call('HSET', stateKey, 'is_locked', 'true')\n" +
        "  redis.call('HSET', stateKey, 'lock_expires_at', tostring(nowMillis))\n" +
        "  return {count, 1, 0}\n" +
        "end\n" +

        // Signal warned state
        "if count >= warnThreshold then\n" +
        "  return {count, 0, 1}\n" +
        "end\n" +

        "return {count, 0, 0}";

    this.slidingWindowScript = new DefaultRedisScript<>(script, List.class);
  }

  @Override
  public AlgorithmType getAlgorithmType() {
    return AlgorithmType.SLIDING_WINDOW;
  }

  @Override
  public AccountState checkAndRecord(String username) {
    String slidingKey = "rl:" + username + ":sliding";
    String lockKey    = "rl:" + username + ":locked";
    String stateKey   = "rl:" + username + ":state";

    long nowMillis      = Instant.now().toEpochMilli();
    long windowStart    = nowMillis - (properties.windowSizeSeconds() * 1000);
    String requestId    = UUID.randomUUID().toString();

    List result = redisTemplate.execute(
        slidingWindowScript,
        List.of(slidingKey, lockKey, stateKey),
        String.valueOf(nowMillis),
        String.valueOf(windowStart),
        requestId,
        String.valueOf(properties.windowSizeSeconds()),
        String.valueOf(properties.maxAttempts()),
        String.valueOf(properties.cooldownSeconds()),
        String.valueOf(properties.warnedThreshold())
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