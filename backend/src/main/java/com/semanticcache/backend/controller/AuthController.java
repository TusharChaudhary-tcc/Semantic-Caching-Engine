package com.semanticcache.backend.controller;

import com.semanticcache.backend.dto.AuthResponse;
import com.semanticcache.backend.dto.AuthSession;
import com.semanticcache.backend.dto.LoginRequest;
import com.semanticcache.backend.dto.RegisterRequest;
import com.semanticcache.backend.exception.InvalidRefreshTokenException;
import com.semanticcache.backend.service.AuthService;
import com.semanticcache.backend.util.RefreshTokenCookieUtil;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * Controller exposing public authentication endpoints under {@code /api/auth}.
 * <p>
 * Handles user registration, credential login, refresh token rotation, and logout.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final RefreshTokenCookieUtil cookieUtil;

    public AuthController(AuthService authService, RefreshTokenCookieUtil cookieUtil) {
        this.authService = Objects.requireNonNull(authService, "AuthService must not be null");
        this.cookieUtil = Objects.requireNonNull(cookieUtil, "RefreshTokenCookieUtil must not be null");
    }

    /**
     * Registers a new user account, creates an initial refresh token cookie, and returns access tokens.
     *
     * @param request validated registration payload
     * @return HTTP 201 Created with {@link AuthResponse} and {@code Set-Cookie} header
     */
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        AuthSession session = authService.register(request);
        ResponseCookie cookie = cookieUtil.createCookie(session.rawRefreshToken());
        AuthResponse response = AuthResponse.of(session.accessToken(), 900L, session.userId(), session.username());

        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(response);
    }

    /**
     * Authenticates an existing user account with username and password.
     *
     * @param request validated login payload
     * @return HTTP 200 OK with {@link AuthResponse} and {@code Set-Cookie} header
     */
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        AuthSession session = authService.login(request);
        ResponseCookie cookie = cookieUtil.createCookie(session.rawRefreshToken());
        AuthResponse response = AuthResponse.of(session.accessToken(), 900L, session.userId(), session.username());

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(response);
    }

    /**
     * Rotates an active refresh token provided via the {@code refreshToken} cookie and issues a fresh session.
     *
     * @param request HTTP servlet request carrying cookies
     * @return HTTP 200 OK with {@link AuthResponse} and {@code Set-Cookie} header
     */
    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(HttpServletRequest request) {
        String rawRefreshToken = extractRefreshTokenCookie(request);
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw new InvalidRefreshTokenException("Refresh token is required");
        }

        AuthSession session = authService.rotateSession(rawRefreshToken);
        ResponseCookie cookie = cookieUtil.createCookie(session.rawRefreshToken());
        AuthResponse response = AuthResponse.of(session.accessToken(), 900L, session.userId(), session.username());

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(response);
    }

    /**
     * Revokes the current refresh token session and clears the cookie.
     * Idempotent: returns HTTP 204 No Content even if cookie is absent or already revoked.
     *
     * @param request HTTP servlet request carrying cookies
     * @return HTTP 204 No Content with cleared {@code Set-Cookie} header
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        String rawRefreshToken = extractRefreshTokenCookie(request);
        authService.logout(rawRefreshToken);
        ResponseCookie cleanCookie = cookieUtil.createCleanCookie();

        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cleanCookie.toString())
                .build();
    }

    private String extractRefreshTokenCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (RefreshTokenCookieUtil.COOKIE_NAME.equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }
        return null;
    }
}
