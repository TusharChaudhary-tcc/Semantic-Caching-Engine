package com.semanticcache.backend.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthDtoTest {

    private static Validator validator;
    private static ObjectMapper objectMapper;

    @BeforeAll
    static void setUp() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
        objectMapper = new ObjectMapper();
    }

    // --- RegisterRequest Tests ---

    @Test
    @DisplayName("Valid RegisterRequest passes validation")
    void validRegisterRequestPasses() {
        RegisterRequest request = new RegisterRequest("testuser", "securePassword123");
        Set<ConstraintViolation<RegisterRequest>> violations = validator.validate(request);
        assertTrue(violations.isEmpty(), "Valid request should produce no violations");
    }

    @Test
    @DisplayName("RegisterRequest rejects null or blank username")
    void registerRequestRejectsNullOrBlankUsername() {
        RegisterRequest nullUser = new RegisterRequest(null, "securePassword123");
        RegisterRequest blankUser = new RegisterRequest("   ", "securePassword123");

        assertFalse(validator.validate(nullUser).isEmpty());
        assertFalse(validator.validate(blankUser).isEmpty());
    }

    @Test
    @DisplayName("RegisterRequest enforces username 3-50 length bounds")
    void registerRequestEnforcesUsernameLengthBounds() {
        RegisterRequest shortUser = new RegisterRequest("ab", "securePassword123");
        RegisterRequest longUser = new RegisterRequest("a".repeat(51), "securePassword123");
        RegisterRequest minUser = new RegisterRequest("abc", "securePassword123");
        RegisterRequest maxUser = new RegisterRequest("a".repeat(50), "securePassword123");

        assertFalse(validator.validate(shortUser).isEmpty());
        assertFalse(validator.validate(longUser).isEmpty());
        assertTrue(validator.validate(minUser).isEmpty());
        assertTrue(validator.validate(maxUser).isEmpty());
    }

    @Test
    @DisplayName("RegisterRequest rejects null or blank password")
    void registerRequestRejectsNullOrBlankPassword() {
        RegisterRequest nullPass = new RegisterRequest("testuser", null);
        RegisterRequest blankPass = new RegisterRequest("testuser", "        ");

        assertFalse(validator.validate(nullPass).isEmpty());
        assertFalse(validator.validate(blankPass).isEmpty());
    }

    @Test
    @DisplayName("RegisterRequest enforces password length bounds between 8 and 72 characters")
    void registerRequestEnforcesPasswordLengthBounds() {
        RegisterRequest shortPass = new RegisterRequest("testuser", "short7");
        RegisterRequest longPass = new RegisterRequest("testuser", "p".repeat(73));
        RegisterRequest minPass = new RegisterRequest("testuser", "p".repeat(8));
        RegisterRequest maxPass = new RegisterRequest("testuser", "p".repeat(72));

        assertFalse(validator.validate(shortPass).isEmpty(), "Password under 8 characters must be rejected");
        assertFalse(validator.validate(longPass).isEmpty(), "Password over 72 characters must be rejected");
        assertTrue(validator.validate(minPass).isEmpty(), "8-character password must be accepted");
        assertTrue(validator.validate(maxPass).isEmpty(), "72-character password must be accepted");
    }

    @Test
    @DisplayName("RegisterRequest accepts password at exact 72-byte UTF-8 boundary")
    void registerRequestAcceptsExact72BytePassword() {
        // 72 ASCII characters = exactly 72 bytes
        String ascii72 = "a".repeat(72);
        assertEquals(72, ascii72.getBytes(StandardCharsets.UTF_8).length);
        assertTrue(validator.validate(new RegisterRequest("testuser", ascii72)).isEmpty());

        // 24 three-byte UTF-8 characters = exactly 72 bytes
        String utf8_72 = "\u4E2D".repeat(24);
        assertEquals(24, utf8_72.length());
        assertEquals(72, utf8_72.getBytes(StandardCharsets.UTF_8).length);
        assertTrue(validator.validate(new RegisterRequest("testuser", utf8_72)).isEmpty());
    }

    @Test
    @DisplayName("RegisterRequest rejects multibyte password exceeding 72 UTF-8 bytes even when char count < 72")
    void registerRequestRejectsMultibytePasswordExceeding72Bytes() {
        // 25 three-byte UTF-8 characters = 75 bytes (well under the 72-char limit)
        String utf8_75 = "\u4E2D".repeat(25);
        assertEquals(25, utf8_75.length());
        assertEquals(75, utf8_75.getBytes(StandardCharsets.UTF_8).length);

        Set<ConstraintViolation<RegisterRequest>> violations = validator.validate(new RegisterRequest("testuser", utf8_75));
        assertFalse(violations.isEmpty(), "Multibyte password exceeding 72 bytes must be rejected");
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("password")));
    }

    // --- LoginRequest Tests ---

    @Test
    @DisplayName("Valid LoginRequest passes validation")
    void validLoginRequestPasses() {
        LoginRequest request = new LoginRequest("testuser", "anyPassword");
        Set<ConstraintViolation<LoginRequest>> violations = validator.validate(request);
        assertTrue(violations.isEmpty());
    }

    @Test
    @DisplayName("LoginRequest rejects blank username or password")
    void loginRequestRejectsBlankFields() {
        LoginRequest blankUser = new LoginRequest("   ", "password123");
        LoginRequest blankPass = new LoginRequest("testuser", "   ");

        assertFalse(validator.validate(blankUser).isEmpty());
        assertFalse(validator.validate(blankPass).isEmpty());
    }

    @Test
    @DisplayName("LoginRequest does not enforce registration-only minimum password length")
    void loginRequestDoesNotEnforceMinPasswordLength() {
        LoginRequest shortPass = new LoginRequest("testuser", "abc");
        assertTrue(validator.validate(shortPass).isEmpty(), "Login must not enforce registration-only minimum length");
    }

    @Test
    @DisplayName("LoginRequest enforces maximum username length")
    void loginRequestEnforcesMaxUsernameLength() {
        LoginRequest longUser = new LoginRequest("u".repeat(51), "pass");
        assertFalse(validator.validate(longUser).isEmpty());
    }

    @Test
    @DisplayName("LoginRequest accepts ASCII password at exact 72-byte boundary")
    void loginRequestAcceptsExact72BytePassword() {
        String ascii72 = "a".repeat(72);
        assertTrue(validator.validate(new LoginRequest("testuser", ascii72)).isEmpty());
    }

    @Test
    @DisplayName("LoginRequest rejects password exceeding 72 UTF-8 bytes")
    void loginRequestRejectsPasswordExceeding72Bytes() {
        // ASCII exceeding 72 bytes (73 chars = 73 bytes)
        String ascii73 = "a".repeat(73);
        Set<ConstraintViolation<LoginRequest>> asciiViolations = validator.validate(new LoginRequest("testuser", ascii73));
        assertFalse(asciiViolations.isEmpty(), "Login password exceeding 72 ASCII bytes must be rejected");

        // Multibyte exceeding 72 bytes (25 three-byte chars = 75 bytes, char count 25)
        String utf8_75 = "\u4E2D".repeat(25);
        Set<ConstraintViolation<LoginRequest>> utf8Violations = validator.validate(new LoginRequest("testuser", utf8_75));
        assertFalse(utf8Violations.isEmpty(), "Login password exceeding 72 UTF-8 bytes must be rejected");
        assertTrue(utf8Violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("password")));
    }

    // --- JSON Serialization Cleanliness Tests ---

    @Test
    @DisplayName("RegisterRequest serializes to JSON with only username and password (no unwanted properties)")
    void registerRequestJsonContainsNoUnwantedProperties() throws IOException {
        RegisterRequest request = new RegisterRequest("alice", "secret123");
        String json = objectMapper.writeValueAsString(request);
        JsonNode root = objectMapper.readTree(json);

        assertEquals(2, root.size(), "RegisterRequest JSON must contain exactly 2 properties");
        assertTrue(root.has("username"));
        assertTrue(root.has("password"));
    }

    @Test
    @DisplayName("LoginRequest serializes to JSON with only username and password (no unwanted properties)")
    void loginRequestJsonContainsNoUnwantedProperties() throws IOException {
        LoginRequest request = new LoginRequest("alice", "secret123");
        String json = objectMapper.writeValueAsString(request);
        JsonNode root = objectMapper.readTree(json);

        assertEquals(2, root.size(), "LoginRequest JSON must contain exactly 2 properties");
        assertTrue(root.has("username"));
        assertTrue(root.has("password"));
    }

    // --- AuthResponse Tests ---

    @Test
    @DisplayName("AuthResponse serializes to JSON with all expected fields and default Bearer tokenType")
    void authResponseSerializesExpectedFields() throws IOException {
        String userId = UUID.randomUUID().toString();
        AuthResponse response = new AuthResponse("mock.jwt.access.token", 900L, userId, "alice");

        String json = objectMapper.writeValueAsString(response);
        JsonNode root = objectMapper.readTree(json);

        assertEquals("mock.jwt.access.token", root.get("accessToken").asText());
        assertEquals("Bearer", root.get("tokenType").asText());
        assertEquals(900L, root.get("expiresIn").asLong());
        assertEquals(userId, root.get("userId").asText());
        assertEquals("alice", root.get("username").asText());
    }

    @Test
    @DisplayName("AuthResponse strictly never contains a refresh token or hash field")
    void authResponseDoesNotExposeRefreshToken() throws IOException {
        String userId = UUID.randomUUID().toString();
        AuthResponse response = AuthResponse.of("mock.jwt.token", 900L, userId, "alice");

        String json = objectMapper.writeValueAsString(response);

        assertFalse(json.contains("refreshToken"), "AuthResponse JSON must never contain refreshToken field");
        assertFalse(json.contains("refresh_token"), "AuthResponse JSON must never contain refresh_token field");
        assertFalse(json.contains("passwordHash"), "AuthResponse JSON must never contain passwordHash");
        assertFalse(json.contains("familyId"), "AuthResponse JSON must never contain familyId");
    }
}
