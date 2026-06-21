package com.allan.rate_limiter_lab.service;

import com.allan.rate_limiter_lab.config.RateLimiterProperties;
import com.allan.rate_limiter_lab.dto.AccountState;
import com.allan.rate_limiter_lab.dto.LoginRequest;
import com.allan.rate_limiter_lab.dto.LoginResponse;
import com.allan.rate_limiter_lab.exception.AccountLockedException;
import com.allan.rate_limiter_lab.exception.InvalidAlgorithmException;
import com.allan.rate_limiter_lab.strategy.RateLimitStrategy;
import com.allan.rate_limiter_lab.strategy.RateLimitStrategy.AlgorithmType;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class RateLimiterService {

  private final RedisTemplate<String, String> redisTemplate;
  private final RateLimiterProperties properties;
  private final Map<AlgorithmType, RateLimitStrategy> strategies;

  private static final String CONFIG_KEY = "rl:config:algorithm";

  public RateLimiterService(
      RedisTemplate<String, String> redisTemplate,
      RateLimiterProperties properties,
      List<RateLimitStrategy> strategyList) {
    this.redisTemplate = redisTemplate;
    this.properties = properties;
    this.strategies = strategyList.stream()
        .collect(Collectors.toMap(RateLimitStrategy::getAlgorithmType, Function.identity()));
  }

  /**
   * Processes a login attempt, tracks IPs for VPN bypass detection, and applies rate limits.
   */
  public LoginResponse processLoginAttempt(LoginRequest request, boolean credentialsValid) {
    String username = request.username().toLowerCase();
    String currentIp = request.ipAddress();
    AlgorithmType currentAlgo = getActiveAlgorithm();

    String stateKey = "rl:" + username + ":state";
    String lockKey = "rl:" + username + ":locked";

    // 1. Check for VPN Bypass attempt
    String lastIp = (String) redisTemplate.opsForHash().get(stateKey, "last_ip");
    boolean isLocked = Boolean.TRUE.equals(redisTemplate.hasKey(lockKey));

    boolean vpnBypassBlocked = false;
    boolean azureWouldAllow = false;

    if (isLocked && lastIp != null && !lastIp.equals(currentIp)) {
      vpnBypassBlocked = true;
      azureWouldAllow = true;
    }

    // Always update the last seen IP
    redisTemplate.opsForHash().put(stateKey, "last_ip", currentIp);

    // 2. Handle Successful Login (Clears counter if not locked)
    if (credentialsValid && !isLocked) {
      clearUserKeys(username);
      return buildSuccessResponse(username, currentIp, currentAlgo);
    }

    // 3. Handle Failed/Locked Login Attempt
    AccountState state;
    try {
      RateLimitStrategy strategy = strategies.get(currentAlgo);
      state = strategy.checkAndRecord(username);
    } catch (AccountLockedException ex) {
      state = buildLockedState(username);
    }

    return buildFailureResponse(username, currentIp, currentAlgo, state, vpnBypassBlocked, azureWouldAllow);
  }

  public AlgorithmType getActiveAlgorithm() {
    String algoStr = redisTemplate.opsForValue().get(CONFIG_KEY);
    if (algoStr == null) {
      return AlgorithmType.FIXED_WINDOW;
    }
    return AlgorithmType.valueOf(algoStr);
  }

  /**
   * Returns the current account state for a given username.
   * Used by the frontend to poll and update the AccountStateCard.
   */
  public Map<String, Object> getStatus(String username) {
    String normalizedUsername = username.toLowerCase();
    String stateKey = "rl:" + normalizedUsername + ":state";
    String lockKey = "rl:" + normalizedUsername + ":locked";

    boolean isLocked = Boolean.TRUE.equals(redisTemplate.hasKey(lockKey));

    Object failsObj = redisTemplate.opsForHash().get(stateKey, "total_failed_attempts");
    int attemptsUsed = failsObj != null ? Integer.parseInt(failsObj.toString()) : 0;
    int attemptsRemaining = Math.max(0, properties.maxAttempts() - attemptsUsed);
    boolean warned = attemptsUsed >= properties.warnedThreshold();

    // Read remaining TTL directly from Redis lock key — no manual math needed
    Long autoUnlockInSeconds = null;
    if (isLocked) {
      Long ttl = redisTemplate.getExpire(lockKey, TimeUnit.SECONDS);
      autoUnlockInSeconds = (ttl != null && ttl > 0) ? ttl : 0L;
    }

    String accountState = isLocked ? "LOCKED" : (warned ? "WARNED" : "ACTIVE");

    // LinkedHashMap used instead of Map.of because autoUnlockInSeconds can be null
    Map<String, Object> status = new LinkedHashMap<>();
    status.put("username", normalizedUsername);
    status.put("accountState", accountState);
    status.put("attemptsUsed", attemptsUsed);
    status.put("attemptsRemaining", attemptsRemaining);
    status.put("warned", warned);
    status.put("locked", isLocked);
    status.put("autoUnlockInSeconds", autoUnlockInSeconds);
    status.put("algorithm", getActiveAlgorithm().name());
    return status;
  }

  /**
   * Returns the current algorithm and all rate limit configuration values.
   * Used by the frontend ConfigPanel to display current limits.
   */
  public Map<String, Object> getConfig() {
    Map<String, Object> config = new LinkedHashMap<>();
    config.put("algorithm", getActiveAlgorithm().name());
    config.put("maxAttempts", properties.maxAttempts());
    config.put("warnedThreshold", properties.warnedThreshold());
    config.put("windowSizeSeconds", properties.windowSizeSeconds());
    config.put("cooldownSeconds", properties.cooldownSeconds());
    config.put("tokenBucketCapacity", properties.tokenBucketCapacity());
    config.put("tokenRefillRate", properties.tokenRefillRate());
    return config;
  }

  /**
   * Switches the global algorithm and carries over state for all active users.
   */
  public void switchAlgorithm(String algorithmName) {
    AlgorithmType newAlgo;
    try {
      newAlgo = AlgorithmType.valueOf(algorithmName.toUpperCase());
    } catch (IllegalArgumentException e) {
      throw new InvalidAlgorithmException("Unknown algorithm: " + algorithmName);
    }

    AlgorithmType oldAlgo = getActiveAlgorithm();
    if (oldAlgo == newAlgo) return;

    resetAll();

    redisTemplate.opsForValue().set(CONFIG_KEY, newAlgo.name());
  }

  public void resetAll() {
    Set<String> keys = redisTemplate.keys("rl:*");
    if (keys != null && !keys.isEmpty()) {
      redisTemplate.delete(keys);
    }
  }

  // --- Private Helper Methods ---

  private void clearUserKeys(String username) {
    Set<String> keys = redisTemplate.keys("rl:" + username + ":*");
    if (keys != null && !keys.isEmpty()) {
      redisTemplate.delete(keys);
    }
  }

  private AccountState buildLockedState(String username) {
    AccountState state = new AccountState();
    state.setLocked(true);
    String stateKey = "rl:" + username + ":state";
    String lockKey  = "rl:" + username + ":locked";

    Object failsObj = redisTemplate.opsForHash().get(stateKey, "total_failed_attempts");
    state.setTotalFailedAttempts(
      failsObj != null ? Integer.parseInt(failsObj.toString()) : properties.maxAttempts()
    );

    // Read actual remaining TTL from the lock key directly — more accurate than
    // parsing a stored timestamp, and avoids format mismatch with Lua-stored values
    Long ttl = redisTemplate.getExpire(lockKey, TimeUnit.SECONDS);
    state.setLockExpiresAt(
      (ttl != null && ttl > 0)
        ? Instant.now().plusSeconds(ttl)
        : Instant.now().plusSeconds(properties.cooldownSeconds())
    );

    return state;
  }

  private LoginResponse buildSuccessResponse(String username, String ipAddress, AlgorithmType algo) {
    Long windowResetInSecs = computeWindowReset(algo);
    return new LoginResponse(
        true, username, ipAddress, algo.name(), "ACTIVE", "Login successful.",
        0, properties.maxAttempts(), false, false,
        null, windowResetInSecs, null, false, false, null,
        algo == AlgorithmType.FIXED_WINDOW,
        algo == AlgorithmType.FIXED_WINDOW ? "Boundary exploit active: attacker can send 10 requests in 3 seconds by straddling window reset" : null
    );
  }

  private LoginResponse buildFailureResponse(
      String username, String ipAddress, AlgorithmType algo, AccountState state,
      boolean vpnBlocked, boolean azureAllowed) {

    int remaining = Math.max(0, properties.maxAttempts() - state.getTotalFailedAttempts());
    boolean warned = state.getTotalFailedAttempts() >= properties.warnedThreshold();

    Long unlockInSecs = null;
    LocalDateTime expiresAt = null;
    if (state.isLocked() && state.getLockExpiresAt() != null) {
      unlockInSecs = Math.max(0, state.getLockExpiresAt().getEpochSecond() - Instant.now().getEpochSecond());
      expiresAt = LocalDateTime.ofInstant(state.getLockExpiresAt(), ZoneId.systemDefault());
    }

    Long windowResetInSecs = computeWindowReset(algo);
    String accountStateStr = state.isLocked() ? "LOCKED" : (warned ? "WARNED" : "ACTIVE");
    String message = state.isLocked() ? "Account locked due to too many failed attempts" : "Invalid credentials";
    String azureReason = azureAllowed ? "New IP detected, Azure resets counter per IP" : null;

    return new LoginResponse(
        false, username, ipAddress, algo.name(), accountStateStr, message,
        state.getTotalFailedAttempts(), remaining, warned, state.isLocked(),
        unlockInSecs, windowResetInSecs, expiresAt, vpnBlocked, azureAllowed, azureReason,
        algo == AlgorithmType.FIXED_WINDOW,
        algo == AlgorithmType.FIXED_WINDOW ? "Boundary exploit active: attacker can send 10 requests in 3 seconds by straddling window reset" : null
    );
  }

  /**
   * Calculates seconds until the current Fixed Window resets.
   * Returns null for Sliding Window and Token Bucket — they have no fixed reset point.
   */
  private Long computeWindowReset(AlgorithmType algo) {
    if (algo != AlgorithmType.FIXED_WINDOW) return null;
    long currentEpochSeconds = Instant.now().getEpochSecond();
    return properties.windowSizeSeconds() - (currentEpochSeconds % properties.windowSizeSeconds());
  }
}