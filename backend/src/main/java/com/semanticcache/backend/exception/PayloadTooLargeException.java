package com.semanticcache.backend.exception;

import java.io.IOException;

/**
 * Thrown when an incoming HTTP request body exceeds the maximum permitted byte size.
 * Extends {@link IOException} so that it can be thrown directly during input stream reads.
 */
public class PayloadTooLargeException extends IOException {

    public PayloadTooLargeException(String message) {
        super(message);
    }

    public PayloadTooLargeException(String message, Throwable cause) {
        super(message, cause);
    }
}
