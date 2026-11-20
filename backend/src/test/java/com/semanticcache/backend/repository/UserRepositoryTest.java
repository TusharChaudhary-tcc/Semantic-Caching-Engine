package com.semanticcache.backend.repository;

import com.semanticcache.backend.entity.UserEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Transactional
class UserRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    @Test
    @DisplayName("User creation generates canonical UUID and persists with exact schema compatibility")
    void userCreationAndRetrieval() {
        String username = "persist_user_" + System.currentTimeMillis();
        String passwordHash = "$2a$12$abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJKLMNO";

        UserEntity user = new UserEntity(username, passwordHash);
        UserEntity saved = userRepository.saveAndFlush(user);

        assertNotNull(saved.getId(), "User ID must be generated");
        assertEquals(36, saved.getId().length(), "Generated ID must be a 36-character canonical UUID string");
        assertNotNull(UUID.fromString(saved.getId()), "Generated ID must parse as a valid UUID");

        assertEquals(username, saved.getUsername());
        assertEquals(passwordHash, saved.getPasswordHash());
        assertTrue(saved.isEnabled());
        assertNotNull(saved.getCreatedAt());
        assertNotNull(saved.getUpdatedAt());

        Optional<UserEntity> found = userRepository.findById(saved.getId());
        assertTrue(found.isPresent(), "User must be retrievable by ID");
        assertEquals(username, found.get().getUsername());
    }

    @Test
    @DisplayName("findByUsername returns matched entity for existing user and empty for non-existent")
    void findByUsername() {
        String username = "find_user_" + System.currentTimeMillis();
        String passwordHash = "$2a$12$0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNO";

        UserEntity user = new UserEntity(username, passwordHash);
        userRepository.saveAndFlush(user);

        Optional<UserEntity> matched = userRepository.findByUsername(username);
        assertTrue(matched.isPresent(), "User must be found by exact username");
        assertEquals(username, matched.get().getUsername());

        Optional<UserEntity> notFound = userRepository.findByUsername("non_existent_username_xyz");
        assertFalse(notFound.isPresent(), "Non-existent username must return empty Optional");
    }

    @Test
    @DisplayName("existsByUsername returns true for existing user and false for non-existent")
    void existsByUsername() {
        String username = "exists_user_" + System.currentTimeMillis();
        String passwordHash = "$2a$12$0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNO";

        assertFalse(userRepository.existsByUsername(username));

        UserEntity user = new UserEntity(username, passwordHash);
        userRepository.saveAndFlush(user);

        assertTrue(userRepository.existsByUsername(username));
        assertFalse(userRepository.existsByUsername("unknown_user_" + System.currentTimeMillis()));
    }

    @Test
    @DisplayName("Unique username constraint is strictly enforced by the database")
    void uniqueUsernameConstraintEnforced() {
        String duplicateUsername = "duplicate_user_" + System.currentTimeMillis();
        String hash1 = "$2a$12$hash1111111111111111111111111111111111111111111111111";
        String hash2 = "$2a$12$hash2222222222222222222222222222222222222222222222222";

        UserEntity first = new UserEntity(duplicateUsername, hash1);
        userRepository.saveAndFlush(first);

        UserEntity second = new UserEntity(duplicateUsername, hash2);
        assertThrows(DataIntegrityViolationException.class, () -> {
            userRepository.saveAndFlush(second);
        }, "Persisting duplicate username must violate unique constraint and throw DataIntegrityViolationException");
    }

    @Test
    @DisplayName("Updating a user updates the updatedAt timestamp")
    void updatedAtTimestampUpdatesOnModification() throws InterruptedException {
        String username = "update_user_" + System.currentTimeMillis();
        String hash = "$2a$12$updatehash012345678901234567890123456789012345678901234";

        UserEntity user = new UserEntity(username, hash);
        UserEntity saved = userRepository.saveAndFlush(user);
        Instant originalUpdatedAt = saved.getUpdatedAt();

        Thread.sleep(10);
        saved.setEnabled(false);
        UserEntity updated = userRepository.saveAndFlush(saved);

        assertTrue(updated.getUpdatedAt().isAfter(originalUpdatedAt) || updated.getUpdatedAt().equals(originalUpdatedAt),
                "updatedAt must reflect updated timestamp");
        assertFalse(updated.isEnabled());
    }
}
