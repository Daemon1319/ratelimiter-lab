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
public class TokenBucketStrategy implements RateLimitStrategy {

  private final RedisTemplate<String, String> redisTemplate;
  private final RateLimiterProperties properties;
  private final DefaultRedisScript<List> tokenBucketScript;

  public TokenBucketStrategy(RedisTemplate<String, String> redisTemplate, RateLimiterProperties properties) {
    this.redisTemplate = redisTemplate;
    this.properties = properties;

    // Lock check, token refill, token deduct, and lock set all inside one atomic Lua script.
    // Previously lock check and lock set were outside the Lua block — requests
    // could slip past an empty bucket between those two separate Java calls.
    String script =
        "local bucketKey     = KEYS[1]\n" +
        "local lockKey       = KEYS[2]\n" +
        "local stateKey      = KEYS[3]\n" +
        "local nowMillis     = tonumber(ARGV[1])\n" +
        "local capacity      = tonumber(ARGV[2])\n" +
        "local rate          = tonumber(ARGV[3])\n" +
        "local cooldownTtl   = tonumber(ARGV[4])\n" +
        "local warnThreshold = tonumber(ARGV[5])\n" +
        "local maxAttempts   = tonumber(ARGV[6])\n" +

        // Hard barrier: already locked, reject immediately
        "if redis.call('EXISTS', lockKey) == 1 then\n" +
        "  return {-1, 0, 0}\n" +
        "end\n" +

        // Read current bucket state
        "local bucket      = redis.call('HMGET', bucketKey, 'current_tokens', 'last_refill_at')\n" +
        "local tokens      = tonumber(bucket[1])\n" +
        "local last_refill = tonumber(bucket[2])\n" +

        // Initialise bucket on first request
        "if not tokens then\n" +
        "  tokens      = capacity\n" +
        "  last_refill = nowMillis\n" +
        "else\n" +
        // Refill based on wall clock elapsed time
        "  local delta   = math.max(0, (nowMillis - last_refill) / 1000)\n" +
        "  tokens        = math.min(capacity, tokens + (delta * rate))\n" +
        "  last_refill   = nowMillis\n" +
        "end\n" +

        // Deduct token if available, otherwise deny
        "local consumed = capacity - tokens\n" +
        "local attempts = math.floor(consumed) + 1\n" +
        "if tokens >= 1 then\n" +
        "  tokens = tokens - 1\n" +
        "else\n" +
        // Bucket empty — lock the account
        "  redis.call('SET', lockKey, '1', 'EX', cooldownTtl)\n" +
        "  redis.call('HSET', stateKey, 'is_locked', 'true')\n" +
        "  redis.call('HSET', stateKey, 'lock_expires_at', tostring(nowMillis))\n" +
        "  redis.call('HSET', stateKey, 'total_failed_attempts', tostring(attempts))\n" +
        "  redis.call('HMSET', bucketKey, 'current_tokens', tostring(tokens), 'last_refill_at', tostring(last_refill))\n" +
        "  redis.call('EXPIRE', bucketKey, 3600)\n" +
        "  return {attempts, 1, 0}\n" +
        "end\n" +

        // Persist updated bucket
        "redis.call('HMSET', bucketKey, 'current_tokens', tostring(tokens), 'last_refill_at', tostring(last_refill))\n" +
        "redis.call('EXPIRE', bucketKey, 3600)\n" +

        // Update master state
        "redis.call('HSET', stateKey, 'total_failed_attempts', tostring(attempts))\n" +
        "redis.call('HSET', stateKey, 'last_activity', tostring(nowMillis))\n" +

        // Signal warned state
        "if attempts >= warnThreshold then\n" +
        "  return {attempts, 0, 1}\n" +
        "end\n" +

        "return {attempts, 0, 0}";

    this.tokenBucketScript = new DefaultRedisScript<>(script, List.class);
  }

  @Override
  public AlgorithmType getAlgorithmType() {
    return AlgorithmType.TOKEN_BUCKET;
  }

  @Override
  public AccountState checkAndRecord(String username) {
    String bucketKey = "rl:" + username + ":tokens";
    String lockKey   = "rl:" + username + ":locked";
    String stateKey  = "rl:" + username + ":state";

    long nowMillis = Instant.now().toEpochMilli();

    List result = redisTemplate.execute(
        tokenBucketScript,
        List.of(bucketKey, lockKey, stateKey),
        String.valueOf(nowMillis),
        String.valueOf(properties.tokenBucketCapacity()),
        String.valueOf(properties.tokenRefillRate()),
        String.valueOf(properties.cooldownSeconds()),
        String.valueOf(properties.warnedThreshold()),
        String.valueOf(properties.maxAttempts())
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