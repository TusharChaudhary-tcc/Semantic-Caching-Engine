package com.semanticcache.backend.exception;

/**
 * Exception thrown when a presented refresh token is malformed, blank, expired,
 * or not found in persistence.
 */
public class InvalidRefreshTokenException extends RuntimeException {

    public InvalidRefreshTokenException(String message) {
        super(message);
    }

    public InvalidRefreshTokenException(String message, Throwable cause) {
        super(message, cause);
    }
}
