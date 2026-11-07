package com.semanticcache.backend.exception;

public class GeminiEmptyResponseException extends GeminiApiException {

    public GeminiEmptyResponseException(String message) {
        super(message);
    }
}
