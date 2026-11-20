package com.semanticcache.backend.exception;

import com.semanticcache.backend.dto.ErrorResponse;
import com.semanticcache.backend.util.RefreshTokenCookieUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final long maxRequestBodyBytes;
    private final RefreshTokenCookieUtil cookieUtil;

    @Autowired
    public GlobalExceptionHandler(
            @Value("${cache.max-request-body-bytes:65536}") long maxRequestBodyBytes,
            @Autowired(required = false) RefreshTokenCookieUtil cookieUtil
    ) {
        this.maxRequestBodyBytes = maxRequestBodyBytes;
        this.cookieUtil = cookieUtil != null ? cookieUtil : new RefreshTokenCookieUtil();
    }

    public GlobalExceptionHandler(long maxRequestBodyBytes) {
        this(maxRequestBodyBytes, new RefreshTokenCookieUtil());
    }

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
     * Handles requests whose payload exceeds the configured maximum byte size.
     * Returns HTTP 413 Payload Too Large.
     */
    @ExceptionHandler(PayloadTooLargeException.class)
    public ResponseEntity<ErrorResponse> handlePayloadTooLargeException(
            PayloadTooLargeException ex,
            HttpServletRequest request
    ) {
        log.warn("Request payload exceeded size limit on {}: exception class [{}]",
                request.getRequestURI(), ex.getClass().getName());

        ErrorResponse response = ErrorResponse.of(
                HttpStatus.PAYLOAD_TOO_LARGE.value(),
                HttpStatus.PAYLOAD_TOO_LARGE.getReasonPhrase(),
                "Request body exceeds maximum allowed size of " + maxRequestBodyBytes + " bytes",
                request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(response);
    }

    /**
     * Catches unparseable / malformed JSON payloads, or payload size limits exceeded during deserialization.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleMessageNotReadableException(
            HttpMessageNotReadableException ex,
            HttpServletRequest request
    ) {
        if (isPayloadTooLarge(ex)) {
            log.warn("Request payload exceeded size limit on {}: exception class [{}]",
                    request.getRequestURI(), ex.getClass().getName());

            ErrorResponse response = ErrorResponse.of(
                    HttpStatus.PAYLOAD_TOO_LARGE.value(),
                    HttpStatus.PAYLOAD_TOO_LARGE.getReasonPhrase(),
                    "Request body exceeds maximum allowed size of " + maxRequestBodyBytes + " bytes",
                    request.getRequestURI()
            );

            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(response);
        }

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

    private boolean isPayloadTooLarge(Throwable ex) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = ex;
        while (current != null && seen.add(current)) {
            if (current instanceof PayloadTooLargeException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
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
     * Handles duplicate user registration attempts.
     * Returns HTTP 409 Conflict with a sanitized message.
     */
    @ExceptionHandler(UserAlreadyExistsException.class)
    public ResponseEntity<ErrorResponse> handleUserAlreadyExistsException(
            UserAlreadyExistsException ex,
            HttpServletRequest request
    ) {
        log.warn("Registration conflict on {}: {}", request.getRequestURI(), ex.getMessage());
        ErrorResponse response = ErrorResponse.of(
                HttpStatus.CONFLICT.value(),
                HttpStatus.CONFLICT.getReasonPhrase(),
                ex.getMessage(),
                request.getRequestURI()
        );
        return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
    }

    /**
     * Handles database constraint violations (e.g. concurrent registration race conditions).
     * Returns HTTP 409 Conflict if caused by a duplicate username constraint; otherwise returns
     * HTTP 500 without leaking database details.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrityViolationException(
            DataIntegrityViolationException ex,
            HttpServletRequest request
    ) {
        log.warn("Data integrity violation on {}: exception class [{}]", request.getRequestURI(), ex.getClass().getName());

        if (isUsernameConstraintViolation(ex)) {
            ErrorResponse response = ErrorResponse.of(
                    HttpStatus.CONFLICT.value(),
                    HttpStatus.CONFLICT.getReasonPhrase(),
                    "Username is already taken",
                    request.getRequestURI()
            );
            return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
        }

        ErrorResponse response = ErrorResponse.of(
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase(),
                "A database integrity error occurred. Please try again later.",
                request.getRequestURI()
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }

    private boolean isUsernameConstraintViolation(DataIntegrityViolationException ex) {
        Throwable current = ex;
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        boolean hasUniqueConstraintSqlState = false;
        String constraintName = null;

        while (current != null && seen.add(current)) {
            if (current instanceof org.hibernate.exception.ConstraintViolationException cve) {
                if (cve.getConstraintName() != null) {
                    constraintName = cve.getConstraintName();
                }
            }
            if (current instanceof java.sql.SQLException sqlEx) {
                String sqlState = sqlEx.getSQLState();
                if ("23505".equals(sqlState)) {
                    hasUniqueConstraintSqlState = true;
                }
            }
            current = current.getCause();
        }

        // If we extracted a constraint name, check if it targets the username unique constraint
        if (constraintName != null) {
            String lower = constraintName.toLowerCase();
            if (lower.contains("uk_users_username")
                    || lower.contains("users_username")
                    || lower.contains("users(username")
                    || lower.contains("users.username")) {
                return true;
            }
        }

        // Fallback for drivers/dialects where getConstraintName() is not extracted, supported by SQLState 23505
        if (hasUniqueConstraintSqlState) {
            current = ex;
            seen.clear();
            while (current != null && seen.add(current)) {
                String message = current.getMessage();
                if (message != null) {
                    String lower = message.toLowerCase();
                    if (lower.contains("users(username")
                            || lower.contains("users.username")
                            || lower.contains("uk_users_username")
                            || (lower.contains("unique") && lower.contains("username"))) {
                        return true;
                    }
                }
                current = current.getCause();
            }
        }

        return false;
    }

    /**
     * Handles bad login credentials (unknown username or mismatched password).
     * Returns HTTP 401 Unauthorized with a generic safe message.
     */
    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleInvalidCredentialsException(
            InvalidCredentialsException ex,
            HttpServletRequest request
    ) {
        log.warn("Authentication failure on {}: {}", request.getRequestURI(), ex.getMessage());
        ErrorResponse response = ErrorResponse.of(
                HttpStatus.UNAUTHORIZED.value(),
                HttpStatus.UNAUTHORIZED.getReasonPhrase(),
                "Invalid username or password",
                request.getRequestURI()
        );
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
    }

    /**
     * Handles login attempts on disabled user accounts.
     * Returns HTTP 401 Unauthorized.
     */
    @ExceptionHandler(AccountDisabledException.class)
    public ResponseEntity<ErrorResponse> handleAccountDisabledException(
            AccountDisabledException ex,
            HttpServletRequest request
    ) {
        log.warn("Authentication rejected for disabled account on {}: {}", request.getRequestURI(), ex.getMessage());
        ErrorResponse response = ErrorResponse.of(
                HttpStatus.UNAUTHORIZED.value(),
                HttpStatus.UNAUTHORIZED.getReasonPhrase(),
                "Account is disabled",
                request.getRequestURI()
        );
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
    }

    /**
     * Handles invalid or expired refresh tokens.
     * Clears the refresh token cookie and returns HTTP 401 Unauthorized.
     */
    @ExceptionHandler(InvalidRefreshTokenException.class)
    public ResponseEntity<ErrorResponse> handleInvalidRefreshTokenException(
            InvalidRefreshTokenException ex,
            HttpServletRequest request
    ) {
        log.warn("Invalid refresh token on {}: {}", request.getRequestURI(), ex.getMessage());
        ResponseCookie cleanCookie = cookieUtil.createCleanCookie();
        ErrorResponse response = ErrorResponse.of(
                HttpStatus.UNAUTHORIZED.value(),
                HttpStatus.UNAUTHORIZED.getReasonPhrase(),
                "Invalid or expired refresh token",
                request.getRequestURI()
        );
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.SET_COOKIE, cleanCookie.toString())
                .body(response);
    }

    /**
     * Handles refresh token reuse detection.
     * Clears the refresh token cookie and returns HTTP 401 Unauthorized.
     */
    @ExceptionHandler(TokenReuseException.class)
    public ResponseEntity<ErrorResponse> handleTokenReuseException(
            TokenReuseException ex,
            HttpServletRequest request
    ) {
        log.warn("Token reuse detected on {}: {}", request.getRequestURI(), ex.getMessage());
        ResponseCookie cleanCookie = cookieUtil.createCleanCookie();
        ErrorResponse response = ErrorResponse.of(
                HttpStatus.UNAUTHORIZED.value(),
                HttpStatus.UNAUTHORIZED.getReasonPhrase(),
                "Invalid or expired refresh token",
                request.getRequestURI()
        );
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.SET_COOKIE, cleanCookie.toString())
                .body(response);
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
