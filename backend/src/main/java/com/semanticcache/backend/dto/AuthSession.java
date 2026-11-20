package com.semanticcache.backend.dto;

import java.util.Objects;

/**
 * Internal session representation coordinating JWT access token issuance
 * and HttpOnly refresh-token cookie delivery.
 */
public record AuthSession(
        String accessToken,
        String rawRefreshToken,
        String userId,
        String username
) {

    public AuthSession {
        Objects.requireNonNull(accessToken, "accessToken must not be null");
        Objects.requireNonNull(rawRefreshToken, "rawRefreshToken must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(username, "username must not be null");
    }
}
