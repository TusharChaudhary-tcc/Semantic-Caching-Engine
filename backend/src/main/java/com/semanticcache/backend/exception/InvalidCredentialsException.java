package com.semanticcache.backend.exception;

/**
 * Exception thrown when an authentication attempt fails due to invalid credentials.
 * Used for both unknown usernames and invalid passwords to prevent user enumeration.
 */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException(String message) {
        super(message);
    }
}
