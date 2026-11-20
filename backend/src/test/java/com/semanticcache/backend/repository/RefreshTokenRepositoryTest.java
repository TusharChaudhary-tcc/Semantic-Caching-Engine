package com.semanticcache.backend.repository;

import com.semanticcache.backend.entity.RefreshTokenEntity;
import com.semanticcache.backend.entity.UserEntity;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Transactional
class RefreshTokenRepositoryTest {

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    private UserEntity createTestUser() {
        String username = "tok_user_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 8);
        String passwordHash = "$2a$12$0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNO";
        return userRepository.saveAndFlush(new UserEntity(username, passwordHash));
    }

    @Test
    @DisplayName("RefreshTokenEntity persists with canonical UUID and is retrievable by ID")
    void refreshTokenCreationAndRetrieval() {
        UserEntity user = createTestUser();
        String tokenHash = "hash_" + UUID.randomUUID().toString().replace("-", "") + "0123456789abcdef";
        String familyId = UUID.randomUUID().toString();
        Instant expiresAt = Instant.now().plus(7, ChronoUnit.DAYS);

        RefreshTokenEntity token = new RefreshTokenEntity(user, tokenHash, familyId, expiresAt);
        RefreshTokenEntity saved = refreshTokenRepository.saveAndFlush(token);

        assertNotNull(saved.getId(), "Token ID must be generated");
        assertEquals(36, saved.getId().length(), "Generated ID must be a 36-character canonical UUID string");
        assertNotNull(UUID.fromString(saved.getId()), "Generated ID must parse as a valid UUID");

        assertEquals(tokenHash, saved.getTokenHash());
        assertEquals(familyId, saved.getFamilyId());
        assertEquals(expiresAt, saved.getExpiresAt());
        assertNull(saved.getRevokedAt());
        assertFalse(saved.isRevoked());
        assertFalse(saved.isExpired(Instant.now()));
        assertNotNull(saved.getCreatedAt());

        Optional<RefreshTokenEntity> found = refreshTokenRepository.findById(saved.getId());
        assertTrue(found.isPresent());
        assertEquals(tokenHash, found.get().getTokenHash());
        assertEquals(user.getId(), found.get().getUser().getId());
    }

    @Test
    @DisplayName("findByTokenHash returns matched token for valid hash and empty for unknown hash")
    void findByTokenHash() {
        UserEntity user = createTestUser();
        String tokenHash = "known_hash_" + UUID.randomUUID().toString().replace("-", "") + "abcdef";
        String familyId = UUID.randomUUID().toString();

        RefreshTokenEntity token = new RefreshTokenEntity(user, tokenHash, familyId, Instant.now().plusSeconds(3600));
        refreshTokenRepository.saveAndFlush(token);

        Optional<RefreshTokenEntity> found = refreshTokenRepository.findByTokenHash(tokenHash);
        assertTrue(found.isPresent(), "Token must be found by token hash");
        assertEquals(tokenHash, found.get().getTokenHash());

        Optional<RefreshTokenEntity> notFound = refreshTokenRepository.findByTokenHash("unknown_hash_12345");
        assertFalse(notFound.isPresent());
    }

    @Test
    @DisplayName("findAllByFamilyId retrieves all tokens belonging to the specified family")
    void findAllByFamilyId() {
        UserEntity user = createTestUser();
        String familyId = UUID.randomUUID().toString();
        String otherFamilyId = UUID.randomUUID().toString();

        String hash1 = "family_hash1_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        String hash2 = "family_hash2_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        String otherHash = "other_hash___" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);

        RefreshTokenEntity token1 = new RefreshTokenEntity(user, hash1, familyId, Instant.now().plusSeconds(3600));
        RefreshTokenEntity token2 = new RefreshTokenEntity(user, hash2, familyId, Instant.now().plusSeconds(7200));
        token2.revoke(Instant.now());
        RefreshTokenEntity otherToken = new RefreshTokenEntity(user, otherHash, otherFamilyId, Instant.now().plusSeconds(3600));

