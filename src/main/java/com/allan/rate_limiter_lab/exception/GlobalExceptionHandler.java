package com.allan.rate_limiter_lab.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

  @ExceptionHandler(RateLimitExceededException.class)
  public ResponseEntity<Map<String, Object>> handleRateLimitExceeded(RateLimitExceededException ex) {
    // 429 Too Many Requests
    return buildErrorResponse(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage());
  }

  @ExceptionHandler(AccountLockedException.class)
  public ResponseEntity<Map<String, Object>> handleAccountLocked(AccountLockedException ex) {
    // 423 Locked - Specifically indicates the resource (account) is locked
    return buildErrorResponse(HttpStatus.LOCKED, ex.getMessage());
  }

  @ExceptionHandler(InvalidAlgorithmException.class)
  public ResponseEntity<Map<String, Object>> handleInvalidAlgorithm(InvalidAlgorithmException ex) {
    // 400 Bad Request - The client sent an unrecognized algorithm
    return buildErrorResponse(HttpStatus.BAD_REQUEST, ex.getMessage());
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<Map<String, Object>> handleGenericException(Exception ex) {
    // 500 Internal Server Error - Catch-all for unexpected backend crashes
    return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
  }

  /**
   * Helper method to construct a consistent JSON error response shape.
   */
  private ResponseEntity<Map<String, Object>> buildErrorResponse(HttpStatus status, String message) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("timestamp", Instant.now().toString());
    body.put("status", status.value());
    body.put("error", status.getReasonPhrase());
    body.put("message", message);
    
    return new ResponseEntity<>(body, status);
  }
}