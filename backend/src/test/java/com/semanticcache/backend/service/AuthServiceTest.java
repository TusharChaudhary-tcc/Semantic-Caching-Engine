package com.semanticcache.backend.service;

import com.semanticcache.backend.entity.RefreshTokenEntity;
import com.semanticcache.backend.entity.UserEntity;
import com.semanticcache.backend.exception.InvalidRefreshTokenException;
import com.semanticcache.backend.exception.TokenReuseException;
import com.semanticcache.backend.repository.RefreshTokenRepository;
import com.semanticcache.backend.repository.UserRepository;
import com.semanticcache.backend.util.RefreshTokenUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class AuthServiceTest {

    @Autowired
    private AuthService authService;

    @Autowired
    private RefreshTokenWorker refreshTokenWorker;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private UserRepository userRepository;

    private UserEntity createTestUser() {
        String username = "auth_u_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 8);
        String passwordHash = "$2a$12$0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNO";
        return userRepository.saveAndFlush(new UserEntity(UUID.randomUUID().toString(), username, passwordHash, true));
    }

    @Test
    @DisplayName("rotateRefreshToken succeeds with active raw token and returns valid replacement")
    void successfulRotation() {
        UserEntity user = createTestUser();
        String familyId = UUID.randomUUID().toString();
        String initialRawToken = refreshTokenWorker.createInitialToken(user, familyId);

        String newRawToken = authService.rotateRefreshToken(initialRawToken);

        assertNotNull(newRawToken);
        assertFalse(newRawToken.isBlank());

        // Initial token is now revoked
        String initialHash = RefreshTokenUtil.hashToken(initialRawToken);
        RefreshTokenEntity oldEntity = refreshTokenRepository.findByTokenHash(initialHash).orElseThrow();
        assertTrue(oldEntity.isRevoked(), "Original token must be revoked");

        // New token is active and valid for subsequent rotation
        String newHash = RefreshTokenUtil.hashToken(newRawToken);
        RefreshTokenEntity newEntity = refreshTokenRepository.findByTokenHash(newHash).orElseThrow();
        assertFalse(newEntity.isRevoked(), "New token must be active");
        assertEquals(familyId, newEntity.getFamilyId());

        // Perform second rotation with the replacement token
        String secondReplacement = authService.rotateRefreshToken(newRawToken);
        assertNotNull(secondReplacement);
    }

    @Test
    @DisplayName("rotateRefreshToken throws InvalidRefreshTokenException on null or blank input")
    void rejectsBlankRawToken() {
        assertThrows(InvalidRefreshTokenException.class, () -> authService.rotateRefreshToken(null));
        assertThrows(InvalidRefreshTokenException.class, () -> authService.rotateRefreshToken(""));
        assertThrows(InvalidRefreshTokenException.class, () -> authService.rotateRefreshToken("   "));
    }

    @Test
    @DisplayName("rotateRefreshToken throws InvalidRefreshTokenException on unknown token")
    void rejectsUnknownRawToken() {
        String unknownToken = RefreshTokenUtil.generateSecureRawToken();
        assertThrows(InvalidRefreshTokenException.class, () -> authService.rotateRefreshToken(unknownToken));
    }

    @Test
    @DisplayName("rotateRefreshToken throws InvalidRefreshTokenException on expired token")
    void rejectsExpiredRawToken() {
        UserEntity user = createTestUser();
        String rawToken = RefreshTokenUtil.generateSecureRawToken();
        String hash = RefreshTokenUtil.hashToken(rawToken);
        String familyId = UUID.randomUUID().toString();

        RefreshTokenEntity entity = new RefreshTokenEntity(user, hash, familyId, Instant.now().minus(2, ChronoUnit.HOURS));
        refreshTokenRepository.saveAndFlush(entity);

        assertThrows(InvalidRefreshTokenException.class, () -> authService.rotateRefreshToken(rawToken));
    }

    @Test
    @DisplayName("rotateRefreshToken throws TokenReuseException on revoked token AND family revocation commits to database")
    void reuseDetectionRevocationCommitsDespiteException() {
        UserEntity user = createTestUser();
        String familyId = UUID.randomUUID().toString();

        // Step 1: Initial token created and rotated normally
        String initialRawToken = refreshTokenWorker.createInitialToken(user, familyId);
        String activeReplacementToken = authService.rotateRefreshToken(initialRawToken);

        // At this point: initialRawToken is REVOKED, activeReplacementToken is ACTIVE
        String replacementHash = RefreshTokenUtil.hashToken(activeReplacementToken);
        assertTrue(refreshTokenRepository.findByTokenHash(replacementHash).orElseThrow().isRevoked() == false,
                "Replacement token must initially be active");

        // Step 2: Attempt to reuse the already-rotated initialRawToken
        TokenReuseException exception = assertThrows(TokenReuseException.class, () -> {
            authService.rotateRefreshToken(initialRawToken);
        });
        assertTrue(exception.getMessage().contains("reuse detected"), "Exception message must report reuse");

        // Step 3: CRITICAL ARCHITECTURAL VERIFICATION
        // Verify that in the database, the active replacement token in the family was revoked
        // and the transaction committed despite TokenReuseException being thrown by AuthService.
        List<RefreshTokenEntity> familyTokens = refreshTokenRepository.findAllByFamilyId(familyId);
        assertEquals(2, familyTokens.size());
        for (RefreshTokenEntity token : familyTokens) {
            assertTrue(token.isRevoked(),
                    "Every token in the family must remain revoked in the DB after TokenReuseException");
            assertNotNull(token.getRevokedAt());
        }

        // Step 4: The formerly active replacement token must now also be rejected
        assertThrows(TokenReuseException.class, () -> {
            authService.rotateRefreshToken(activeReplacementToken);
        });
    }

    @Test
    @DisplayName("register successfully persists user, hashes password, and issues valid tokens")
    void registerSuccess() {
        String username = "reg_user_" + System.currentTimeMillis();
        com.semanticcache.backend.dto.RegisterRequest request = new com.semanticcache.backend.dto.RegisterRequest(username, "Password123!");

        com.semanticcache.backend.dto.AuthSession session = authService.register(request);

        assertNotNull(session);
        assertEquals(username, session.username());
        assertNotNull(session.userId());
        assertNotNull(session.accessToken());
        assertNotNull(session.rawRefreshToken());

        UserEntity user = userRepository.findByUsername(username).orElseThrow();
        assertEquals(username, user.getUsername());
        assertTrue(user.isEnabled());
        assertFalse(user.getPasswordHash().equals("Password123!"));
    }

    @Test
    @DisplayName("register throws UserAlreadyExistsException when username is taken")
    void registerDuplicateThrows() {
        UserEntity existing = createTestUser();
        com.semanticcache.backend.dto.RegisterRequest request = new com.semanticcache.backend.dto.RegisterRequest(existing.getUsername(), "Password123!");

        assertThrows(com.semanticcache.backend.exception.UserAlreadyExistsException.class,
                () -> authService.register(request));
    }

    @Test
    @DisplayName("login succeeds with valid credentials")
    void loginSuccess() {
        String username = "login_user_" + System.currentTimeMillis();
        com.semanticcache.backend.dto.RegisterRequest regRequest = new com.semanticcache.backend.dto.RegisterRequest(username, "SecurePass123!");
        authService.register(regRequest);

        com.semanticcache.backend.dto.LoginRequest loginReq = new com.semanticcache.backend.dto.LoginRequest(username, "SecurePass123!");
        com.semanticcache.backend.dto.AuthSession session = authService.login(loginReq);

        assertNotNull(session);
        assertEquals(username, session.username());
        assertNotNull(session.accessToken());
        assertNotNull(session.rawRefreshToken());
    }

    @Test
    @DisplayName("login throws InvalidCredentialsException on unknown username or wrong password")
    void loginInvalidCredentials() {
        String username = "login_fail_" + System.currentTimeMillis();
        com.semanticcache.backend.dto.RegisterRequest regRequest = new com.semanticcache.backend.dto.RegisterRequest(username, "SecurePass123!");
        authService.register(regRequest);

        // Wrong password
        assertThrows(com.semanticcache.backend.exception.InvalidCredentialsException.class,
                () -> authService.login(new com.semanticcache.backend.dto.LoginRequest(username, "WrongPass123!")));

        // Unknown username
        assertThrows(com.semanticcache.backend.exception.InvalidCredentialsException.class,
                () -> authService.login(new com.semanticcache.backend.dto.LoginRequest("unknown_user_9999", "SecurePass123!")));
    }

    @Test
    @DisplayName("login throws AccountDisabledException when user is disabled")
    void loginDisabledAccount() {
        String username = "disabled_u_" + System.currentTimeMillis();
        com.semanticcache.backend.dto.RegisterRequest regRequest = new com.semanticcache.backend.dto.RegisterRequest(username, "SecurePass123!");
        com.semanticcache.backend.dto.AuthSession session = authService.register(regRequest);

        // Disable user
        UserEntity user = userRepository.findById(session.userId()).orElseThrow();
        user.setEnabled(false);
        userRepository.saveAndFlush(user);

        assertThrows(com.semanticcache.backend.exception.AccountDisabledException.class,
                () -> authService.login(new com.semanticcache.backend.dto.LoginRequest(username, "SecurePass123!")));
    }

    @Test
    @DisplayName("rotateSession returns new access token and new raw refresh token")
    void rotateSessionSuccess() {
        String username = "rot_user_" + System.currentTimeMillis();
        com.semanticcache.backend.dto.RegisterRequest regRequest = new com.semanticcache.backend.dto.RegisterRequest(username, "SecurePass123!");
        com.semanticcache.backend.dto.AuthSession initial = authService.register(regRequest);

        com.semanticcache.backend.dto.AuthSession rotated = authService.rotateSession(initial.rawRefreshToken());

        assertNotNull(rotated);
        assertEquals(username, rotated.username());
        assertEquals(initial.userId(), rotated.userId());
        assertNotNull(rotated.accessToken());
        assertNotNull(rotated.rawRefreshToken());
        assertFalse(initial.rawRefreshToken().equals(rotated.rawRefreshToken()));
    }

    @Test
    @DisplayName("logout revokes refresh token in database")
    void logoutRevokesToken() {
        String username = "logout_u_" + System.currentTimeMillis();
        com.semanticcache.backend.dto.RegisterRequest regRequest = new com.semanticcache.backend.dto.RegisterRequest(username, "SecurePass123!");
        com.semanticcache.backend.dto.AuthSession session = authService.register(regRequest);

        String tokenHash = RefreshTokenUtil.hashToken(session.rawRefreshToken());
        RefreshTokenEntity entityBefore = refreshTokenRepository.findByTokenHash(tokenHash).orElseThrow();
        assertFalse(entityBefore.isRevoked());

        authService.logout(session.rawRefreshToken());

        RefreshTokenEntity entityAfter = refreshTokenRepository.findByTokenHash(tokenHash).orElseThrow();
        assertTrue(entityAfter.isRevoked());
    }

    @Test
    @DisplayName("rotateSession rejects whitespace-padded or modified tokens as not equivalent to valid tokens")
    void rotateSessionRejectsWhitespaceModifiedTokens() {
        String username = "ws_rot_u_" + System.currentTimeMillis();
        com.semanticcache.backend.dto.RegisterRequest regRequest = new com.semanticcache.backend.dto.RegisterRequest(username, "SecurePass123!");
        com.semanticcache.backend.dto.AuthSession session = authService.register(regRequest);

        String originalToken = session.rawRefreshToken();

        // 1. Token with leading or trailing whitespace is treated as distinct (not trimmed) -> fails to match
        assertThrows(InvalidRefreshTokenException.class,
                () -> authService.rotateSession(" " + originalToken));
        assertThrows(InvalidRefreshTokenException.class,
                () -> authService.rotateSession(originalToken + " "));
        assertThrows(InvalidRefreshTokenException.class,
                () -> authService.rotateSession(" \t " + originalToken + " \n "));

        // 2. Token with internal space is not equivalent
        assertThrows(InvalidRefreshTokenException.class,
                () -> authService.rotateSession(originalToken.substring(0, 5) + " " + originalToken.substring(5)));

        // 3. Null or blank tokens throw InvalidRefreshTokenException
        assertThrows(InvalidRefreshTokenException.class, () -> authService.rotateSession(null));
        assertThrows(InvalidRefreshTokenException.class, () -> authService.rotateSession(""));
        assertThrows(InvalidRefreshTokenException.class, () -> authService.rotateSession("   "));
        assertThrows(InvalidRefreshTokenException.class, () -> authService.rotateSession("\t\n"));

        // 4. The original token remains valid and untouched
        com.semanticcache.backend.dto.AuthSession rotated = authService.rotateSession(originalToken);
        assertNotNull(rotated);
        assertNotNull(rotated.accessToken());
    }

    @Test
    @DisplayName("logout with whitespace-padded token does not revoke original token and blank logout is harmless")
    void logoutWhitespaceHandling() {
        String username = "ws_logout_u_" + System.currentTimeMillis();
        com.semanticcache.backend.dto.RegisterRequest regRequest = new com.semanticcache.backend.dto.RegisterRequest(username, "SecurePass123!");
        com.semanticcache.backend.dto.AuthSession session = authService.register(regRequest);

        String originalToken = session.rawRefreshToken();
        String originalHash = RefreshTokenUtil.hashToken(originalToken);

        // 1. Blank or null logout is completely harmless and does not throw
        authService.logout(null);
        authService.logout("");
        authService.logout("   ");
        authService.logout("\t\n");

        RefreshTokenEntity entityBefore = refreshTokenRepository.findByTokenHash(originalHash).orElseThrow();
        assertFalse(entityBefore.isRevoked(), "Original token must not be revoked by blank logouts");

        // 2. Logout with whitespace-padded token hashes unchanged string -> does not match original token
        authService.logout(" " + originalToken);
        authService.logout(originalToken + " ");

        RefreshTokenEntity entityStillActive = refreshTokenRepository.findByTokenHash(originalHash).orElseThrow();
        assertFalse(entityStillActive.isRevoked(), "Original token must not be revoked by whitespace-padded token");

        // 3. Logout with exact original token succeeds
        authService.logout(originalToken);
        RefreshTokenEntity entityRevoked = refreshTokenRepository.findByTokenHash(originalHash).orElseThrow();
        assertTrue(entityRevoked.isRevoked(), "Original token must be revoked when exact token is passed");
    }
}
