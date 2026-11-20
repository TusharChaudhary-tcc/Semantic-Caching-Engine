package com.semanticcache.backend.exception;

public class QdrantInitializationException extends QdrantException {

    public QdrantInitializationException(String message) {
        super(message);
    }

    public QdrantInitializationException(String message, Throwable cause) {
        super(message, cause);
    }
}
