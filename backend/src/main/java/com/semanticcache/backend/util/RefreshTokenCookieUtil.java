package com.semanticcache.backend.util;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Spring-managed utility for creating and invalidating HttpOnly refresh-token cookies.
 * <p>
 * Enforces strict cookie security attributes including path restriction to {@code /api/auth},
 * mandatory HttpOnly defense against XSS, environment-driven Secure/SameSite flags, and 7-day expiry.
 */
@Component
public class RefreshTokenCookieUtil {

    public static final String COOKIE_NAME = "refreshToken";
    public static final String COOKIE_PATH = "/api/auth";
    public static final Duration COOKIE_MAX_AGE = Duration.ofDays(7);

    private final boolean secure;
    private final String sameSite;

    @Autowired
    public RefreshTokenCookieUtil(
            @Value("${app.security.cookie.secure:false}") boolean secure,
            @Value("${app.security.cookie.same-site:Lax}") String sameSite
    ) {
        if (sameSite == null || sameSite.isBlank()) {
            throw new IllegalArgumentException("SameSite configuration must not be null or blank");
        }
        String normalizedSameSite = validateAndNormalizeSameSite(sameSite.trim());
        if ("None".equalsIgnoreCase(normalizedSameSite) && !secure) {
            throw new IllegalStateException("SameSite=None requires Secure cookie to be enabled");
        }
        this.secure = secure;
        this.sameSite = normalizedSameSite;
    }

    public RefreshTokenCookieUtil() {
        this(false, "Lax");
    }

    /**
     * Builds an HttpOnly {@link ResponseCookie} carrying the raw refresh token with a 7-day expiration.
     *
     * @param rawRefreshToken high-entropy raw refresh token string
     * @return populated {@link ResponseCookie} ready for the {@code Set-Cookie} header
     * @throws IllegalArgumentException if {@code rawRefreshToken} is null or blank
     */
    public ResponseCookie createCookie(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw new IllegalArgumentException("Raw refresh token must not be null or blank");
        }
        return ResponseCookie.from(COOKIE_NAME, rawRefreshToken)
                .httpOnly(true)
                .secure(secure)
                .path(COOKIE_PATH)
                .maxAge(COOKIE_MAX_AGE)
                .sameSite(sameSite)
                .build();
    }

    /**
     * Builds an eviction {@link ResponseCookie} with max-age 0 and identical security attributes
     * to immediately clear the stored refresh token from the client's browser.
     *
     * @return clearance {@link ResponseCookie} with max-age 0
     */
    public ResponseCookie createCleanCookie() {
        return ResponseCookie.from(COOKIE_NAME, "")
                .httpOnly(true)
                .secure(secure)
                .path(COOKIE_PATH)
                .maxAge(Duration.ZERO)
                .sameSite(sameSite)
                .build();
    }

    public boolean isSecure() {
        return secure;
    }

    public String getSameSite() {
        return sameSite;
    }

    public String getCookieName() {
        return COOKIE_NAME;
    }

    public String getCookiePath() {
        return COOKIE_PATH;
    }

    public Duration getMaxAge() {
        return COOKIE_MAX_AGE;
    }

    private static String validateAndNormalizeSameSite(String sameSite) {
        if ("Strict".equalsIgnoreCase(sameSite)) {
            return "Strict";
        } else if ("Lax".equalsIgnoreCase(sameSite)) {
            return "Lax";
        } else if ("None".equalsIgnoreCase(sameSite)) {
            return "None";
        } else {
            throw new IllegalArgumentException(
                    "Unsupported SameSite value: '" + sameSite + "'. Supported values are: Strict, Lax, None"
            );
        }
    }
}
