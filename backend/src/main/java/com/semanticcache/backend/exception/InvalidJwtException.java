package com.semanticcache.backend.exception;

/**
 * Exception thrown when a JSON Web Token (JWT) is invalid, expired, malformed,
 * tampered with, unsigned, or fails security and claim validation constraints.
 * <p>
 * To prevent credential and token leakage, exception messages must never reveal
 * raw tokens or signing secrets.
 */
public class InvalidJwtException extends RuntimeException {

    public InvalidJwtException(String message) {
        super(message);
    }

    public InvalidJwtException(String message, Throwable cause) {
        super(message, cause);
    }
}
