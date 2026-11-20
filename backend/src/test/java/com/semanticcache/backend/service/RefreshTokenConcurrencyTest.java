package com.semanticcache.backend.service;

import com.semanticcache.backend.entity.RefreshTokenEntity;
import com.semanticcache.backend.entity.UserEntity;
import com.semanticcache.backend.exception.TokenReuseException;
import com.semanticcache.backend.repository.RefreshTokenRepository;
import com.semanticcache.backend.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class RefreshTokenConcurrencyTest {

    @Autowired
    private AuthService authService;

    @Autowired
    private RefreshTokenWorker refreshTokenWorker;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private UserRepository userRepository;

    private UserEntity createTestUser() {
        String username = "conc_u_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 8);
        String passwordHash = "$2a$12$0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNO";
        return userRepository.saveAndFlush(new UserEntity(UUID.randomUUID().toString(), username, passwordHash, true));
    }

    @Test
    @DisplayName("Concurrent rotation attempts against the same token prevent duplicate replacement and trigger family revocation")
    void concurrentRotationAttemptsHandledSafely() throws Exception {
        UserEntity user = createTestUser();
        String familyId = UUID.randomUUID().toString();
        String initialRawToken = refreshTokenWorker.createInitialToken(user, familyId);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(2);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger reuseCount = new AtomicInteger(0);
        AtomicReference<String> replacementTokenRef = new AtomicReference<>();

        Runnable task = () -> {
            try {
                // Wait for both threads to be ready
                startGate.await();
                String newRaw = authService.rotateRefreshToken(initialRawToken);
                successCount.incrementAndGet();
                replacementTokenRef.set(newRaw);
            } catch (TokenReuseException ex) {
                reuseCount.incrementAndGet();
            } catch (Exception ex) {
                // Unexpected exception
                ex.printStackTrace();
            } finally {
                doneGate.countDown();
            }
        };

        Future<?> f1 = executor.submit(task);
        Future<?> f2 = executor.submit(task);

        // Release both threads simultaneously
        startGate.countDown();

        boolean finishedInTime = doneGate.await(10, TimeUnit.SECONDS);
        executor.shutdown();
        assertTrue(finishedInTime, "Concurrent rotation requests must finish within 10 seconds without deadlocking");

        f1.get();
        f2.get();

        // One request rotates the token successfully, the other detects reuse
        assertEquals(1, successCount.get(), "Exactly one concurrent rotation request must succeed initially");
        assertEquals(1, reuseCount.get(), "The concurrent duplicate request must detect reuse and throw TokenReuseException");

        // Verify database state: because reuse was detected by the second concurrent request,
        // all tokens in this family (the original AND the newly issued replacement) MUST end up revoked.
        List<RefreshTokenEntity> familyTokens = refreshTokenRepository.findAllByFamilyId(familyId);
        assertTrue(familyTokens.size() >= 2, "Family must contain original token and replacement token");
        for (RefreshTokenEntity token : familyTokens) {
            assertTrue(token.isRevoked(),
                    "Every token in the family must end up revoked due to concurrent reuse detection: " + token.getId());
            assertNotNull(token.getRevokedAt());
        }
    }
}
