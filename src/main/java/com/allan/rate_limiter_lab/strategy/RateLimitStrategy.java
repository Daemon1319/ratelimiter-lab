package com.allan.rate_limiter_lab.strategy;

import com.allan.rate_limiter_lab.dto.AccountState;

public interface RateLimitStrategy {

  /**
   * Defines the available rate-limiting algorithms for the lab.
   */
  enum AlgorithmType {
    FIXED_WINDOW,
    SLIDING_WINDOW,
    TOKEN_BUCKET
  }

  /**
   * Used for dynamic bean mapping in the service layer.
   *
   * @return The specific algorithm type this strategy implements.
   */
  AlgorithmType getAlgorithmType();

  /**
   * Executes the rate limit check for the provided username.
   * If the limit is exceeded, it updates the state and throws a RateLimitExceededException
   * or AccountLockedException depending on the severity.
   *
   * @param username the user attempting the action
   * @return the current AccountState after the operation
   */
  AccountState checkAndRecord(String username);

}