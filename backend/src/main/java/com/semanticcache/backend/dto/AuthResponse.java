package com.semanticcache.backend.dto;

import java.util.Objects;

/**
 * Authentication response returned upon successful user registration, login, or token refresh.
 * <p>
 * Contains the short-lived JWT access token and user metadata.
 * Refresh tokens are strictly excluded from JSON serialization and are delivered
 * exclusively through HttpOnly, Secure cookies.
 */
public record AuthResponse(
        String accessToken,
        String tokenType,
        long expiresIn,
        String userId,
        String username
) {

    public static final String DEFAULT_TOKEN_TYPE = "Bearer";

    public AuthResponse {
        Objects.requireNonNull(accessToken, "accessToken must not be null");
        Objects.requireNonNull(tokenType, "tokenType must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(username, "username must not be null");
    }

    /**
     * Convenience constructor defaulting {@code tokenType} to {@code "Bearer"}.
     *
     * @param accessToken signed JWT access token
     * @param expiresIn   access token lifetime in seconds
     * @param userId      canonical UUID string identifier of the authenticated user
     * @param username    user account username
     */
    public AuthResponse(String accessToken, long expiresIn, String userId, String username) {
        this(accessToken, DEFAULT_TOKEN_TYPE, expiresIn, userId, username);
    }

    public static AuthResponse of(String accessToken, long expiresIn, String userId, String username) {
        return new AuthResponse(accessToken, DEFAULT_TOKEN_TYPE, expiresIn, userId, username);
    }
}
