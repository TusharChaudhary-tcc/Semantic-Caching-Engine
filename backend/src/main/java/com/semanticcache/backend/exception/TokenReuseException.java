package com.semanticcache.backend.exception;

/**
 * Exception thrown when an already-revoked refresh token is presented for rotation,
 * indicating potential token theft, replay attack, or reuse.
 * Triggers family-wide token revocation.
 */
public class TokenReuseException extends RuntimeException {

    public TokenReuseException(String message) {
        super(message);
    }

    public TokenReuseException(String message, Throwable cause) {
        super(message, cause);
    }
}
