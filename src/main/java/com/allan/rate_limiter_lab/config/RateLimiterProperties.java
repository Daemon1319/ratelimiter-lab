package com.allan.rate_limiter_lab.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rate-limiter")
public record RateLimiterProperties(
  int maxAttempts,
  int warnedThreshold,
  long windowSizeSeconds,
  long cooldownSeconds,
  int tokenBucketCapacity,
  double tokenRefillRate
) {
}