package com.allan.rate_limiter_lab.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "users")
public class User {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, unique = true)
  private String username;

  @Column(nullable = false, name = "password_hash")
  private String passwordHash;

  @Column(nullable = false, updatable = false, name = "created_at")
  private Instant createdAt;

  public User() {
  }

  public User(String username, String passwordHash) {
    this.username = username;
    this.passwordHash = passwordHash;
  }

  // Automatically sets the timestamp just before saving to the database
  @PrePersist
  protected void onCreate() {
    this.createdAt = Instant.now();
  }

  public Long getId() {
    return id;
  }

  public String getUsername() {
    return username;
  }

  public void setUsername(String username) {
    this.username = username;
  }

  public String getPasswordHash() {
    return passwordHash;
  }

  public void setPasswordHash(String passwordHash) {
    this.passwordHash = passwordHash;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}