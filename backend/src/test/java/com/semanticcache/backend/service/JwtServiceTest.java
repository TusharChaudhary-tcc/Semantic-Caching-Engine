package com.semanticcache.backend.service;

import com.semanticcache.backend.exception.InvalidJwtException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtServiceTest {

    // Valid 32-byte / 256-bit Base64 secret key
    private static final String TEST_SECRET_BASE64 = "s+9zXQ3x1J0u4qLw9vM8kR7tY6pA5sD4fG3hJ2kL1z8=";
    private static final String OTHER_SECRET_BASE64 = "YW5vdGhlcl9zZWNyZXRfa2V5X3dpdGhfMzJfYnl0ZXMhIQ==";

    private MutableClock testClock;
    private JwtService jwtService;
    private SecretKey testKey;

    @BeforeEach
    void setUp() {
        testClock = new MutableClock(Instant.now().truncatedTo(ChronoUnit.SECONDS));
        jwtService = new JwtService(TEST_SECRET_BASE64, testClock);
        testKey = Keys.hmacShaKeyFor(Base64.getDecoder().decode(TEST_SECRET_BASE64));
    }

    private Jws<Claims> parseWithTestClock(String token) {
        return Jwts.parser()
                .verifyWith(testKey)
                .clock(() -> Date.from(testClock.instant()))
                .build()
                .parseSignedClaims(token);
    }

    @Test
    @DisplayName("1. Successful token generation and validation returns the exact UUID user ID")
    void successfulGenerationAndValidation() {
        UUID userId = UUID.randomUUID();
        String token = jwtService.generateAccessToken(userId.toString());

        assertNotNull(token);
        assertFalse(token.isBlank());

        UUID validatedUserId = jwtService.validateAccessToken(token);
        assertEquals(userId, validatedUserId, "Validated UUID must match the issued user ID");
    }

    @Test
    @DisplayName("2. Generated token header explicitly uses HS256 algorithm")
    void algorithmIsExplicitlyHS256() {
        String token = jwtService.generateAccessToken(UUID.randomUUID().toString());
        Jws<Claims> jws = parseWithTestClock(token);
        assertEquals("HS256", jws.getHeader().getAlgorithm());
    }

    @Test
    @DisplayName("3. Generated token payload contains the exact issuer 'semanticcache-backend'")
    void tokenContainsExpectedIssuer() {
        String token = jwtService.generateAccessToken(UUID.randomUUID().toString());
        Jws<Claims> jws = parseWithTestClock(token);
        assertEquals("semanticcache-backend", jws.getPayload().getIssuer());
    }

    @Test
    @DisplayName("4. Generated token payload subject matches the canonical UUID string")
    void tokenContainsExpectedSubject() {
        UUID userId = UUID.randomUUID();
        String token = jwtService.generateAccessToken(userId.toString());
        Jws<Claims> jws = parseWithTestClock(token);
        assertEquals(userId.toString(), jws.getPayload().getSubject());
    }

    @Test
    @DisplayName("5. Generated token contains custom claim typ=access")
    void tokenContainsTypeAccessClaim() {
        String token = jwtService.generateAccessToken(UUID.randomUUID().toString());
        Jws<Claims> jws = parseWithTestClock(token);
        assertEquals("access", jws.getPayload().get("typ", String.class));
    }

    @Test
    @DisplayName("6. Generated token expiration is exactly 15 minutes after issuance")
    void tokenLifetimeIsFifteenMinutes() {
        Instant issuanceTime = testClock.instant();
        String token = jwtService.generateAccessToken(UUID.randomUUID().toString());
        Jws<Claims> jws = parseWithTestClock(token);

        Date issuedAt = jws.getPayload().getIssuedAt();
        Date expiration = jws.getPayload().getExpiration();

        assertEquals(issuanceTime.getEpochSecond(), issuedAt.toInstant().getEpochSecond());
        assertEquals(issuanceTime.plus(Duration.ofMinutes(15)).getEpochSecond(), expiration.toInstant().getEpochSecond());
    }

    @Test
    @DisplayName("7. Expired token past clock-skew window is rejected with InvalidJwtException")
    void expiredTokenIsRejected() {
        String token = jwtService.generateAccessToken(UUID.randomUUID().toString());

        // Fast-forward past 15m expiration + 30s clock skew
        testClock.advance(Duration.ofMinutes(16));

        assertThrows(InvalidJwtException.class, () -> jwtService.validateAccessToken(token));
    }

    @Test
    @DisplayName("8. Clock skew allowance accepts token within 30 seconds of expiry and rejects beyond 30 seconds")
    void clockSkewBehavior() {
        String token = jwtService.generateAccessToken(UUID.randomUUID().toString());

        // Advance to 15 minutes and 20 seconds (within 30s clock skew)
        testClock.advance(Duration.ofMinutes(15).plusSeconds(20));
        assertNotNull(jwtService.validateAccessToken(token), "Token within 30s skew window must be accepted");

        // Advance to 15 minutes and 35 seconds (outside 30s clock skew)
        testClock.advance(Duration.ofSeconds(15));
        assertThrows(InvalidJwtException.class, () -> jwtService.validateAccessToken(token),
                "Token outside 30s skew window must be rejected");
    }

    @Test
    @DisplayName("9. Token with wrong issuer is rejected with InvalidJwtException")
    void wrongIssuerIsRejected() {
        Instant now = testClock.instant();
        String invalidIssuerToken = Jwts.builder()
                .issuer("untrusted-foreign-issuer")
                .subject(UUID.randomUUID().toString())
                .claim("typ", "access")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(Duration.ofMinutes(15))))
                .signWith(testKey, Jwts.SIG.HS256)
                .compact();

        assertThrows(InvalidJwtException.class, () -> jwtService.validateAccessToken(invalidIssuerToken));
    }

    @Test
    @DisplayName("10. Token signed with wrong secret key is rejected with InvalidJwtException")
    void wrongSignatureIsRejected() {
        SecretKey otherKey = Keys.hmacShaKeyFor(Base64.getDecoder().decode(OTHER_SECRET_BASE64));
        Instant now = testClock.instant();

        String wrongKeyToken = Jwts.builder()
                .issuer("semanticcache-backend")
                .subject(UUID.randomUUID().toString())
                .claim("typ", "access")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(Duration.ofMinutes(15))))
                .signWith(otherKey, Jwts.SIG.HS256)
                .compact();

        assertThrows(InvalidJwtException.class, () -> jwtService.validateAccessToken(wrongKeyToken));
    }

    @Test
    @DisplayName("11. Token with tampered payload is rejected with InvalidJwtException")
    void tamperedPayloadIsRejected() {
        String token = jwtService.generateAccessToken(UUID.randomUUID().toString());
        String[] parts = token.split("\\.");
        assertEquals(3, parts.length);

        // Tamper with base64 payload bytes
        String tamperedPayload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"sub\":\"tampered\",\"typ\":\"access\"}".getBytes(StandardCharsets.UTF_8));
        String tamperedToken = parts[0] + "." + tamperedPayload + "." + parts[2];

        assertThrows(InvalidJwtException.class, () -> jwtService.validateAccessToken(tamperedToken));
    }

    @Test
    @DisplayName("12. Token missing typ claim is rejected with InvalidJwtException")
    void missingTypeClaimIsRejected() {
        Instant now = testClock.instant();
        String tokenWithoutType = Jwts.builder()
                .issuer("semanticcache-backend")
                .subject(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(Duration.ofMinutes(15))))
                .signWith(testKey, Jwts.SIG.HS256)
                .compact();

        assertThrows(InvalidJwtException.class, () -> jwtService.validateAccessToken(tokenWithoutType));
    }

    @Test
    @DisplayName("13. Token with incorrect typ (e.g. refresh) is rejected with InvalidJwtException")
    void incorrectTypeClaimIsRejected() {
        Instant now = testClock.instant();
        String refreshToken = Jwts.builder()
                .issuer("semanticcache-backend")
                .subject(UUID.randomUUID().toString())
                .claim("typ", "refresh")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(Duration.ofMinutes(15))))
                .signWith(testKey, Jwts.SIG.HS256)
                .compact();

        assertThrows(InvalidJwtException.class, () -> jwtService.validateAccessToken(refreshToken));
    }

    @Test
    @DisplayName("14. Token with non-UUID subject is rejected with InvalidJwtException")
    void nonUuidSubjectIsRejected() {
        Instant now = testClock.instant();
        String nonUuidToken = Jwts.builder()
                .issuer("semanticcache-backend")
                .subject("not-a-valid-uuid-identifier")
                .claim("typ", "access")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(Duration.ofMinutes(15))))
                .signWith(testKey, Jwts.SIG.HS256)
                .compact();

        assertThrows(InvalidJwtException.class, () -> jwtService.validateAccessToken(nonUuidToken));
    }

    @Test
    @DisplayName("15. Malformed, blank, or null tokens are rejected with InvalidJwtException")
    void malformedTokensAreRejected() {
        assertThrows(InvalidJwtException.class, () -> jwtService.validateAccessToken(null));
        assertThrows(InvalidJwtException.class, () -> jwtService.validateAccessToken(""));
        assertThrows(InvalidJwtException.class, () -> jwtService.validateAccessToken("   "));
        assertThrows(InvalidJwtException.class, () -> jwtService.validateAccessToken("invalid.token.parts"));
        assertThrows(InvalidJwtException.class, () -> jwtService.validateAccessToken("completely_random_garbage_string"));
    }

    @Test
    @DisplayName("16. Invalid Base64 secret fails initialization with IllegalStateException")
    void invalidBase64SecretFailsInitialization() {
        assertThrows(IllegalStateException.class, () -> new JwtService("not-valid-base64-!@#$%", testClock));
    }

    @Test
    @DisplayName("17. Secret shorter than 32 decoded bytes fails initialization with IllegalStateException")
    void shortSecretFailsInitialization() {
        // "short_secret" is only 12 bytes
        String shortSecretBase64 = Base64.getEncoder().encodeToString("short_secret".getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalStateException.class, () -> new JwtService(shortSecretBase64, testClock));
    }

    @Test
    @DisplayName("generateAccessToken rejects null, blank, or non-UUID userId with IllegalArgumentException")
    void generateAccessTokenValidatesUserId() {
        assertThrows(IllegalArgumentException.class, () -> jwtService.generateAccessToken(null));
        assertThrows(IllegalArgumentException.class, () -> jwtService.generateAccessToken(""));
        assertThrows(IllegalArgumentException.class, () -> jwtService.generateAccessToken("   "));
        assertThrows(IllegalArgumentException.class, () -> jwtService.generateAccessToken("non-uuid-string"));
    }

    @Test
    @DisplayName("generateAccessToken rejects shortened UUID representations")
    void generateAccessTokenRejectsShortenedUuid() {
        assertThrows(IllegalArgumentException.class, () -> jwtService.generateAccessToken("1-2-3-4-5"));
        assertThrows(IllegalArgumentException.class, () -> jwtService.generateAccessToken("e80a7d9"));
    }

    @Test
    @DisplayName("generateAccessToken rejects UUIDs containing leading or trailing whitespace without trimming")
    void generateAccessTokenRejectsWhitespaceAroundUuid() {
        String validUuid = UUID.randomUUID().toString();
        assertThrows(IllegalArgumentException.class, () -> jwtService.generateAccessToken(" " + validUuid));
        assertThrows(IllegalArgumentException.class, () -> jwtService.generateAccessToken(validUuid + " "));
        assertThrows(IllegalArgumentException.class, () -> jwtService.generateAccessToken("  " + validUuid + "  "));
        assertThrows(IllegalArgumentException.class, () -> jwtService.generateAccessToken("\t" + validUuid + "\n"));
    }

    @Test
    @DisplayName("generateAccessToken rejects non-canonical uppercase UUID representations")
    void generateAccessTokenRejectsNonCanonicalUppercaseUuid() {
        String upperUuid = UUID.randomUUID().toString().toUpperCase();
        assertThrows(IllegalArgumentException.class, () -> jwtService.generateAccessToken(upperUuid));
    }

    @Test
    @DisplayName("validateAccessToken rejects shortened subject UUID representations")
    void validateAccessTokenRejectsShortenedSubjectUuid() {
        Instant now = testClock.instant();
        String token = Jwts.builder()
                .issuer("semanticcache-backend")
                .subject("1-2-3-4-5")
                .claim("typ", "access")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(Duration.ofMinutes(15))))
                .signWith(testKey, Jwts.SIG.HS256)
                .compact();

        assertThrows(InvalidJwtException.class, () -> jwtService.validateAccessToken(token));
    }

    @Test
    @DisplayName("validateAccessToken rejects subject UUID with leading or trailing whitespace")
    void validateAccessTokenRejectsSubjectWithWhitespace() {
        Instant now = testClock.instant();
        String token = Jwts.builder()
                .issuer("semanticcache-backend")
                .subject(" " + UUID.randomUUID() + " ")
                .claim("typ", "access")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(Duration.ofMinutes(15))))
                .signWith(testKey, Jwts.SIG.HS256)
                .compact();

        assertThrows(InvalidJwtException.class, () -> jwtService.validateAccessToken(token));
    }

    @Test
    @DisplayName("validateAccessToken rejects non-canonical uppercase subject UUID")
    void validateAccessTokenRejectsNonCanonicalUppercaseSubject() {
        Instant now = testClock.instant();
        String token = Jwts.builder()
                .issuer("semanticcache-backend")
                .subject(UUID.randomUUID().toString().toUpperCase())
                .claim("typ", "access")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(Duration.ofMinutes(15))))
                .signWith(testKey, Jwts.SIG.HS256)
                .compact();

        assertThrows(InvalidJwtException.class, () -> jwtService.validateAccessToken(token));
    }

    @Test
    @DisplayName("validateAccessToken rejects valid JWT with leading or trailing whitespace without trimming")
    void validateAccessTokenRejectsWhitespaceAroundJwt() {
        String validToken = jwtService.generateAccessToken(UUID.randomUUID().toString());
        assertThrows(InvalidJwtException.class, () -> jwtService.validateAccessToken(" " + validToken));
        assertThrows(InvalidJwtException.class, () -> jwtService.validateAccessToken(validToken + " "));
        assertThrows(InvalidJwtException.class, () -> jwtService.validateAccessToken("  " + validToken + "  "));
        assertThrows(InvalidJwtException.class, () -> jwtService.validateAccessToken("\t" + validToken + "\n"));
    }

    @Test
    @DisplayName("validateAccessToken strictly enforces exact algorithm identifier comparison")
    void exactAlgorithmComparisonEnforced() {
        // Use a 64-byte key capable of HS512 to produce a token with alg=HS512
        byte[] keyBytes64 = new byte[64];
        for (int i = 0; i < 64; i++) keyBytes64[i] = (byte) (i + 1);
        String base64Key64 = Base64.getEncoder().encodeToString(keyBytes64);
        JwtService serviceWithKey64 = new JwtService(base64Key64, testClock);
        SecretKey hmacKey64 = Keys.hmacShaKeyFor(keyBytes64);

        Instant now = testClock.instant();
        String hs512Token = Jwts.builder()
                .issuer("semanticcache-backend")
                .subject(UUID.randomUUID().toString())
                .claim("typ", "access")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(Duration.ofMinutes(15))))
                .signWith(hmacKey64, Jwts.SIG.HS512)
                .compact();

        InvalidJwtException ex = assertThrows(InvalidJwtException.class,
                () -> serviceWithKey64.validateAccessToken(hs512Token));
        assertTrue(ex.getMessage().contains("Token algorithm must be HS256"),
                "Exception message must identify algorithm mismatch");
    }

    @Test
    @DisplayName("Exception cause chain does not disclose supplied user ID")
    void exceptionCauseChainDoesNotDiscloseSuppliedId() {
        String sensitiveId = "sensitive-custom-user-id-value-12345";
        IllegalArgumentException genEx = assertThrows(IllegalArgumentException.class,
                () -> jwtService.generateAccessToken(sensitiveId));
        assertNoCauseDiscloses(genEx, sensitiveId);

        Instant now = testClock.instant();
        String token = Jwts.builder()
                .issuer("semanticcache-backend")
                .subject(sensitiveId)
                .claim("typ", "access")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(Duration.ofMinutes(15))))
                .signWith(testKey, Jwts.SIG.HS256)
                .compact();

        InvalidJwtException valEx = assertThrows(InvalidJwtException.class,
                () -> jwtService.validateAccessToken(token));
        assertNoCauseDiscloses(valEx, sensitiveId);
    }

    private void assertNoCauseDiscloses(Throwable throwable, String sensitiveValue) {
        Throwable current = throwable;
        while (current != null) {
            if (current.getMessage() != null) {
                assertFalse(current.getMessage().contains(sensitiveValue),
                        "Exception message in cause chain must not leak input value: " + current.getClass().getName());
            }
            current = current.getCause();
        }
    }

    /**
     * Helper mutable Clock for deterministic testing without Thread.sleep.
     */
    private static class MutableClock extends Clock {
        private Instant currentInstant;
        private final ZoneOffset zone = ZoneOffset.UTC;

        public MutableClock(Instant initialInstant) {
            this.currentInstant = initialInstant;
        }

        public void advance(Duration duration) {
            this.currentInstant = this.currentInstant.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return zone;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return Clock.fixed(currentInstant, zone);
        }

        @Override
        public Instant instant() {
            return currentInstant;
        }
    }
}
