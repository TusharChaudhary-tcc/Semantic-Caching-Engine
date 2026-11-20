package com.semanticcache.backend.exception;

public class QdrantIncompatibleCollectionException extends QdrantInitializationException {

    public QdrantIncompatibleCollectionException(String message) {
        super(message);
    }

    public QdrantIncompatibleCollectionException(String message, Throwable cause) {
        super(message, cause);
    }
}
