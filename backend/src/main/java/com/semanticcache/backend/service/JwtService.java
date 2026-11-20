package com.semanticcache.backend.service;

import com.semanticcache.backend.exception.InvalidJwtException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Objects;
import java.util.UUID;

/**
 * Production-oriented service responsible for generating and validating HS256 JWT access tokens.
 * <p>
 * Tokens have a strict 15-minute lifetime, require canonical UUID subjects, custom claim {@code typ=access},
 * and are verified with a 30-second clock-skew tolerance against the HMAC-SHA256 signing secret.
 */
@Service
public class JwtService {

    public static final String ISSUER = "semanticcache-backend";
    public static final String CLAIM_TYPE = "typ";
    public static final String ACCESS_TYPE_VALUE = "access";
    public static final Duration ACCESS_TOKEN_LIFETIME = Duration.ofMinutes(15);
    public static final long CLOCK_SKEW_SECONDS = 30L;
    public static final int MIN_SECRET_BYTE_LENGTH = 32; // 256 bits minimum for HS256

    private final SecretKey secretKey;
    private final Clock clock;

    @Autowired
    public JwtService(@Value("${app.security.jwt.secret:}") String secretKeyBase64) {
        this(secretKeyBase64, Clock.systemUTC());
    }

    /**
     * Constructor supporting an injectable {@link Clock} for deterministic testing.
     *
     * @param secretKeyBase64 Base64-encoded secret containing at least 32 bytes (256 bits)
     * @param clock           time provider used for token issuance, expiration, and skew validation
     */
    public JwtService(String secretKeyBase64, Clock clock) {
        this.clock = Objects.requireNonNull(clock, "Clock must not be null");
        this.secretKey = initSigningKey(secretKeyBase64);
    }

    /**
     * Validates and decodes the Base64 signing secret, ensuring minimum decoded key length of 32 bytes (256 bits).
     * Fails fast during bean initialization if the secret is missing or insecure.
     */
    private static SecretKey initSigningKey(String secretKeyBase64) {
        if (secretKeyBase64 == null || secretKeyBase64.trim().isEmpty()) {
            throw new IllegalStateException("JWT secret configuration 'app.security.jwt.secret' must not be null or blank");
        }
        byte[] decodedBytes;
        try {
            decodedBytes = Base64.getDecoder().decode(secretKeyBase64.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("JWT secret must be a valid Base64-encoded string", e);
        }
        if (decodedBytes.length < MIN_SECRET_BYTE_LENGTH) {
            throw new IllegalStateException(
                    "JWT secret must contain at least " + MIN_SECRET_BYTE_LENGTH + " bytes (256 bits). " +
                    "Decoded length was: " + decodedBytes.length + " bytes"
            );
        }
        return Keys.hmacShaKeyFor(decodedBytes);
    }

    /**
     * Generates a signed HS256 JWT access token with a 15-minute lifetime.
     *
     * @param userId canonical UUID string identifier of the authenticated user
     * @return compact URL-safe JWT string
     * @throws IllegalArgumentException if {@code userId} is null, blank, or not a valid canonical UUID
     */
    public String generateAccessToken(String userId) {
        if (userId == null) {
            throw new IllegalArgumentException("User ID must not be null");
        }
        UUID parsedUuid;
        try {
            parsedUuid = UUID.fromString(userId);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("User ID must be a valid canonical UUID string");
        }
        if (!parsedUuid.toString().equals(userId)) {
            throw new IllegalArgumentException("User ID must be a valid canonical UUID string");
        }

        Instant now = clock.instant();
        Instant expiresAt = now.plus(ACCESS_TOKEN_LIFETIME);

        return Jwts.builder()
                .issuer(ISSUER)
                .subject(userId)
                .claim(CLAIM_TYPE, ACCESS_TYPE_VALUE)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(secretKey, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * Validates an access token and extracts the verified UUID subject.
     * Checks signature, HS256 algorithm, issuer, expiration, 30s clock skew, and {@code typ=access}.
     *
     * @param token compact JWT string
     * @return verified {@link UUID} of the user subject
     * @throws InvalidJwtException if the token is invalid, expired, unsigned, tampered, or malformed
     */
    public UUID validateAccessToken(String token) {
        if (token == null || token.isBlank()) {
            throw new InvalidJwtException("Token must not be null or blank");
        }
        try {
            Jws<Claims> jws = Jwts.parser()
                    .verifyWith(secretKey)
                    .clockSkewSeconds(CLOCK_SKEW_SECONDS)
                    .requireIssuer(ISSUER)
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(token);

            // Verify header algorithm is strictly HS256 (exact case-sensitive match)
            String alg = jws.getHeader().getAlgorithm();
            if (!Jwts.SIG.HS256.getId().equals(alg)) {
                throw new InvalidJwtException("Token algorithm must be HS256. Found: " + alg);
            }

            Claims claims = jws.getPayload();

            // Verify custom claim typ=access
            Object typClaim = claims.get(CLAIM_TYPE);
            if (typClaim == null || !ACCESS_TYPE_VALUE.equals(typClaim.toString())) {
                throw new InvalidJwtException("Token missing required claim 'typ=access'");
            }

            // Verify valid canonical UUID subject
            String subject = claims.getSubject();
            if (subject == null) {
                throw new InvalidJwtException("Token subject must not be null");
            }
            UUID parsedSubject;
            try {
                parsedSubject = UUID.fromString(subject);
            } catch (IllegalArgumentException e) {
                throw new InvalidJwtException("Token subject must be a valid canonical UUID");
            }
            if (!parsedSubject.toString().equals(subject)) {
                throw new InvalidJwtException("Token subject must be a valid canonical UUID");
            }
            return parsedSubject;
        } catch (InvalidJwtException e) {
            throw e;
        } catch (JwtException e) {
            throw new InvalidJwtException("JWT validation failed: " + e.getClass().getSimpleName(), e);
        } catch (IllegalArgumentException e) {
            throw new InvalidJwtException("Malformed or unparseable JWT", e);
        }
    }
}
