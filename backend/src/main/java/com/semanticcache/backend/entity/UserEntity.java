package com.semanticcache.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * JPA entity representing an authenticated user account.
 * Maps directly to the authoritative {@code users} table in the Flyway schema.
 */
@Entity
@Table(name = "users")
public class UserEntity {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "username", length = 50, nullable = false, unique = true)
    private String username;

    @Column(name = "password_hash", length = 100, nullable = false)
    private String passwordHash;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Default constructor required by JPA.
     */
    protected UserEntity() {
    }

    /**
     * Constructs a new UserEntity with validated credentials and metadata.
     * Generates a canonical UUID string identifier and initializes timestamps.
     *
     * @param username     unique username (max 50 chars, non-blank)
     * @param passwordHash securely hashed password (max 100 chars, non-blank; never raw password)
     */
    public UserEntity(String username, String passwordHash) {
        this(UUID.randomUUID().toString(), username, passwordHash, true);
    }

    /**
     * Constructs a UserEntity with explicit ID, credentials, and enabled status.
     *
     * @param id           canonical 36-character UUID string identifier
     * @param username     unique username (max 50 chars, non-blank)
     * @param passwordHash securely hashed password (max 100 chars, non-blank)
     * @param enabled      account active status
     */
    public UserEntity(String id, String username, String passwordHash, boolean enabled) {
        this.id = validateId(id);
        this.username = validateUsername(username);
        this.passwordHash = validatePasswordHash(passwordHash);
        this.enabled = enabled;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PrePersist
    protected void onPrePersist() {
        if (this.id == null || this.id.isBlank()) {
            this.id = UUID.randomUUID().toString();
        }
        Instant now = Instant.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        if (this.updatedAt == null) {
            this.updatedAt = now;
        }
    }

    @PreUpdate
    protected void onPreUpdate() {
        this.updatedAt = Instant.now();
    }

    private static String validateId(String id) {
        if (id == null || id.trim().isEmpty()) {
            return UUID.randomUUID().toString();
        }
        String trimmed = id.trim();
        if (trimmed.length() > 36) {
            throw new IllegalArgumentException("User ID length cannot exceed 36 characters: " + trimmed);
        }
        return trimmed;
    }

    private static String validateUsername(String username) {
        if (username == null || username.trim().isEmpty()) {
            throw new IllegalArgumentException("Username must not be null or blank");
        }
        String trimmed = username.trim();
        if (trimmed.length() > 50) {
            throw new IllegalArgumentException("Username length cannot exceed 50 characters: " + trimmed.length());
        }
        return trimmed;
    }

    private static String validatePasswordHash(String passwordHash) {
        if (passwordHash == null || passwordHash.trim().isEmpty()) {
            throw new IllegalArgumentException("Password hash must not be null or blank");
        }
        String trimmed = passwordHash.trim();
        if (trimmed.length() > 100) {
            throw new IllegalArgumentException("Password hash length cannot exceed 100 characters: " + trimmed.length());
        }
        return trimmed;
    }

    // --- Getters and Setters ---

    public String getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = validateUsername(username);
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = validatePasswordHash(passwordHash);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        UserEntity that = (UserEntity) o;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    @Override
    public String toString() {
        return "UserEntity[id=" + id + ", username=" + username + ", enabled=" + enabled + "]";
    }
}
