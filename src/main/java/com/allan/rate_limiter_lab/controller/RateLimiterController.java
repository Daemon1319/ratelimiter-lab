package com.allan.rate_limiter_lab.controller;

import com.allan.rate_limiter_lab.dto.LoginRequest;
import com.allan.rate_limiter_lab.dto.LoginResponse;
import com.allan.rate_limiter_lab.service.RateLimiterService;
import com.allan.rate_limiter_lab.service.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class RateLimiterController {

  private final UserService userService;
  private final RateLimiterService rateLimiterService;

  public RateLimiterController(UserService userService, RateLimiterService rateLimiterService) {
    this.userService = userService;
    this.rateLimiterService = rateLimiterService;
  }

  @PostMapping("/auth/login")
  public ResponseEntity<LoginResponse> login(@RequestBody LoginRequest request) {
    boolean credentialsValid = userService.verifyCredentials(request.username(), request.password());
    LoginResponse response = rateLimiterService.processLoginAttempt(request, credentialsValid);

    if (response.success()) {
      return ResponseEntity.ok(response);
    } else if (response.locked()) {
      return ResponseEntity.status(HttpStatus.LOCKED).body(response);
    } else {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
    }
  }

  @GetMapping("/rate-limiter/status/{username}")
  public ResponseEntity<Map<String, Object>> getStatus(@PathVariable String username) {
    Map<String, Object> status = rateLimiterService.getStatus(username);
    return ResponseEntity.ok(status);
  }

  @GetMapping("/rate-limiter/config")
  public ResponseEntity<Map<String, Object>> getConfig() {
    Map<String, Object> config = rateLimiterService.getConfig();
    return ResponseEntity.ok(config);
  }

  @PostMapping("/rate-limiter/algorithm")
  public ResponseEntity<Map<String, String>> switchAlgorithm(@RequestBody Map<String, String> payload) {
    String algorithm = payload.get("algorithm");
    rateLimiterService.switchAlgorithm(algorithm);
    return ResponseEntity.ok(Map.of("message", "Algorithm successfully switched to " + algorithm.toUpperCase()));
  }

  @PostMapping("/rate-limiter/reset")
  public ResponseEntity<Map<String, String>> resetAll() {
    rateLimiterService.resetAll();
    return ResponseEntity.ok(Map.of("message", "All rate limiter states and lockouts have been wiped."));
  }
}