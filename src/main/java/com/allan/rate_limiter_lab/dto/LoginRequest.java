package com.allan.rate_limiter_lab.dto;

public record LoginRequest(
  String username,
  String password,
  String ipAddress
) {
}