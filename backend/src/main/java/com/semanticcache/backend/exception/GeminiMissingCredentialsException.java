package com.semanticcache.backend.exception;

public class GeminiMissingCredentialsException extends RuntimeException {

    public GeminiMissingCredentialsException(String message) {
        super(message);
    }
}
