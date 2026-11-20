package com.semanticcache.backend.exception;

/**
 * Exception thrown when communication with the Qdrant vector database fails
 * due to genuine transport, network, or gRPC connectivity issues (e.g., connection refused,
 * host unreachable, or service unavailable).
 */
public class QdrantCommunicationException extends QdrantInitializationException {

    public QdrantCommunicationException(String message) {
        super(message);
    }

    public QdrantCommunicationException(String message, Throwable cause) {
        super(message, cause);
    }
}
