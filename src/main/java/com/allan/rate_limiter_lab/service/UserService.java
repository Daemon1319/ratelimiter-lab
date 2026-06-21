package com.allan.rate_limiter_lab.service;

import com.allan.rate_limiter_lab.entity.User;
import com.allan.rate_limiter_lab.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class UserService {

  private final UserRepository userRepository;
  private final PasswordEncoder passwordEncoder;

  public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
    this.userRepository = userRepository;
    this.passwordEncoder = passwordEncoder;
  }

  /**
   * Verifies the provided credentials against the PostgreSQL database.
   *
   * @param username The username attempting to log in.
   * @param rawPassword The raw, unhashed password from the request body.
   * @return true if the user exists and the password matches; false otherwise.
   */
  public boolean verifyCredentials(String username, String rawPassword) {
    // Normalize username to lowercase to prevent case-sensitivity bypasses
    String normalizedUsername = username.toLowerCase();

    Optional<User> userOptional = userRepository.findByUsername(normalizedUsername);

    if (userOptional.isEmpty()) {
      return false;
    }

    User user = userOptional.get();
    
    // Use BCrypt to securely compare the raw password against the stored database hash
    return passwordEncoder.matches(rawPassword, user.getPasswordHash());
  }
}