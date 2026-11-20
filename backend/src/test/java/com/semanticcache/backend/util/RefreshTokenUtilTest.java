package com.semanticcache.backend.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RefreshTokenUtilTest {

    private static final Pattern HEX_64_PATTERN = Pattern.compile("^[0-9a-f]{64}$");

    @Test
    @DisplayName("generateSecureRawToken contains at least 256 bits of entropy")
    void rawTokenContainsAtLeast256Bits() {
        String rawToken = RefreshTokenUtil.generateSecureRawToken();
        assertNotNull(rawToken);

        byte[] decoded = Base64.getUrlDecoder().decode(rawToken);
        assertEquals(32, decoded.length, "Raw token must contain exactly 32 bytes (256 bits) of entropy");
    }

    @Test
    @DisplayName("generateSecureRawToken produces unique values across multiple invocations")
    void rawTokensAreUnique() {
        Set<String> tokens = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            tokens.add(RefreshTokenUtil.generateSecureRawToken());
        }
        assertEquals(100, tokens.size(), "Each generated token must be unique");
    }

    @Test
    @DisplayName("hashToken produces exactly 64-character lowercase hexadecimal SHA-256 string")
    void hashTokenProduces64CharHex() {
        String rawToken = RefreshTokenUtil.generateSecureRawToken();
        String hash = RefreshTokenUtil.hashToken(rawToken);

        assertNotNull(hash);
        assertEquals(64, hash.length(), "SHA-256 hex string must be exactly 64 characters");
        assertTrue(HEX_64_PATTERN.matcher(hash).matches(), "Hash must match 64-character lowercase hex regex");
    }

    @Test
    @DisplayName("hashToken is deterministic for identical inputs")
    void hashTokenIsDeterministic() {
        String token = "fixed_test_token_string_value_123456789";
        String hash1 = RefreshTokenUtil.hashToken(token);
        String hash2 = RefreshTokenUtil.hashToken(token);

        assertEquals(hash1, hash2, "Hashing the same raw token must produce identical digests");
    }

    @Test
    @DisplayName("Raw token strictly differs from its SHA-256 hash digest")
    void rawTokenDiffersFromHash() {
        String rawToken = RefreshTokenUtil.generateSecureRawToken();
        String hash = RefreshTokenUtil.hashToken(rawToken);

        assertNotEquals(rawToken, hash, "Raw token must never equal its hash digest");
    }

    @Test
    @DisplayName("hashToken rejects null, empty, or blank strings")
    void hashTokenRejectsBlankInput() {
        assertThrows(IllegalArgumentException.class, () -> RefreshTokenUtil.hashToken(null));
        assertThrows(IllegalArgumentException.class, () -> RefreshTokenUtil.hashToken(""));
        assertThrows(IllegalArgumentException.class, () -> RefreshTokenUtil.hashToken("   "));
    }
}
