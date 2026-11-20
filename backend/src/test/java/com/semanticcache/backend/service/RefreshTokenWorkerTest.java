package com.semanticcache.backend.service;

import com.semanticcache.backend.dto.RotationResult;
import com.semanticcache.backend.entity.RefreshTokenEntity;
import com.semanticcache.backend.entity.UserEntity;
import com.semanticcache.backend.repository.RefreshTokenRepository;
import com.semanticcache.backend.repository.UserRepository;
import com.semanticcache.backend.util.RefreshTokenUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class RefreshTokenWorkerTest {

    @Autowired
    private RefreshTokenWorker refreshTokenWorker;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private UserRepository userRepository;

    private UserEntity createTestUser(boolean enabled) {
        String username = "worker_u_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 8);
        String passwordHash = "$2a$12$0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNO";
        return userRepository.saveAndFlush(new UserEntity(UUID.randomUUID().toString(), username, passwordHash, enabled));
    }

    @Test
    @DisplayName("Successful rotation revokes old token, preserves family ID, and issues exactly one active replacement")
    void successfulRotation() {
        UserEntity user = createTestUser(true);
        String familyId = UUID.randomUUID().toString();
        String initialRawToken = refreshTokenWorker.createInitialToken(user, familyId);
        String initialHash = RefreshTokenUtil.hashToken(initialRawToken);

        RotationResult result = refreshTokenWorker.rotate(initialHash);

        assertTrue(result.isSuccess(), "Rotation must succeed for valid active token");
        assertNotNull(result.getNewRawToken(), "New raw token must be returned");
        assertEquals(familyId, result.getFamilyId(), "Family ID must be preserved");
        assertEquals(user.getId(), result.getUserId(), "User ID must match");

        // Old token must now be revoked in the database
        Optional<RefreshTokenEntity> oldTokenOpt = refreshTokenRepository.findByTokenHash(initialHash);
        assertTrue(oldTokenOpt.isPresent());
        assertTrue(oldTokenOpt.get().isRevoked(), "Old token must be marked as revoked");
        assertNotNull(oldTokenOpt.get().getRevokedAt());

        // New token must be persisted by its hash and be active
        String newHash = RefreshTokenUtil.hashToken(result.getNewRawToken());
        Optional<RefreshTokenEntity> newTokenOpt = refreshTokenRepository.findByTokenHash(newHash);
        assertTrue(newTokenOpt.isPresent(), "Replacement token must be retrievable by hash");
        assertFalse(newTokenOpt.get().isRevoked(), "Replacement token must be active");
        assertEquals(familyId, newTokenOpt.get().getFamilyId());
        assertEquals(user.getId(), newTokenOpt.get().getUser().getId());

        // Verify expiration is ~7 days in future
        Instant now = Instant.now();
        assertTrue(newTokenOpt.get().getExpiresAt().isAfter(now.plus(6, ChronoUnit.DAYS)));
        assertTrue(newTokenOpt.get().getExpiresAt().isBefore(now.plus(8, ChronoUnit.DAYS)));

        // Verify family has exactly 1 active token out of 2 total
        List<RefreshTokenEntity> familyTokens = refreshTokenRepository.findAllByFamilyId(familyId);
        assertEquals(2, familyTokens.size(), "Family must contain exactly 2 tokens (1 revoked, 1 active)");
        long activeCount = familyTokens.stream().filter(t -> !t.isRevoked()).count();
        assertEquals(1, activeCount, "Family must contain exactly 1 active replacement token");
    }

    @Test
    @DisplayName("Rotation rejects unknown token hash with INVALID status")
    void unknownTokenHashIsRejected() {
        String unknownHash = RefreshTokenUtil.hashToken("completely_unknown_token_value_12345");
        RotationResult result = refreshTokenWorker.rotate(unknownHash);

        assertTrue(result.isInvalid());
        assertFalse(result.isSuccess());
        assertNull(result.getNewRawToken());
    }

    @Test
    @DisplayName("Rotation rejects null or blank token hash with INVALID status")
    void blankTokenHashIsRejected() {
        assertTrue(refreshTokenWorker.rotate(null).isInvalid());
        assertTrue(refreshTokenWorker.rotate("").isInvalid());
        assertTrue(refreshTokenWorker.rotate("   ").isInvalid());
    }

    @Test
    @DisplayName("Rotation rejects expired token with EXPIRED status and does not issue replacement")
    void expiredTokenCannotRotate() {
        UserEntity user = createTestUser(true);
        String rawToken = RefreshTokenUtil.generateSecureRawToken();
        String hash = RefreshTokenUtil.hashToken(rawToken);
        String familyId = UUID.randomUUID().toString();

        // Expired 1 hour ago
        Instant expiredAt = Instant.now().minus(1, ChronoUnit.HOURS);
        RefreshTokenEntity entity = new RefreshTokenEntity(user, hash, familyId, expiredAt);
        refreshTokenRepository.saveAndFlush(entity);

        RotationResult result = refreshTokenWorker.rotate(hash);

        assertTrue(result.isExpired(), "Result status must be EXPIRED");
        assertFalse(result.isSuccess());
        assertNull(result.getNewRawToken());

        // Verify no replacement was issued in family
        List<RefreshTokenEntity> familyTokens = refreshTokenRepository.findAllByFamilyId(familyId);
        assertEquals(1, familyTokens.size(), "No replacement token must be created for expired token");
    }

    @Test
    @DisplayName("Rotation rejects token belonging to disabled user with INVALID status")
    void disabledUserTokenCannotRotate() {
        UserEntity disabledUser = createTestUser(false);
        String familyId = UUID.randomUUID().toString();
        String rawToken = refreshTokenWorker.createInitialToken(disabledUser, familyId);
        String hash = RefreshTokenUtil.hashToken(rawToken);

        RotationResult result = refreshTokenWorker.rotate(hash);
        assertTrue(result.isInvalid(), "Disabled user token must be rejected as INVALID");
    }

    @Test
    @DisplayName("Reuse of revoked token revokes all unrevoked tokens in its family and preserves other families")
    void reuseDetectionRevokesEntireFamily() {
        UserEntity user = createTestUser(true);
        String familyId = UUID.randomUUID().toString();
        String otherFamilyId = UUID.randomUUID().toString();

        // Create 2 tokens in target family: token1 (already revoked), token2 (still active)
        String rawToken1 = RefreshTokenUtil.generateSecureRawToken();
        String hash1 = RefreshTokenUtil.hashToken(rawToken1);
        RefreshTokenEntity entity1 = new RefreshTokenEntity(user, hash1, familyId, Instant.now().plus(7, ChronoUnit.DAYS));
        entity1.revoke(Instant.now());
        refreshTokenRepository.saveAndFlush(entity1);

        String rawToken2 = RefreshTokenUtil.generateSecureRawToken();
        String hash2 = RefreshTokenUtil.hashToken(rawToken2);
        RefreshTokenEntity entity2 = new RefreshTokenEntity(user, hash2, familyId, Instant.now().plus(7, ChronoUnit.DAYS));
        refreshTokenRepository.saveAndFlush(entity2);

        // Create token in unrelated family (must remain active)
        String rawOther = RefreshTokenUtil.generateSecureRawToken();
        String hashOther = RefreshTokenUtil.hashToken(rawOther);
        RefreshTokenEntity otherEntity = new RefreshTokenEntity(user, hashOther, otherFamilyId, Instant.now().plus(7, ChronoUnit.DAYS));
        refreshTokenRepository.saveAndFlush(otherEntity);

        // Attempt rotation with already-revoked token1
        RotationResult result = refreshTokenWorker.rotate(hash1);

        assertTrue(result.isReuseDetected(), "Result status must be REUSE_DETECTED");
        assertEquals(familyId, result.getFamilyId());
        assertNull(result.getNewRawToken());

        // Verify ALL tokens in compromised family are now revoked
        List<RefreshTokenEntity> compromisedFamily = refreshTokenRepository.findAllByFamilyId(familyId);
        assertEquals(2, compromisedFamily.size());
        assertTrue(compromisedFamily.stream().allMatch(RefreshTokenEntity::isRevoked),
                "Every token in the compromised family must be revoked");

        // Verify unrelated family is untouched
        Optional<RefreshTokenEntity> untouchedOther = refreshTokenRepository.findByTokenHash(hashOther);
        assertTrue(untouchedOther.isPresent());
        assertFalse(untouchedOther.get().isRevoked(), "Tokens in unrelated families must remain active");
    }

    @Test
    @DisplayName("revokeToken successfully revokes active token, is idempotent, and preserves other tokens")
    void revokeTokenBehavior() {
        UserEntity user = createTestUser(true);
        String familyId = UUID.randomUUID().toString();
        String rawToken1 = refreshTokenWorker.createInitialToken(user, familyId);
        String hash1 = RefreshTokenUtil.hashToken(rawToken1);

        String rawToken2 = refreshTokenWorker.createInitialToken(user, familyId);
        String hash2 = RefreshTokenUtil.hashToken(rawToken2);

        // 1. Revoking active token succeeds
        boolean revoked = refreshTokenWorker.revokeToken(hash1);
        assertTrue(revoked, "revokeToken must return true for active token");

        RefreshTokenEntity token1 = refreshTokenRepository.findByTokenHash(hash1).orElseThrow();
        assertTrue(token1.isRevoked(), "Token 1 must be marked revoked");

        // 2. Revoking already-revoked token returns false (idempotent)
        boolean revokedAgain = refreshTokenWorker.revokeToken(hash1);
        assertFalse(revokedAgain, "revokeToken must return false for already revoked token");

        // 3. Other token remains active
        RefreshTokenEntity token2 = refreshTokenRepository.findByTokenHash(hash2).orElseThrow();
        assertFalse(token2.isRevoked(), "Other token must remain active");

        // 4. Revoking unknown token or null/empty returns false
        assertFalse(refreshTokenWorker.revokeToken("unknown-hash"));
        assertFalse(refreshTokenWorker.revokeToken(null));
        assertFalse(refreshTokenWorker.revokeToken("   "));
    }
}
