package com.allan.rate_limiter_lab.repository;

import com.allan.rate_limiter_lab.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

  /**
   * Finds a user by their exact username.
   * Wrapped in an Optional to safely handle cases where the user does not exist.
   */
  Optional<User> findByUsername(String username);

}