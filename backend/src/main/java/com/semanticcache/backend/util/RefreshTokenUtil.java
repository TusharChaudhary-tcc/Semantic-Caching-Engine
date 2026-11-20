package com.semanticcache.backend.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Utility for cryptographically secure refresh token generation and SHA-256 hashing.
 * Raw tokens provide a minimum of 256 bits of entropy and are never stored in persistence.
 */
public final class RefreshTokenUtil {

    private static final int RAW_TOKEN_BYTE_LENGTH = 32; // 256 bits of entropy
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private RefreshTokenUtil() {
        // Utility class
    }

    /**
     * Generates a cryptographically secure random refresh token containing at least 256 bits of entropy.
     * Encoded as a URL-safe Base64 string without padding.
     *
     * @return the raw refresh token string
     */
    public static String generateSecureRawToken() {
        byte[] randomBytes = new byte[RAW_TOKEN_BYTE_LENGTH];
        SECURE_RANDOM.nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }

    /**
     * Computes the canonical 64-character lowercase hexadecimal SHA-256 digest of a raw token.
     * Only this digest is stored in the database.
     *
     * @param rawToken the raw token string (must not be null or blank)
     * @return 64-character hex SHA-256 hash
     */
    public static String hashToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new IllegalArgumentException("Raw token must not be null or blank");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest algorithm is unavailable in the JVM runtime", e);
        }
    }
}
