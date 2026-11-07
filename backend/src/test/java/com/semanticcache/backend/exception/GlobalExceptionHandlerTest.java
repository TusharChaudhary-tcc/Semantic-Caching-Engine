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
}
