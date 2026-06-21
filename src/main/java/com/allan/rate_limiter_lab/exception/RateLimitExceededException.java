package com.allan.rate_limiter_lab.exception;

public class RateLimitExceededException extends RuntimeException {

  public RateLimitExceededException(String message) {
    super(message);
  }
}