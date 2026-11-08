package com.semanticcache.backend.exception;

import com.semanticcache.backend.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler exceptionHandler;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        exceptionHandler = new GlobalExceptionHandler();
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
}
