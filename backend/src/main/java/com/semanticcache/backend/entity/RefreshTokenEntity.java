package com.semanticcache.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * JPA entity representing a persisted refresh token.
 * Maps directly to the authoritative {@code refresh_tokens} table in the Flyway schema.
 * Stores only cryptographic SHA-256 digests (never raw tokens).
 */
@Entity
@Table(name = "refresh_tokens")
public class RefreshTokenEntity {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private UserEntity user;

    @Column(name = "token_hash", length = 64, nullable = false, unique = true)
    private String tokenHash;

    @Column(name = "family_id", length = 36, nullable = false, updatable = false)
    private String familyId;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * Default constructor required by JPA.
     */
    protected RefreshTokenEntity() {
    }

    /**
     * Constructs a new unrevoked RefreshTokenEntity with a generated UUID identifier.
     *
     * @param user      the associated authenticated UserEntity (must not be null)
     * @param tokenHash 64-character hex SHA-256 digest of the raw refresh token
     * @param familyId  36-character canonical UUID string identifying the token rotation family
     * @param expiresAt token expiration timestamp (must not be null)
     */
    public RefreshTokenEntity(UserEntity user, String tokenHash, String familyId, Instant expiresAt) {
        this(UUID.randomUUID().toString(), user, tokenHash, familyId, expiresAt, null, Instant.now());
    }

    /**
     * Constructs a RefreshTokenEntity with full field specification.
     *
     * @param id        canonical 36-character UUID string identifier
     * @param user      the associated authenticated UserEntity
     * @param tokenHash 64-character hex SHA-256 digest
     * @param familyId  36-character UUID family identifier
     * @param expiresAt token expiration timestamp
     * @param revokedAt revocation timestamp (or null if active)
     * @param createdAt creation timestamp
     */
    public RefreshTokenEntity(String id, UserEntity user, String tokenHash, String familyId,
                              Instant expiresAt, Instant revokedAt, Instant createdAt) {
        this.id = validateId(id);
        this.user = Objects.requireNonNull(user, "User must not be null");
        this.tokenHash = validateTokenHash(tokenHash);
        this.familyId = validateFamilyId(familyId);
        this.expiresAt = Objects.requireNonNull(expiresAt, "ExpiresAt must not be null");
        this.revokedAt = revokedAt;
        this.createdAt = createdAt != null ? createdAt : Instant.now();
    }

    @PrePersist
    protected void onPrePersist() {
        if (this.id == null || this.id.isBlank()) {
            this.id = UUID.randomUUID().toString();
        }
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
    }

    private static String validateId(String id) {
        if (id == null || id.trim().isEmpty()) {
            return UUID.randomUUID().toString();
        }
        String trimmed = id.trim();
        if (trimmed.length() > 36) {
            throw new IllegalArgumentException("Token ID length cannot exceed 36 characters: " + trimmed);
        }
        return trimmed;
    }

    private static String validateTokenHash(String tokenHash) {
        if (tokenHash == null || tokenHash.trim().isEmpty()) {
            throw new IllegalArgumentException("Token hash must not be null or blank");
        }
        String trimmed = tokenHash.trim();
        if (trimmed.length() > 64) {
            throw new IllegalArgumentException("Token hash length cannot exceed 64 characters: " + trimmed.length());
        }
        return trimmed;
    }

    private static String validateFamilyId(String familyId) {
        if (familyId == null || familyId.trim().isEmpty()) {
            throw new IllegalArgumentException("Family ID must not be null or blank");
        }
        String trimmed = familyId.trim();
        if (trimmed.length() > 36) {
            throw new IllegalArgumentException("Family ID length cannot exceed 36 characters: " + trimmed.length());
        }
        return trimmed;
    }

    // --- Business Methods ---

    /**
     * Marks this refresh token as revoked at the specified timestamp.
     *
     * @param revokedAt revocation timestamp
     */
    public void revoke(Instant revokedAt) {
        this.revokedAt = Objects.requireNonNull(revokedAt, "Revocation timestamp must not be null");
    }

    /**
     * Checks whether this token has been revoked.
     *
     * @return true if revokedAt is non-null
     */
    public boolean isRevoked() {
        return this.revokedAt != null;
    }

    /**
     * Checks whether this token has expired relative to a given point in time.
     *
     * @param now current timestamp to compare against
     * @return true if now is after expiresAt
     */
    public boolean isExpired(Instant now) {
        return now.isAfter(this.expiresAt);
    }

    // --- Getters ---

    public String getId() {
        return id;
    }

    public UserEntity getUser() {
        return user;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public String getFamilyId() {
        return familyId;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public void setRevokedAt(Instant revokedAt) {
        this.revokedAt = revokedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RefreshTokenEntity that = (RefreshTokenEntity) o;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    @Override
    public String toString() {
        return "RefreshTokenEntity[id=" + id + ", familyId=" + familyId + ", isRevoked=" + isRevoked() + "]";
    }
}
