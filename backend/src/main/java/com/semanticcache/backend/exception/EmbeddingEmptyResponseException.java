package com.semanticcache.backend.exception;

public class EmbeddingEmptyResponseException extends EmbeddingException {

    public EmbeddingEmptyResponseException(String message) {
        super(message);
    }

    public EmbeddingEmptyResponseException(String message, Throwable cause) {
        super(message, cause);
    }
}
