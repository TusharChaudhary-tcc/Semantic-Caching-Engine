package com.semanticcache.backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.semanticcache.backend.dto.AuthSession;
import com.semanticcache.backend.dto.LoginRequest;
import com.semanticcache.backend.dto.RegisterRequest;
import com.semanticcache.backend.exception.AccountDisabledException;
import com.semanticcache.backend.exception.GlobalExceptionHandler;
import com.semanticcache.backend.exception.InvalidCredentialsException;
import com.semanticcache.backend.exception.InvalidRefreshTokenException;
import com.semanticcache.backend.exception.TokenReuseException;
import com.semanticcache.backend.exception.UserAlreadyExistsException;
import com.semanticcache.backend.service.AuthService;
import com.semanticcache.backend.util.RefreshTokenCookieUtil;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthControllerTest {

    private MockMvc mockMvc;
    private AuthService authService;
    private RefreshTokenCookieUtil cookieUtil;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        authService = mock(AuthService.class);
        cookieUtil = new RefreshTokenCookieUtil(false, "Lax");
        AuthController authController = new AuthController(authService, cookieUtil);
        GlobalExceptionHandler exceptionHandler = new GlobalExceptionHandler(65536L, cookieUtil);

        mockMvc = MockMvcBuilders.standaloneSetup(authController)
                .setControllerAdvice(exceptionHandler)
                .build();
        objectMapper = new ObjectMapper();
    }

    @Nested
    @DisplayName("POST /api/auth/register")
    class RegisterEndpointTests {

        @Test
        @DisplayName("Returns 201 Created with AuthResponse and Set-Cookie on successful registration")
        void registerSuccess() throws Exception {
            String userId = UUID.randomUUID().toString();
            AuthSession session = new AuthSession("access.token.jwt", "raw-refresh-token", userId, "valid_user");
            when(authService.register(any(RegisterRequest.class))).thenReturn(session);

            RegisterRequest request = new RegisterRequest("valid_user", "ValidPassword123!");

            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(header().exists(HttpHeaders.SET_COOKIE))
                    .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("refreshToken=raw-refresh-token")))
                    .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("HttpOnly")))
                    .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Path=/api/auth")))
                    .andExpect(jsonPath("$.accessToken").value("access.token.jwt"))
                    .andExpect(jsonPath("$.tokenType").value("Bearer"))
                    .andExpect(jsonPath("$.expiresIn").value(900))
                    .andExpect(jsonPath("$.userId").value(userId))
                    .andExpect(jsonPath("$.username").value("valid_user"))
                    .andExpect(jsonPath("$.refreshToken").doesNotExist());
        }

        @Test
        @DisplayName("Returns 409 Conflict when username already exists")
        void registerUsernameTaken() throws Exception {
            when(authService.register(any(RegisterRequest.class)))
                    .thenThrow(new UserAlreadyExistsException("Username is already taken"));

            RegisterRequest request = new RegisterRequest("duplicate_user", "ValidPassword123!");

            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.error").value("Conflict"))
                    .andExpect(jsonPath("$.message").value("Username is already taken"));
        }

        @Test
        @DisplayName("Returns 409 Conflict when Hibernate ConstraintViolationException identifies username constraint")
        void registerHibernateConstraintViolationMatchesUsername() throws Exception {
            java.sql.SQLException sqlEx = new java.sql.SQLException("duplicate key", "23505");
            org.hibernate.exception.ConstraintViolationException cve =
                    new org.hibernate.exception.ConstraintViolationException("Duplicate username", sqlEx, "uk_users_username");
            DataIntegrityViolationException dive = new DataIntegrityViolationException("Conflict", cve);

            when(authService.register(any(RegisterRequest.class))).thenThrow(dive);

            RegisterRequest request = new RegisterRequest("racing_user", "ValidPassword123!");

            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.message").value("Username is already taken"));
        }

        @Test
        @DisplayName("Returns 500 Internal Server Error when Hibernate ConstraintViolationException identifies unrelated constraint")
        void registerHibernateConstraintViolationUnrelatedConstraint() throws Exception {
            java.sql.SQLException sqlEx = new java.sql.SQLException("duplicate key", "23505");
            org.hibernate.exception.ConstraintViolationException cve =
                    new org.hibernate.exception.ConstraintViolationException("Duplicate token", sqlEx, "refresh_tokens_token_hash_key");
            DataIntegrityViolationException dive = new DataIntegrityViolationException("Conflict", cve);

            when(authService.register(any(RegisterRequest.class))).thenThrow(dive);

            RegisterRequest request = new RegisterRequest("user", "ValidPassword123!");

            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.status").value(500))
                    .andExpect(jsonPath("$.message").value("A database integrity error occurred. Please try again later."));
        }

        @Test
        @DisplayName("Returns 409 Conflict on H2 SQLState 23505 unique constraint violation for username")
        void registerDataIntegrityViolationH2Fallback() throws Exception {
            java.sql.SQLException sqlEx = new java.sql.SQLException(
                    "Unique index or primary key violation: \"PUBLIC.CONSTRAINT_INDEX_4 ON PUBLIC.USERS(USERNAME NULLS FIRST)\"",
                    "23505"
            );
            DataIntegrityViolationException dive = new DataIntegrityViolationException("Conflict", sqlEx);

            when(authService.register(any(RegisterRequest.class))).thenThrow(dive);

            RegisterRequest request = new RegisterRequest("racing_user", "ValidPassword123!");

            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.message").value("Username is already taken"));
        }

        @Test
        @DisplayName("Returns 500 Internal Server Error on unrelated DataIntegrityViolationException (foreign key, check, or null constraint)")
        void registerUnrelatedDataIntegrityViolation() throws Exception {
            when(authService.register(any(RegisterRequest.class)))
                    .thenThrow(new DataIntegrityViolationException("Foreign key violation: FK_REFRESH_TOKENS_USER"));

            RegisterRequest request = new RegisterRequest("user", "ValidPassword123!");

            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.status").value(500))
                    .andExpect(jsonPath("$.message").value("A database integrity error occurred. Please try again later."));
        }

        @Test
        @DisplayName("Returns 400 Bad Request on validation failure")
        void registerValidationFailure() throws Exception {
            RegisterRequest request = new RegisterRequest("usr", "short");

            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));
        }
    }

    @Nested
    @DisplayName("POST /api/auth/login")
    class LoginEndpointTests {

        @Test
        @DisplayName("Returns 200 OK with AuthResponse and Set-Cookie on successful login")
        void loginSuccess() throws Exception {
            String userId = UUID.randomUUID().toString();
            AuthSession session = new AuthSession("access.token.jwt", "raw-refresh-token", userId, "valid_user");
            when(authService.login(any(LoginRequest.class))).thenReturn(session);

            LoginRequest request = new LoginRequest("valid_user", "ValidPassword123!");

            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(header().exists(HttpHeaders.SET_COOKIE))
                    .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("refreshToken=raw-refresh-token")))
                    .andExpect(jsonPath("$.accessToken").value("access.token.jwt"))
                    .andExpect(jsonPath("$.tokenType").value("Bearer"))
                    .andExpect(jsonPath("$.expiresIn").value(900))
                    .andExpect(jsonPath("$.userId").value(userId))
                    .andExpect(jsonPath("$.username").value("valid_user"))
                    .andExpect(jsonPath("$.refreshToken").doesNotExist());
        }

        @Test
        @DisplayName("Returns 401 Unauthorized on invalid credentials")
        void loginInvalidCredentials() throws Exception {
            when(authService.login(any(LoginRequest.class)))
                    .thenThrow(new InvalidCredentialsException("Invalid username or password"));

            LoginRequest request = new LoginRequest("valid_user", "WrongPassword123!");

            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.status").value(401))
                    .andExpect(jsonPath("$.message").value("Invalid username or password"));
        }

        @Test
        @DisplayName("Returns 401 Unauthorized when account is disabled")
        void loginAccountDisabled() throws Exception {
            when(authService.login(any(LoginRequest.class)))
                    .thenThrow(new AccountDisabledException("Account is disabled"));

            LoginRequest request = new LoginRequest("disabled_user", "ValidPassword123!");

            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.status").value(401))
                    .andExpect(jsonPath("$.message").value("Account is disabled"));
        }
    }

    @Nested
    @DisplayName("POST /api/auth/refresh")
    class RefreshEndpointTests {

        @Test
        @DisplayName("Returns 200 OK with AuthResponse and new Set-Cookie on successful rotation")
        void refreshSuccess() throws Exception {
            String userId = UUID.randomUUID().toString();
            AuthSession session = new AuthSession("new.jwt.token", "new-raw-refresh-token", userId, "valid_user");
            when(authService.rotateSession("presented-raw-token")).thenReturn(session);

            mockMvc.perform(post("/api/auth/refresh")
                            .cookie(new Cookie("refreshToken", "presented-raw-token")))
                    .andExpect(status().isOk())
                    .andExpect(header().exists(HttpHeaders.SET_COOKIE))
                    .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("refreshToken=new-raw-refresh-token")))
                    .andExpect(jsonPath("$.accessToken").value("new.jwt.token"))
                    .andExpect(jsonPath("$.userId").value(userId));
        }

        @Test
        @DisplayName("Returns 401 Unauthorized and clears cookie when refreshToken cookie is missing")
        void refreshMissingCookie() throws Exception {
            mockMvc.perform(post("/api/auth/refresh"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().exists(HttpHeaders.SET_COOKIE))
                    .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")))
                    .andExpect(jsonPath("$.status").value(401))
                    .andExpect(jsonPath("$.message").value("Invalid or expired refresh token"));
        }

        @Test
        @DisplayName("Returns 401 Unauthorized and clears cookie on invalid or expired token")
        void refreshInvalidToken() throws Exception {
            when(authService.rotateSession("invalid-token"))
                    .thenThrow(new InvalidRefreshTokenException("Refresh token is expired"));

            mockMvc.perform(post("/api/auth/refresh")
                            .cookie(new Cookie("refreshToken", "invalid-token")))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().exists(HttpHeaders.SET_COOKIE))
                    .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")))
                    .andExpect(jsonPath("$.status").value(401))
                    .andExpect(jsonPath("$.message").value("Invalid or expired refresh token"));
        }

        @Test
        @DisplayName("Returns 401 Unauthorized and clears cookie on token reuse detection")
        void refreshTokenReuseDetected() throws Exception {
            when(authService.rotateSession("reused-token"))
                    .thenThrow(new TokenReuseException("Token reuse detected"));

            mockMvc.perform(post("/api/auth/refresh")
                            .cookie(new Cookie("refreshToken", "reused-token")))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().exists(HttpHeaders.SET_COOKIE))
                    .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")))
                    .andExpect(jsonPath("$.status").value(401))
                    .andExpect(jsonPath("$.message").value("Invalid or expired refresh token"));
        }
    }

    @Nested
    @DisplayName("POST /api/auth/logout")
    class LogoutEndpointTests {

        @Test
        @DisplayName("Returns 204 No Content and clears cookie when refreshToken is provided")
        void logoutWithCookie() throws Exception {
            mockMvc.perform(post("/api/auth/logout")
                            .cookie(new Cookie("refreshToken", "presented-raw-token")))
                    .andExpect(status().isNoContent())
                    .andExpect(header().exists(HttpHeaders.SET_COOKIE))
                    .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")));

            verify(authService).logout("presented-raw-token");
        }

        @Test
        @DisplayName("Returns 204 No Content and clears cookie when no cookie is provided (idempotent)")
        void logoutWithoutCookie() throws Exception {
            mockMvc.perform(post("/api/auth/logout"))
                    .andExpect(status().isNoContent())
                    .andExpect(header().exists(HttpHeaders.SET_COOKIE))
                    .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")));

            verify(authService).logout(null);
        }
    }
}
