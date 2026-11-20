package com.semanticcache.backend.service;

import com.semanticcache.backend.dto.RotationResult;
import com.semanticcache.backend.entity.RefreshTokenEntity;
import com.semanticcache.backend.entity.UserEntity;
import com.semanticcache.backend.repository.RefreshTokenRepository;
import com.semanticcache.backend.util.RefreshTokenUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Transactional worker executing atomic refresh token rotation, pessimistic locking,
 * and family-wide reuse detection and revocation.
 * <p>
 * Executed via Spring proxy invocation from non-transactional orchestrators to ensure
 * clean transaction boundary commits on reuse detection without rollback.
 */
@Service
public class RefreshTokenWorker {

    private static final long ROTATION_VALIDITY_DAYS = 7;

    private final RefreshTokenRepository refreshTokenRepository;

    public RefreshTokenWorker(RefreshTokenRepository refreshTokenRepository) {
        this.refreshTokenRepository = Objects.requireNonNull(refreshTokenRepository, "RefreshTokenRepository must not be null");
    }

    /**
     * Executes atomic refresh token rotation under a pessimistic write lock (SELECT ... FOR UPDATE).
     * If token reuse is detected, all active tokens in the compromised family are revoked and the
     * transaction commits normally so that revocation is persisted regardless of subsequent caller actions.
     *
     * @param tokenHash 64-character hex SHA-256 digest of the presented refresh token
     * @return structured {@link RotationResult} indicating SUCCESS, REUSE_DETECTED, EXPIRED, or INVALID
     */
    @Transactional
    public RotationResult rotate(String tokenHash) {
        if (tokenHash == null || tokenHash.trim().isEmpty()) {
            return RotationResult.invalid();
        }

        // 1. Acquire pessimistic write lock on the token row
        Optional<RefreshTokenEntity> tokenOpt = refreshTokenRepository.findByTokenHashForUpdate(tokenHash.trim());
        if (tokenOpt.isEmpty()) {
            return RotationResult.invalid();
        }

        RefreshTokenEntity token = tokenOpt.get();

        // 2. Verify associated user account validity
        UserEntity user = token.getUser();
        if (user == null || !user.isEnabled()) {
            return RotationResult.invalid();
        }

        Instant now = Instant.now();

        // 3. Reuse detection: If token was already revoked, revoke every token in this family
        if (token.isRevoked()) {
            String familyId = token.getFamilyId();
            List<RefreshTokenEntity> familyTokens = refreshTokenRepository.findAllByFamilyId(familyId);
            for (RefreshTokenEntity member : familyTokens) {
                if (!member.isRevoked()) {
                    member.revoke(now);
                    refreshTokenRepository.save(member);
                }
            }
            refreshTokenRepository.flush();
            // Normal return allows the transaction to COMMIT so family revocation persists in DB
            return RotationResult.reuseDetected(familyId);
        }

        // 4. Expiration check
        if (token.isExpired(now)) {
            return RotationResult.expired();
        }

        // 5. Valid token: revoke old token and issue cryptographically secure replacement
        token.revoke(now);
        refreshTokenRepository.save(token);

        String newRawToken = RefreshTokenUtil.generateSecureRawToken();
        String newHash = RefreshTokenUtil.hashToken(newRawToken);
        Instant newExpiresAt = now.plus(ROTATION_VALIDITY_DAYS, ChronoUnit.DAYS);

        RefreshTokenEntity replacement = new RefreshTokenEntity(user, newHash, token.getFamilyId(), newExpiresAt);
        refreshTokenRepository.save(replacement);
        refreshTokenRepository.flush();

        return RotationResult.success(newRawToken, token.getFamilyId(), user.getId());
    }

    /**
     * Creates an initial active refresh token for a user session, returning the unpersisted raw token.
     *
     * @param user     authenticated user account
     * @param familyId token family identifier (or null to generate a new canonical UUID)
     * @return raw refresh token containing at least 256 bits of entropy
     */
    @Transactional
    public String createInitialToken(UserEntity user, String familyId) {
        Objects.requireNonNull(user, "User must not be null");
        String effectiveFamilyId = (familyId == null || familyId.trim().isEmpty())
                ? UUID.randomUUID().toString()
                : familyId.trim();

        String rawToken = RefreshTokenUtil.generateSecureRawToken();
        String hash = RefreshTokenUtil.hashToken(rawToken);
        Instant expiresAt = Instant.now().plus(ROTATION_VALIDITY_DAYS, ChronoUnit.DAYS);

        RefreshTokenEntity entity = new RefreshTokenEntity(user, hash, effectiveFamilyId, expiresAt);
        refreshTokenRepository.saveAndFlush(entity);

        return rawToken;
    }

    /**
     * Revokes a specific refresh token by its SHA-256 digest during logout.
     * Idempotent operation: does nothing if the token is not found or already revoked.
     * Does not revoke unrelated tokens or the entire family.
     *
     * @param tokenHash 64-character hex SHA-256 digest of the token to revoke
     * @return true if an active token was revoked; false if not found or already revoked
     */
    @Transactional
    public boolean revokeToken(String tokenHash) {
        if (tokenHash == null || tokenHash.trim().isEmpty()) {
            return false;
        }
        Optional<RefreshTokenEntity> tokenOpt = refreshTokenRepository.findByTokenHash(tokenHash.trim());
        if (tokenOpt.isEmpty()) {
            return false;
        }
        RefreshTokenEntity token = tokenOpt.get();
        if (token.isRevoked()) {
            return false;
        }
        token.revoke(Instant.now());
        refreshTokenRepository.save(token);
        refreshTokenRepository.flush();
        return true;
    }
}
