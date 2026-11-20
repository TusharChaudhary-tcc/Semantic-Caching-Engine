package com.semanticcache.backend.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseCookie;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RefreshTokenCookieUtilTest {

    private static final String SAMPLE_RAW_TOKEN = "dGVzdC1yYXctcmVmcmVzaC10b2tlbi12YWx1ZQ";

    @Test
    @DisplayName("Cookie name is refreshToken and path is /api/auth")
    void cookieNameAndPathAreCorrect() {
        RefreshTokenCookieUtil util = new RefreshTokenCookieUtil(false, "Lax");
        ResponseCookie cookie = util.createCookie(SAMPLE_RAW_TOKEN);

        assertEquals("refreshToken", cookie.getName());
        assertEquals("/api/auth", cookie.getPath());
    }

    @Test
    @DisplayName("HttpOnly is unconditionally true on both normal and clean cookies")
    void httpOnlyIsAlwaysTrue() {
        RefreshTokenCookieUtil util = new RefreshTokenCookieUtil(false, "Lax");

        ResponseCookie normalCookie = util.createCookie(SAMPLE_RAW_TOKEN);
        ResponseCookie cleanCookie = util.createCleanCookie();

        assertTrue(normalCookie.isHttpOnly(), "Normal cookie must be HttpOnly");
        assertTrue(cleanCookie.isHttpOnly(), "Clean cookie must be HttpOnly");
    }

    @Test
    @DisplayName("Secure flag reflects configuration")
    void secureFlagReflectsConfiguration() {
        RefreshTokenCookieUtil insecureUtil = new RefreshTokenCookieUtil(false, "Lax");
        RefreshTokenCookieUtil secureUtil = new RefreshTokenCookieUtil(true, "Strict");

        assertFalse(insecureUtil.createCookie(SAMPLE_RAW_TOKEN).isSecure());
        assertFalse(insecureUtil.createCleanCookie().isSecure());

        assertTrue(secureUtil.createCookie(SAMPLE_RAW_TOKEN).isSecure());
        assertTrue(secureUtil.createCleanCookie().isSecure());
    }

    @Test
    @DisplayName("SameSite reflects configuration for Strict, Lax, and None")
    void sameSiteReflectsConfiguration() {
        RefreshTokenCookieUtil strictUtil = new RefreshTokenCookieUtil(true, "Strict");
        RefreshTokenCookieUtil laxUtil = new RefreshTokenCookieUtil(false, "Lax");
        RefreshTokenCookieUtil noneUtil = new RefreshTokenCookieUtil(true, "None");

        assertEquals("Strict", strictUtil.createCookie(SAMPLE_RAW_TOKEN).getSameSite());
        assertEquals("Lax", laxUtil.createCookie(SAMPLE_RAW_TOKEN).getSameSite());
        assertEquals("None", noneUtil.createCookie(SAMPLE_RAW_TOKEN).getSameSite());
    }

    @Test
    @DisplayName("Normal cookie has seven-day (604800s) expiry")
    void normalCookieHasSevenDayExpiry() {
        RefreshTokenCookieUtil util = new RefreshTokenCookieUtil(false, "Lax");
        ResponseCookie cookie = util.createCookie(SAMPLE_RAW_TOKEN);

        assertEquals(Duration.ofDays(7), cookie.getMaxAge());
        assertEquals(604800L, cookie.getMaxAge().getSeconds());
        assertEquals(SAMPLE_RAW_TOKEN, cookie.getValue());
    }

    @Test
    @DisplayName("Clearing cookie has zero expiry and empty value")
    void clearingCookieHasZeroExpiryAndEmptyValue() {
        RefreshTokenCookieUtil util = new RefreshTokenCookieUtil(false, "Lax");
        ResponseCookie cleanCookie = util.createCleanCookie();

        assertEquals(Duration.ZERO, cleanCookie.getMaxAge());
        assertEquals(0L, cleanCookie.getMaxAge().getSeconds());
        assertEquals("", cleanCookie.getValue());
    }

    @Test
    @DisplayName("Clearing cookie preserves the original cookie security attributes")
    void clearingCookiePreservesOriginalAttributes() {
        RefreshTokenCookieUtil util = new RefreshTokenCookieUtil(true, "Strict");

        ResponseCookie normal = util.createCookie(SAMPLE_RAW_TOKEN);
        ResponseCookie clean = util.createCleanCookie();

        assertEquals(normal.getName(), clean.getName());
        assertEquals(normal.getPath(), clean.getPath());
        assertEquals(normal.isHttpOnly(), clean.isHttpOnly());
        assertEquals(normal.isSecure(), clean.isSecure());
        assertEquals(normal.getSameSite(), clean.getSameSite());
    }

    @Test
    @DisplayName("Null or blank raw refresh token is rejected with IllegalArgumentException")
    void nullOrBlankTokenIsRejected() {
        RefreshTokenCookieUtil util = new RefreshTokenCookieUtil(false, "Lax");

        assertThrows(IllegalArgumentException.class, () -> util.createCookie(null));
        assertThrows(IllegalArgumentException.class, () -> util.createCookie(""));
        assertThrows(IllegalArgumentException.class, () -> util.createCookie("   "));
    }

    @Test
    @DisplayName("Invalid SameSite configuration is rejected with IllegalArgumentException")
    void invalidSameSiteConfigurationIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new RefreshTokenCookieUtil(true, "Invalid"));
        assertThrows(IllegalArgumentException.class, () -> new RefreshTokenCookieUtil(true, ""));
        assertThrows(IllegalArgumentException.class, () -> new RefreshTokenCookieUtil(true, null));
    }

    @Test
    @DisplayName("SameSite=None without Secure enabled is rejected with IllegalStateException")
    void sameSiteNoneWithoutSecureIsRejected() {
        assertThrows(IllegalStateException.class, () -> new RefreshTokenCookieUtil(false, "None"));
    }
}
