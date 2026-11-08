package com.semanticcache.backend.exception;

import com.semanticcache.backend.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Catches Bean Validation errors (@Valid on request bodies).
     * Maps each invalid field to its declared message.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(
            MethodArgumentNotValidException ex,
            HttpServletRequest request
    ) {
        Map<String, String> validationErrors = new LinkedHashMap<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            validationErrors.put(fieldError.getField(), fieldError.getDefaultMessage());
        }

        ErrorResponse response = ErrorResponse.withValidationErrors(
                HttpStatus.BAD_REQUEST.value(),
                HttpStatus.BAD_REQUEST.getReasonPhrase(),
                "Validation failed for one or more fields",
                request.getRequestURI(),
                validationErrors
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    /**
     * Catches unparseable / malformed JSON payloads.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleMessageNotReadableException(
            HttpMessageNotReadableException ex,
            HttpServletRequest request
    ) {
        // log.warn("Malformed JSON request received on {}: {}", request.getRequestURI(), ex.getMessage());
        log.warn(
                "Malformed JSON request received on {}: exception class [{}]",
                request.getRequestURI(),
                ex.getClass().getName()
        );

        ErrorResponse response = ErrorResponse.of(
                HttpStatus.BAD_REQUEST.value(),
                HttpStatus.BAD_REQUEST.getReasonPhrase(),
                "Malformed JSON request body",
                request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    /**
     * Handles missing Gemini API credentials (e.g. GOOGLE_API_KEY environment variable not set).
     * Returns HTTP 503 with a safe client-facing message without exposing internal paths or configs.
     */
    @ExceptionHandler(GeminiMissingCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleMissingCredentialsException(
            GeminiMissingCredentialsException ex,
            HttpServletRequest request
    ) {
        log.error("Missing Gemini API credentials on {}: exception class [{}]", request.getRequestURI(), ex.getClass().getName());

        ErrorResponse response = ErrorResponse.of(
                HttpStatus.SERVICE_UNAVAILABLE.value(),
                HttpStatus.SERVICE_UNAVAILABLE.getReasonPhrase(),
                "The AI service is temporarily unavailable due to missing credentials. Please configure the server API key.",
                request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
    }

    /**
     * Handles empty responses from the Gemini API.
     * Returns HTTP 502 with a safe, intentional client-facing message.
     */
    @ExceptionHandler(GeminiEmptyResponseException.class)
    public ResponseEntity<ErrorResponse> handleEmptyResponseException(
            GeminiEmptyResponseException ex,
            HttpServletRequest request
    ) {
        log.warn("Empty response received from Gemini API on {}: exception class [{}]", request.getRequestURI(), ex.getClass().getName());

        ErrorResponse response = ErrorResponse.of(
                HttpStatus.BAD_GATEWAY.value(),
                HttpStatus.BAD_GATEWAY.getReasonPhrase(),
                "The AI service returned an empty response. Please try again later.",
                request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(response);
    }

    /**
     * Handles Gemini API failures and upstream error responses.
     * Sanitizes errors: returns a generic client message; keeps technical details strictly in server logs without raw payloads.
     */
    @ExceptionHandler(GeminiApiException.class)
    public ResponseEntity<ErrorResponse> handleGeminiApiException(
            GeminiApiException ex,
            HttpServletRequest request
    ) {
        log.error("Gemini API error processing request on {}: exception class [{}]", request.getRequestURI(), ex.getClass().getName());

        ErrorResponse response = ErrorResponse.of(
                HttpStatus.BAD_GATEWAY.value(),
                HttpStatus.BAD_GATEWAY.getReasonPhrase(),
                "The AI service failed to generate a response. Please try again later.",
                request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(response);
    }

    /**
     * Handles empty or invalid response vectors from the embedding model.
     * Returns HTTP 502 with a safe, intentional client-facing message.
     */
    @ExceptionHandler(EmbeddingEmptyResponseException.class)
    public ResponseEntity<ErrorResponse> handleEmbeddingEmptyResponseException(
            EmbeddingEmptyResponseException ex,
            HttpServletRequest request
    ) {
        log.warn("Empty or invalid embedding response on {}: exception class [{}]", request.getRequestURI(), ex.getClass().getName());

        ErrorResponse response = ErrorResponse.of(
                HttpStatus.BAD_GATEWAY.value(),
                HttpStatus.BAD_GATEWAY.getReasonPhrase(),
                "The AI embedding service returned an empty or invalid response. Please try again later.",
                request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(response);
    }

    /**
     * Handles embedding generation failures and upstream errors.
     * Sanitizes errors: returns a generic client message; keeps details strictly in server logs.
     */
    @ExceptionHandler(EmbeddingException.class)
    public ResponseEntity<ErrorResponse> handleEmbeddingException(
            EmbeddingException ex,
            HttpServletRequest request
    ) {
        log.error("Embedding generation error on {}: exception class [{}]", request.getRequestURI(), ex.getClass().getName());

        ErrorResponse response = ErrorResponse.of(
                HttpStatus.BAD_GATEWAY.value(),
                HttpStatus.BAD_GATEWAY.getReasonPhrase(),
                "Failed to generate text embedding from the AI service. Please try again later.",
                request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(response);
    }

    /**
     * Handles Qdrant initialization failures (e.g. collection creation or connection unavailable on startup).
     * Returns HTTP 503 Service Unavailable with a safe client-facing message.
     */
    @ExceptionHandler(QdrantInitializationException.class)
    public ResponseEntity<ErrorResponse> handleQdrantInitializationException(
            QdrantInitializationException ex,
            HttpServletRequest request
    ) {
        log.error("Qdrant initialization error on {}: exception class [{}]", request.getRequestURI(), ex.getClass().getName());

        ErrorResponse response = ErrorResponse.of(
                HttpStatus.SERVICE_UNAVAILABLE.value(),
                HttpStatus.SERVICE_UNAVAILABLE.getReasonPhrase(),
                "Vector database service is temporarily unavailable. Please try again later.",
                request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
    }

    /**
     * Handles Qdrant vector database runtime operation failures (upsert, search, retrieve).
     * Returns HTTP 502 Bad Gateway with a safe client-facing message.
     */
    @ExceptionHandler(QdrantOperationException.class)
    public ResponseEntity<ErrorResponse> handleQdrantOperationException(
            QdrantOperationException ex,
            HttpServletRequest request
    ) {
        log.error("Qdrant operation error on {}: exception class [{}]", request.getRequestURI(), ex.getClass().getName());

        ErrorResponse response = ErrorResponse.of(
                HttpStatus.BAD_GATEWAY.value(),
                HttpStatus.BAD_GATEWAY.getReasonPhrase(),
                "Failed to process vector database operation. Please try again later.",
                request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(response);
    }

    /**
     * Fallback handler for general Qdrant exceptions.
     * Returns HTTP 502 Bad Gateway with a safe client-facing message.
     */
    @ExceptionHandler(QdrantException.class)
    public ResponseEntity<ErrorResponse> handleQdrantException(
            QdrantException ex,
            HttpServletRequest request
    ) {
        log.error("Qdrant generic error on {}: exception class [{}]", request.getRequestURI(), ex.getClass().getName());

        ErrorResponse response = ErrorResponse.of(
                HttpStatus.BAD_GATEWAY.value(),
                HttpStatus.BAD_GATEWAY.getReasonPhrase(),
                "Vector database encountered an error. Please try again later.",
                request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(response);
    }

    /**
     * Catch-all fallback handler for unexpected runtime exceptions.
     * Prevents internal implementation details or stack traces from leaking to clients.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(
            Exception ex,
            HttpServletRequest request
    ) {
        // log.error("Unhandled exception processing request on {}: ", request.getRequestURI(), ex);

        log.error(
                "Unhandled exception processing request on {}: exception class [{}]",
                request.getRequestURI(),
                ex.getClass().getName()
        );

        ErrorResponse response = ErrorResponse.of(
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase(),
                "An unexpected internal error occurred. Please try again later.",
                request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }
}