        refreshTokenRepository.saveAndFlush(token1);
        refreshTokenRepository.saveAndFlush(token2);
        refreshTokenRepository.saveAndFlush(otherToken);

        List<RefreshTokenEntity> familyTokens = refreshTokenRepository.findAllByFamilyId(familyId);
        assertEquals(2, familyTokens.size(), "Must retrieve exactly the 2 tokens belonging to familyId");
        assertTrue(familyTokens.stream().anyMatch(t -> t.getTokenHash().equals(hash1)));
        assertTrue(familyTokens.stream().anyMatch(t -> t.getTokenHash().equals(hash2)));
        assertFalse(familyTokens.stream().anyMatch(t -> t.getTokenHash().equals(otherHash)));
    }

    @Test
    @DisplayName("findByTokenHashForUpdate executes with pessimistic lock inside an active transaction")
    void findByTokenHashForUpdateExecutesSuccessfully() {
        UserEntity user = createTestUser();
        String tokenHash = "lock_hash_" + UUID.randomUUID().toString().replace("-", "") + "012345";
        String familyId = UUID.randomUUID().toString();

        RefreshTokenEntity token = new RefreshTokenEntity(user, tokenHash, familyId, Instant.now().plusSeconds(3600));
        refreshTokenRepository.saveAndFlush(token);

        Optional<RefreshTokenEntity> locked = refreshTokenRepository.findByTokenHashForUpdate(tokenHash);
        assertTrue(locked.isPresent(), "Pessimistic lock query must find and return the token");
        assertEquals(tokenHash, locked.get().getTokenHash());
    }

    @Test
    @DisplayName("Foreign-key enforcement rejects token referencing null user")
    void foreignKeyEnforcementRejectsNullUser() {
        String tokenHash = "orphaned_hash_" + UUID.randomUUID().toString().replace("-", "");
        assertThrows(NullPointerException.class, () -> {
            new RefreshTokenEntity(null, tokenHash, UUID.randomUUID().toString(), Instant.now().plusSeconds(3600));
        });
    }

    @Test
    @DisplayName("Unique token_hash constraint is strictly enforced")
    void uniqueTokenHashConstraintEnforced() {
        UserEntity user = createTestUser();
        String duplicateHash = "dup_hash_" + UUID.randomUUID().toString().replace("-", "") + "12345";

        RefreshTokenEntity token1 = new RefreshTokenEntity(user, duplicateHash, UUID.randomUUID().toString(), Instant.now().plusSeconds(3600));
        refreshTokenRepository.saveAndFlush(token1);

        RefreshTokenEntity token2 = new RefreshTokenEntity(user, duplicateHash, UUID.randomUUID().toString(), Instant.now().plusSeconds(3600));
        assertThrows(DataIntegrityViolationException.class, () -> {
            refreshTokenRepository.saveAndFlush(token2);
        });
    }

    @Test
    @DisplayName("Database cascade deletion deletes refresh tokens when parent user is deleted")
    void databaseCascadeDeletionOnUserRemoval() {
        UserEntity user = createTestUser();
        String tokenHash = "cascade_hash_" + UUID.randomUUID().toString().replace("-", "") + "1234";

        RefreshTokenEntity token = new RefreshTokenEntity(user, tokenHash, UUID.randomUUID().toString(), Instant.now().plusSeconds(3600));
        RefreshTokenEntity savedToken = refreshTokenRepository.saveAndFlush(token);
        String tokenId = savedToken.getId();
        String userId = user.getId();

        assertTrue(refreshTokenRepository.findById(tokenId).isPresent());

        // Clear persistence context so entities are detached before executing deletion
        entityManager.flush();
        entityManager.clear();

        // Delete parent user
        userRepository.deleteById(userId);
        userRepository.flush();
        entityManager.clear();

        // Refresh token must be cascade-deleted at database level
        assertFalse(refreshTokenRepository.findById(tokenId).isPresent(),
                "Refresh token must be deleted when its parent user is deleted");
    }
}
