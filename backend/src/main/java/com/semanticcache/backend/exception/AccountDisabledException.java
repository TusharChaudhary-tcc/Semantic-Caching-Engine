package com.semanticcache.backend.exception;

/**
 * Exception thrown when an authentication attempt succeeds on credentials
 * but the user account has been disabled.
 */
public class AccountDisabledException extends RuntimeException {

    public AccountDisabledException(String message) {
        super(message);
    }
}
