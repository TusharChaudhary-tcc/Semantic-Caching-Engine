package com.semanticcache.backend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.semanticcache.backend.dto.LoginRequest;
import com.semanticcache.backend.dto.RegisterRequest;
import com.semanticcache.backend.entity.RefreshTokenEntity;
import com.semanticcache.backend.entity.UserEntity;
import com.semanticcache.backend.repository.RefreshTokenRepository;
import com.semanticcache.backend.repository.UserRepository;
import com.semanticcache.backend.service.JwtService;
import com.semanticcache.backend.util.RefreshTokenCookieUtil;
import com.semanticcache.backend.util.RefreshTokenUtil;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AuthControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private JwtService jwtService;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    @DisplayName("Complete authentication round-trip: register -> login -> refresh -> reuse detection -> logout")
    void completeAuthenticationRoundTrip() throws Exception {
        String username = "flow_user_" + System.currentTimeMillis();
        String password = "SuperSecretPassword123!";

        // 1. Register
        RegisterRequest registerRequest = new RegisterRequest(username, password);
        MvcResult registerResult = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated())
                .andExpect(header().exists(HttpHeaders.SET_COOKIE))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("refreshToken=")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("HttpOnly")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Path=/api/auth")))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andExpect(jsonPath("$.username").value(username))
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andReturn();

        JsonNode registerJson = objectMapper.readTree(registerResult.getResponse().getContentAsString());
        String initialAccessToken = registerJson.get("accessToken").asText();
        String userId = registerJson.get("userId").asText();

        // Verify access token validates with JwtService
        assertEquals(UUID.fromString(userId), jwtService.validateAccessToken(initialAccessToken));

        // Extract raw refresh token cookie
        Cookie registerCookie = registerResult.getResponse().getCookie(RefreshTokenCookieUtil.COOKIE_NAME);
        assertNotNull(registerCookie);
        String initialRawRefreshToken = registerCookie.getValue();

        // 2. Duplicate registration rejected with 409 Conflict
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value("Username is already taken"));

        // 3. Login
        LoginRequest loginRequest = new LoginRequest(username, password);
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andExpect(header().exists(HttpHeaders.SET_COOKIE))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.userId").value(userId))
                .andExpect(jsonPath("$.username").value(username))
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andReturn();

        Cookie loginCookie = loginResult.getResponse().getCookie(RefreshTokenCookieUtil.COOKIE_NAME);
        assertNotNull(loginCookie);
        String loginRawRefreshToken = loginCookie.getValue();
        assertFalse(loginRawRefreshToken.equals(initialRawRefreshToken));

        // 4. Refresh token rotation
        MvcResult refreshResult = mockMvc.perform(post("/api/auth/refresh")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header("X-Requested-With", "XMLHttpRequest")
                        .cookie(loginCookie))
                .andExpect(status().isOk())
                .andExpect(header().exists(HttpHeaders.SET_COOKIE))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.userId").value(userId))
                .andReturn();

        Cookie rotatedCookie = refreshResult.getResponse().getCookie(RefreshTokenCookieUtil.COOKIE_NAME);
        assertNotNull(rotatedCookie);
        String rotatedRawRefreshToken = rotatedCookie.getValue();
        assertFalse(rotatedRawRefreshToken.equals(loginRawRefreshToken));

        // 5. Token reuse detection: presenting the now-revoked login token
        mockMvc.perform(post("/api/auth/refresh")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header("X-Requested-With", "XMLHttpRequest")
                        .cookie(loginCookie))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists(HttpHeaders.SET_COOKIE))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("Invalid or expired refresh token"));

        // Family revocation: the rotated token is now also invalidated due to reuse
        mockMvc.perform(post("/api/auth/refresh")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header("X-Requested-With", "XMLHttpRequest")
                        .cookie(rotatedCookie))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists(HttpHeaders.SET_COOKIE))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")));

        // 6. Login again to obtain a fresh token and test logout
        MvcResult logoutLoginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andReturn();

        Cookie activeCookie = logoutLoginResult.getResponse().getCookie(RefreshTokenCookieUtil.COOKIE_NAME);
        assertNotNull(activeCookie);

        // 7. Logout revokes token and clears cookie
        mockMvc.perform(post("/api/auth/logout")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header("X-Requested-With", "XMLHttpRequest")
                        .cookie(activeCookie))
                .andExpect(status().isNoContent())
                .andExpect(header().exists(HttpHeaders.SET_COOKIE))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")));

        // Verify token is revoked in database
        String activeHash = RefreshTokenUtil.hashToken(activeCookie.getValue());
        RefreshTokenEntity loggedOutEntity = refreshTokenRepository.findByTokenHash(activeHash).orElseThrow();
        assertTrue(loggedOutEntity.isRevoked());

        // Attempting to refresh with the logged out token must fail
        mockMvc.perform(post("/api/auth/refresh")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header("X-Requested-With", "XMLHttpRequest")
                        .cookie(activeCookie))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists(HttpHeaders.SET_COOKIE))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")));
    }

    @Test
    @DisplayName("Disabled account is rejected across login and refresh endpoints")
    void disabledAccountRejected() throws Exception {
        String username = "disabled_flow_" + System.currentTimeMillis();
        String password = "SecurePassword123!";

        RegisterRequest registerRequest = new RegisterRequest(username, password);
        MvcResult registerResult = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated())
                .andReturn();

        Cookie cookie = registerResult.getResponse().getCookie(RefreshTokenCookieUtil.COOKIE_NAME);
        assertNotNull(cookie);

        // Disable user
        UserEntity user = userRepository.findByUsername(username).orElseThrow();
        user.setEnabled(false);
        userRepository.saveAndFlush(user);

        // Login fails with 401
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(username, password))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("Account is disabled"));

        // Refresh fails with 401 and clears cookie
        mockMvc.perform(post("/api/auth/refresh")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header("X-Requested-With", "XMLHttpRequest")
                        .cookie(cookie))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists(HttpHeaders.SET_COOKIE))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("Invalid or expired refresh token"));
    }

    @Test
    @DisplayName("Refresh rejected with 403 ORIGIN_REQUIRED when Origin and Referer are absent")
    void refreshRejectedWhenOriginAndRefererAbsent() throws Exception {
        mockMvc.perform(post("/api/auth/refresh")
                        .header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("ORIGIN_REQUIRED"))
                .andExpect(jsonPath("$.message").value("Origin or Referer header is required"));
    }

    @Test
    @DisplayName("Refresh rejected with 403 CSRF_VALIDATION_FAILED when X-Requested-With is absent")
    void refreshRejectedWhenXRequestedWithAbsent() throws Exception {
        mockMvc.perform(post("/api/auth/refresh")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("CSRF_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("CSRF validation failed: missing or invalid X-Requested-With header"));
    }

    @Test
    @DisplayName("Refresh rejected with 403 UNTRUSTED_ORIGIN when untrusted origin is supplied")
    void refreshRejectedWhenOriginUntrusted() throws Exception {
        mockMvc.perform(post("/api/auth/refresh")
                        .header(HttpHeaders.ORIGIN, "http://evil-attacker.com")
                        .header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("UNTRUSTED_ORIGIN"))
                .andExpect(jsonPath("$.message").value("Untrusted or malformed origin"));
    }

    @Test
    @DisplayName("Logout rejected with 403 ORIGIN_REQUIRED when Origin and Referer are absent")
    void logoutRejectedWhenOriginAndRefererAbsent() throws Exception {
        mockMvc.perform(post("/api/auth/logout")
                        .header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("ORIGIN_REQUIRED"));
    }

    @Test
    @DisplayName("Logout rejected with 403 CSRF_VALIDATION_FAILED when X-Requested-With is absent")
    void logoutRejectedWhenXRequestedWithAbsent() throws Exception {
        mockMvc.perform(post("/api/auth/logout")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("CSRF_VALIDATION_FAILED"));
    }
}
