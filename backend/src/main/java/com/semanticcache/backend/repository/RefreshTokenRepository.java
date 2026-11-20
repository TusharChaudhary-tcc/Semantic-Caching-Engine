package com.semanticcache.backend.repository;

import com.semanticcache.backend.entity.RefreshTokenEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for {@link RefreshTokenEntity} persistence and concurrency control.
 */
@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshTokenEntity, String> {

    /**
     * Looks up a refresh token record matching the exact SHA-256 token digest.
     *
     * @param tokenHash the 64-character hex SHA-256 digest of the raw token
     * @return an Optional containing the matched RefreshTokenEntity, or empty if not found
     */
    Optional<RefreshTokenEntity> findByTokenHash(String tokenHash);

    /**
     * Looks up a refresh token record with an exclusive pessimistic write lock (SELECT ... FOR UPDATE).
     * Must be executed within an active transaction.
     *
     * @param tokenHash the 64-character hex SHA-256 digest of the raw token
     * @return an Optional containing the locked RefreshTokenEntity, or empty if not found
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM RefreshTokenEntity r WHERE r.tokenHash = :tokenHash")
    Optional<RefreshTokenEntity> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    /**
     * Retrieves all refresh token records belonging to the specified rotation family.
     * Used for family-wide reuse detection and revocation.
     *
     * @param familyId the 36-character canonical UUID string identifying the token family
     * @return a List of all RefreshTokenEntity instances in the family
     */
    List<RefreshTokenEntity> findAllByFamilyId(String familyId);
}
