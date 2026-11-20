package com.semanticcache.backend.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class PasswordEncoderConfigTest {

    private static final Pattern BCRYPT_PATTERN = Pattern.compile("^\\$2[aby]\\$(\\d{2})\\$[./A-Za-z0-9]{53}$");

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    @DisplayName("PasswordEncoder bean loads successfully into the Spring ApplicationContext")
    void passwordEncoderBeanLoads() {
        assertNotNull(passwordEncoder, "PasswordEncoder bean must be registered in the ApplicationContext");
    }

    @Test
    @DisplayName("BCrypt encoding is used and outputs the expected standard format")
    void bcryptEncodingIsUsed() {
        String testInput = "sample_test_passphrase_for_validation_only";
        String encoded = passwordEncoder.encode(testInput);

        assertNotNull(encoded);
        assertTrue(encoded.startsWith("$2a$") || encoded.startsWith("$2b$") || encoded.startsWith("$2y$"),
                "Encoded string must start with standard BCrypt identifier prefix");
    }

    @Test
    @DisplayName("Encoded password never equals raw input")
    void encodedPasswordIsNotRawInput() {
        String testInput = "sample_test_passphrase_for_validation_only";
        String encoded = passwordEncoder.encode(testInput);

        assertNotEquals(testInput, encoded, "Encoded password must not match plaintext input");
    }

    @Test
    @DisplayName("Correct password matches encoded hash and incorrect password fails matching")
    void passwordMatchingVerification() {
        String testInput = "sample_test_passphrase_for_validation_only";
        String wrongInput = "incorrect_passphrase_for_validation_only";

        String encoded = passwordEncoder.encode(testInput);

        assertTrue(passwordEncoder.matches(testInput, encoded), "Correct password must match hash");
        assertFalse(passwordEncoder.matches(wrongInput, encoded), "Incorrect password must not match hash");
        assertFalse(passwordEncoder.matches("", encoded), "Empty password must not match hash");
    }

    @Test
    @DisplayName("BCrypt work factor is configured to exactly 12")
    void bcryptWorkFactorIsTwelve() {
        String testInput = "sample_test_passphrase_for_validation_only";
        String encoded = passwordEncoder.encode(testInput);

        Matcher matcher = BCRYPT_PATTERN.matcher(encoded);
        assertTrue(matcher.matches(), "Encoded string must match BCrypt regex pattern");

        String costFactor = matcher.group(1);
        assertEquals("12", costFactor, "BCrypt cost factor must be exactly 12");
    }
}
