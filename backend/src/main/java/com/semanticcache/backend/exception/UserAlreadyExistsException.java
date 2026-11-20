package com.semanticcache.backend.exception;

/**
 * Exception thrown when a user registration attempt specifies a username that already exists.
 */
public class UserAlreadyExistsException extends RuntimeException {

    public UserAlreadyExistsException(String message) {
        super(message);
    }
}
