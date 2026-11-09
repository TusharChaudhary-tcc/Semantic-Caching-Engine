package com.semanticcache.backend.exception;

import com.semanticcache.backend.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler exceptionHandler;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        exceptionHandler = new GlobalExceptionHandler(65536L);
        request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/test");
    }

    @Test
    @DisplayName("Handles EmbeddingEmptyResponseException returning 502 Bad Gateway and sanitized message")
    void handlesEmbeddingEmptyResponseException() {
        EmbeddingEmptyResponseException ex = new EmbeddingEmptyResponseException("Empty vector from provider");

        ResponseEntity<ErrorResponse> response = exceptionHandler.handleEmbeddingEmptyResponseException(ex, request);

        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(502, response.getBody().status());
        assertEquals("Bad Gateway", response.getBody().error());
        assertEquals("The AI embedding service returned an empty or invalid response. Please try again later.", response.getBody().message());
        assertEquals("/api/test", response.getBody().path());
    }

    @Test
    @DisplayName("Handles EmbeddingException returning 502 Bad Gateway and sanitized message")
    void handlesEmbeddingException() {
        EmbeddingException ex = new EmbeddingException("Dimension mismatch internal details");

        ResponseEntity<ErrorResponse> response = exceptionHandler.handleEmbeddingException(ex, request);

        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(502, response.getBody().status());
        assertEquals("Bad Gateway", response.getBody().error());
        assertEquals("Failed to generate text embedding from the AI service. Please try again later.", response.getBody().message());
        assertEquals("/api/test", response.getBody().path());
    }

    @Test
    @DisplayName("Handles QdrantInitializationException returning 503 Service Unavailable and sanitized message")
    void handlesQdrantInitializationException() {
        QdrantInitializationException ex = new QdrantInitializationException("Collection init failed: gRPC connection refused to localhost:6334");

        ResponseEntity<ErrorResponse> response = exceptionHandler.handleQdrantInitializationException(ex, request);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(503, response.getBody().status());
        assertEquals("Service Unavailable", response.getBody().error());
        assertEquals("Vector database service is temporarily unavailable. Please try again later.", response.getBody().message());
        assertEquals("/api/test", response.getBody().path());
    }

    @Test
    @DisplayName("Handles QdrantOperationException returning 502 Bad Gateway and sanitized message")
    void handlesQdrantOperationException() {
        QdrantOperationException ex = new QdrantOperationException("Point upsert timeout on collection semantic_cache");

        ResponseEntity<ErrorResponse> response = exceptionHandler.handleQdrantOperationException(ex, request);

        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(502, response.getBody().status());
        assertEquals("Bad Gateway", response.getBody().error());
        assertEquals("Failed to process vector database operation. Please try again later.", response.getBody().message());
        assertEquals("/api/test", response.getBody().path());
    }

    @Test
    @DisplayName("Handles generic QdrantException returning 502 Bad Gateway and sanitized message")
    void handlesGenericQdrantException() {
        QdrantException ex = new QdrantException("Unexpected vector DB error with internal details");

        ResponseEntity<ErrorResponse> response = exceptionHandler.handleQdrantException(ex, request);

        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(502, response.getBody().status());
        assertEquals("Bad Gateway", response.getBody().error());
        assertEquals("Vector database encountered an error. Please try again later.", response.getBody().message());
        assertEquals("/api/test", response.getBody().path());
    }

    @Test
    @DisplayName("Handles PayloadTooLargeException returning 413 and message with standard limit")
    void handlesPayloadTooLargeExceptionDefaultLimit() {
        PayloadTooLargeException ex = new PayloadTooLargeException("Raw internal message that must be sanitized");

        ResponseEntity<ErrorResponse> response = exceptionHandler.handlePayloadTooLargeException(ex, request);

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(413, response.getBody().status());
        assertEquals("Payload Too Large", response.getBody().error());
        assertEquals("Request body exceeds maximum allowed size of 65536 bytes", response.getBody().message());
        assertEquals("/api/test", response.getBody().path());
    }

    @Test
    @DisplayName("PayloadTooLargeException reflects non-default configured limit in HTTP 413 response")
    void payloadTooLargeReflectsNonDefaultConfiguredLimit() {
        GlobalExceptionHandler customHandler = new GlobalExceptionHandler(32768L);
        PayloadTooLargeException ex = new PayloadTooLargeException("Raw internal message");

        ResponseEntity<ErrorResponse> response = customHandler.handlePayloadTooLargeException(ex, request);

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(413, response.getBody().status());
        assertEquals("Payload Too Large", response.getBody().error());
        assertEquals("Request body exceeds maximum allowed size of 32768 bytes", response.getBody().message());
        assertEquals("/api/test", response.getBody().path());
    }

    @Test
    @DisplayName("HttpMessageNotReadableException wrapping PayloadTooLargeException reflects non-default configured limit")
    void messageNotReadableWrappingPayloadTooLargeReflectsNonDefaultLimit() {
        GlobalExceptionHandler customHandler = new GlobalExceptionHandler(16384L);
        HttpMessageNotReadableException ex = new HttpMessageNotReadableException(
                "I/O error during JSON deserialization",
                new PayloadTooLargeException("Stream limit exceeded"),
                mock(HttpInputMessage.class)
        );

        ResponseEntity<ErrorResponse> response = customHandler.handleMessageNotReadableException(ex, request);

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(413, response.getBody().status());
        assertEquals("Payload Too Large", response.getBody().error());
        assertEquals("Request body exceeds maximum allowed size of 16384 bytes", response.getBody().message());
        assertEquals("/api/test", response.getBody().path());
    }

    @Nested
    @SpringBootTest(classes = GlobalExceptionHandler.class)
    @TestPropertySource(properties = "cache.max-request-body-bytes=12345")
    class SpringInjectionTest {

        @Autowired
        private GlobalExceptionHandler injectedHandler;

        @Test
        @DisplayName("Spring injects non-default cache.max-request-body-bytes property into GlobalExceptionHandler")
        void springInjectsConfiguredLimit() {
            PayloadTooLargeException ex = new PayloadTooLargeException("Raw message");

            ResponseEntity<ErrorResponse> response = injectedHandler.handlePayloadTooLargeException(ex, request);

            assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals(413, response.getBody().status());
            assertEquals("Payload Too Large", response.getBody().error());
            assertEquals("Request body exceeds maximum allowed size of 12345 bytes", response.getBody().message());
            assertEquals("/api/test", response.getBody().path());
        }
    }
}
