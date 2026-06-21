package com.allan.rate_limiter_lab.dto;

import java.time.LocalDateTime;

public record LoginResponse(
  boolean success,
  String username,
  String ipAddress,
  String algorithm,
  String accountState,
  String message,
  int attemptsUsed,
  int attemptsRemaining,
  boolean warned,
  boolean locked,
  Long autoUnlockInSeconds,
  Long windowResetInSeconds,
  LocalDateTime cooldownExpiresAt,
  boolean vpnBypassBlocked,
  boolean azureWouldAllow,
  String azureReason,
  boolean fixedWindowVulnerable,
  String fixedWindowWarning
) {
}