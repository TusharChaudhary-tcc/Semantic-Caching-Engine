package com.semanticcache.backend.repository;

import com.semanticcache.backend.entity.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Spring Data JPA repository for {@link UserEntity} persistence operations.
 */
@Repository
public interface UserRepository extends JpaRepository<UserEntity, String> {

    /**
     * Retrieves an active user account matching the exact username.
     *
     * @param username the unique username to search for
     * @return an Optional containing the matched UserEntity, or empty if not found
     */
    Optional<UserEntity> findByUsername(String username);

    /**
     * Checks if a user account already exists with the given username.
     *
     * @param username the username to check for existence
     * @return true if an account exists with this username, false otherwise
     */
    boolean existsByUsername(String username);
}
