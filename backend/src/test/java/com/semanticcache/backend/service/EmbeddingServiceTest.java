package com.semanticcache.backend.service;

import com.semanticcache.backend.dto.EmbeddingResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmbeddingServiceTest {

    @Mock
    private EmbeddingProvider embeddingProvider;

    @InjectMocks
    private EmbeddingService embeddingService;

    @Test
    @DisplayName("Generates embedding response with vector and dimension from provider")
    void getEmbeddingSuccess() {
        List<Float> vector = List.of(0.1f, 0.2f, 0.3f);
        when(embeddingProvider.generateEmbedding("Test query")).thenReturn(vector);

        EmbeddingResponse response = embeddingService.getEmbedding("Test query");

        assertNotNull(response);
        assertEquals(3, response.dimension());
        assertEquals(vector, response.embedding());
        verify(embeddingProvider).generateEmbedding("Test query");
    }

    @Test
    @DisplayName("Rejects null provider in constructor")
    void rejectsNullProvider() {
        assertThrows(NullPointerException.class, () -> new EmbeddingService(null));
    }

    @Test
    @DisplayName("EmbeddingResponse guarantees immutability")
    void embeddingResponseImmutability() {
        EmbeddingResponse response = new EmbeddingResponse(List.of(1.0f, 2.0f), 2);
        assertThrows(UnsupportedOperationException.class, () -> response.embedding().add(3.0f));
    }

    @Test
    @DisplayName("EmbeddingResponse rejects null vector")
    void embeddingResponseRejectsNullVector() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> new EmbeddingResponse(null, 1)
        );
        assertEquals("Embedding vector must not be null", ex.getMessage());
    }

    @Test
    @DisplayName("EmbeddingResponse rejects empty vector")
    void embeddingResponseRejectsEmptyVector() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> new EmbeddingResponse(Collections.emptyList(), 0)
        );
        assertEquals("Embedding vector must not be empty", ex.getMessage());
    }

    @Test
    @DisplayName("EmbeddingResponse rejects zero or negative dimension")
    void embeddingResponseRejectsNonPositiveDimension() {
        IllegalArgumentException exZero = assertThrows(
                IllegalArgumentException.class,
                () -> new EmbeddingResponse(List.of(1.0f), 0)
        );
        assertTrue(exZero.getMessage().contains("Dimension must be greater than zero"));

        IllegalArgumentException exNeg = assertThrows(
                IllegalArgumentException.class,
                () -> new EmbeddingResponse(List.of(1.0f), -5)
        );
        assertTrue(exNeg.getMessage().contains("Dimension must be greater than zero"));
    }

    @Test
    @DisplayName("EmbeddingResponse rejects dimension mismatch with vector size")
    void embeddingResponseRejectsDimensionMismatch() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> new EmbeddingResponse(List.of(1.0f, 2.0f), 3)
        );
        assertTrue(ex.getMessage().contains("does not match embedding size"));
    }
}
