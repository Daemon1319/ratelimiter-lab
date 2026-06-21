package com.allan.rate_limiter_lab.seeder;

import com.allan.rate_limiter_lab.entity.User;
import com.allan.rate_limiter_lab.repository.UserRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class UserSeeder implements ApplicationRunner {

  private final UserRepository userRepository;
  private final PasswordEncoder passwordEncoder;

  public UserSeeder(UserRepository userRepository, PasswordEncoder passwordEncoder) {
    this.userRepository = userRepository;
    this.passwordEncoder = passwordEncoder;
  }

  @Override
  public void run(ApplicationArguments args) {
    // We only need to hash the demo password once since all users share it
    String defaultPassword = "demo123";
    String hashedPassword = passwordEncoder.encode(defaultPassword);

    // List of targeted demo accounts based on your lab plan
    List<String> demoEmails = List.of(
        "john@demo.com",  // Primary demo target for bot attacks
        "jane@demo.com",  // Secondary user to show isolation
        "admin@demo.com"  // Shows different accounts are unaffected
    );

    for (String email : demoEmails) {
      if (userRepository.findByUsername(email).isEmpty()) {
        User user = new User(email, hashedPassword);
        userRepository.save(user);
      }
    }
  }
}