package com.semanticcache.backend.exception;

public class QdrantOperationException extends QdrantException {

    public QdrantOperationException(String message) {
        super(message);
    }

    public QdrantOperationException(String message, Throwable cause) {
        super(message, cause);
    }
}
