package com.allan.rate_limiter_lab.exception;

public class AccountLockedException extends RuntimeException {

  public AccountLockedException(String message) {
    super(message);
  }
}