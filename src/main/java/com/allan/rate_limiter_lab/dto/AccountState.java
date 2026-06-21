package com.allan.rate_limiter_lab.dto;

import java.time.Instant;

public class AccountState {

  private int totalFailedAttempts;
  private boolean locked;
  private Instant lockExpiresAt;
  private Instant lastActivity;

  public AccountState() {
    this.totalFailedAttempts = 0;
    this.locked = false;
    this.lockExpiresAt = null;
    this.lastActivity = Instant.now();
  }

  public AccountState(int totalFailedAttempts, boolean locked, Instant lockExpiresAt, Instant lastActivity) {
    this.totalFailedAttempts = totalFailedAttempts;
    this.locked = locked;
    this.lockExpiresAt = lockExpiresAt;
    this.lastActivity = lastActivity;
  }

  public int getTotalFailedAttempts() {
    return totalFailedAttempts;
  }

  public void setTotalFailedAttempts(int totalFailedAttempts) {
    this.totalFailedAttempts = totalFailedAttempts;
  }

  public boolean isLocked() {
    return locked;
  }

  public void setLocked(boolean locked) {
    this.locked = locked;
  }

  public Instant getLockExpiresAt() {
    return lockExpiresAt;
  }

  public void setLockExpiresAt(Instant lockExpiresAt) {
    this.lockExpiresAt = lockExpiresAt;
  }

  public Instant getLastActivity() {
    return lastActivity;
  }

  public void setLastActivity(Instant lastActivity) {
    this.lastActivity = lastActivity;
  }
}