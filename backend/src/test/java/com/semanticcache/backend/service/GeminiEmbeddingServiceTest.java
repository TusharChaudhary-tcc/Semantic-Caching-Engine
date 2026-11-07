package com.semanticcache.backend.service;

import com.google.genai.Client;
import com.google.genai.Models;
import com.google.genai.types.ContentEmbedding;
import com.google.genai.types.EmbedContentConfig;
import com.google.genai.types.EmbedContentResponse;
import com.semanticcache.backend.exception.EmbeddingEmptyResponseException;
import com.semanticcache.backend.exception.EmbeddingException;
import com.semanticcache.backend.exception.GeminiMissingCredentialsException;
import com.semanticcache.backend.provider.GeminiClientProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GeminiEmbeddingServiceTest {

    private static final String MODEL = "gemini-embedding-001";
    private static final int DIMENSION = 768;
    private static final String TASK_TYPE = "SEMANTIC_SIMILARITY";

    @Mock
    private GeminiClientProvider clientProvider;

    @Mock
    private Client mockClient;

    @Mock
    private Models mockModels;

    private GeminiEmbeddingService embeddingService;

    @BeforeEach
    void setUp() {
        embeddingService = new GeminiEmbeddingService(clientProvider, MODEL, DIMENSION, TASK_TYPE);
    }

    private List<Float> createValidVector(int size) {
        List<Float> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            list.add((float) i / 100.0f);
        }
        return list;
    }

    @Nested
    @DisplayName("Configuration & Initialization Validation")
    class InitializationTests {

        @Test
        @DisplayName("Validates getters return configured values")
        void gettersReturnConfiguredValues() {
            assertEquals(MODEL, embeddingService.getModel());
            assertEquals(DIMENSION, embeddingService.getDimension());
            assertEquals(TASK_TYPE, embeddingService.getTaskType());
        }

        @Test
        @DisplayName("Rejects null client provider")
        void rejectsNullClientProvider() {
            assertThrows(NullPointerException.class, () ->
                    new GeminiEmbeddingService(null, MODEL, DIMENSION, TASK_TYPE)
            );
        }

        @Test
        @DisplayName("Rejects null or blank model")
        void rejectsNullOrBlankModel() {
            assertThrows(IllegalArgumentException.class, () ->
                    new GeminiEmbeddingService(clientProvider, null, DIMENSION, TASK_TYPE)
            );
            assertThrows(IllegalArgumentException.class, () ->
                    new GeminiEmbeddingService(clientProvider, "   ", DIMENSION, TASK_TYPE)
            );
        }

        @Test
        @DisplayName("Rejects non-positive dimension")
        void rejectsNonPositiveDimension() {
            assertThrows(IllegalArgumentException.class, () ->
                    new GeminiEmbeddingService(clientProvider, MODEL, 0, TASK_TYPE)
            );
            assertThrows(IllegalArgumentException.class, () ->
                    new GeminiEmbeddingService(clientProvider, MODEL, -1, TASK_TYPE)
            );
        }

        @Test
        @DisplayName("Rejects null or blank task type")
        void rejectsNullOrBlankTaskType() {
            assertThrows(IllegalArgumentException.class, () ->
                    new GeminiEmbeddingService(clientProvider, MODEL, DIMENSION, null)
            );
            assertThrows(IllegalArgumentException.class, () ->
                    new GeminiEmbeddingService(clientProvider, MODEL, DIMENSION, "   ")
            );
        }
    }

    @Nested
    @DisplayName("Embedding Generation Tests")
    class GenerationTests {

        @Test
        @DisplayName("Generates 768-dimensional embedding vector successfully with correct config")
        void generateEmbeddingSuccess() {
            ReflectionTestUtils.setField(mockClient, "models", mockModels);
            when(clientProvider.getClient()).thenReturn(mockClient);

            List<Float> mockVector = createValidVector(DIMENSION);
            ContentEmbedding contentEmbedding = ContentEmbedding.builder()
                    .values(mockVector)
                    .build();
            EmbedContentResponse mockResponse = EmbedContentResponse.builder()
                    .embeddings(List.of(contentEmbedding))
                    .build();

            when(mockModels.embedContent(eq(MODEL), eq("Hello semantic world"), any(EmbedContentConfig.class)))
                    .thenReturn(mockResponse);

            List<Float> result = embeddingService.generateEmbedding("Hello semantic world");

            assertNotNull(result);
            assertEquals(DIMENSION, result.size());
            assertEquals(mockVector, result);

            ArgumentCaptor<EmbedContentConfig> configCaptor = ArgumentCaptor.forClass(EmbedContentConfig.class);
            verify(mockModels).embedContent(eq(MODEL), eq("Hello semantic world"), configCaptor.capture());

            EmbedContentConfig capturedConfig = configCaptor.getValue();
            assertTrue(capturedConfig.taskType().isPresent());
            assertEquals(TASK_TYPE, capturedConfig.taskType().get());
            assertTrue(capturedConfig.outputDimensionality().isPresent());
            assertEquals(DIMENSION, capturedConfig.outputDimensionality().get());
        }

        @Test
        @DisplayName("Rejects null or blank input text")
        void rejectsNullOrBlankInput() {
            assertThrows(IllegalArgumentException.class, () -> embeddingService.generateEmbedding(null));
            assertThrows(IllegalArgumentException.class, () -> embeddingService.generateEmbedding(""));
            assertThrows(IllegalArgumentException.class, () -> embeddingService.generateEmbedding("   "));
        }

        @Test
        @DisplayName("Propagates GeminiMissingCredentialsException without wrapping")
        void propagatesMissingCredentialsException() {
            when(clientProvider.getClient()).thenThrow(new GeminiMissingCredentialsException("Missing API key"));

            GeminiMissingCredentialsException ex = assertThrows(
                    GeminiMissingCredentialsException.class,
                    () -> embeddingService.generateEmbedding("Sample text")
            );
            assertEquals("Missing API key", ex.getMessage());
        }

        @Test
        @DisplayName("Throws EmbeddingEmptyResponseException when response is null")
        void throwsWhenResponseIsNull() {
            ReflectionTestUtils.setField(mockClient, "models", mockModels);
            when(clientProvider.getClient()).thenReturn(mockClient);

            when(mockModels.embedContent(eq(MODEL), eq("Sample text"), any(EmbedContentConfig.class)))
                    .thenReturn(null);

            assertThrows(EmbeddingEmptyResponseException.class, () -> embeddingService.generateEmbedding("Sample text"));
        }

        @Test
        @DisplayName("Throws EmbeddingEmptyResponseException when embeddings list is empty")
        void throwsWhenEmbeddingsListIsEmpty() {
            ReflectionTestUtils.setField(mockClient, "models", mockModels);
            when(clientProvider.getClient()).thenReturn(mockClient);

            EmbedContentResponse mockResponse = EmbedContentResponse.builder()
                    .embeddings(Collections.emptyList())
                    .build();

            when(mockModels.embedContent(eq(MODEL), eq("Sample text"), any(EmbedContentConfig.class)))
                    .thenReturn(mockResponse);

            assertThrows(EmbeddingEmptyResponseException.class, () -> embeddingService.generateEmbedding("Sample text"));
        }

        @Test
        @DisplayName("Throws EmbeddingEmptyResponseException when values are missing or empty")
        void throwsWhenValuesAreEmpty() {
            ReflectionTestUtils.setField(mockClient, "models", mockModels);
            when(clientProvider.getClient()).thenReturn(mockClient);

            ContentEmbedding contentEmbedding = ContentEmbedding.builder()
                    .values(Collections.emptyList())
                    .build();
            EmbedContentResponse mockResponse = EmbedContentResponse.builder()
                    .embeddings(List.of(contentEmbedding))
                    .build();

            when(mockModels.embedContent(eq(MODEL), eq("Sample text"), any(EmbedContentConfig.class)))
                    .thenReturn(mockResponse);

            assertThrows(EmbeddingEmptyResponseException.class, () -> embeddingService.generateEmbedding("Sample text"));
        }

        @Test
        @DisplayName("Throws EmbeddingException when vector dimension does not match configured dimension")
        void throwsWhenDimensionMismatches() {
            ReflectionTestUtils.setField(mockClient, "models", mockModels);
            when(clientProvider.getClient()).thenReturn(mockClient);

            List<Float> shortVector = createValidVector(256); // Expected 768
            ContentEmbedding contentEmbedding = ContentEmbedding.builder()
                    .values(shortVector)
                    .build();
            EmbedContentResponse mockResponse = EmbedContentResponse.builder()
                    .embeddings(List.of(contentEmbedding))
                    .build();

            when(mockModels.embedContent(eq(MODEL), eq("Sample text"), any(EmbedContentConfig.class)))
                    .thenReturn(mockResponse);

            EmbeddingException ex = assertThrows(EmbeddingException.class, () -> embeddingService.generateEmbedding("Sample text"));
            assertTrue(ex.getMessage().contains("does not match expected dimension"));
        }

        @Test
        @DisplayName("Throws EmbeddingException when vector contains null elements")
        void throwsWhenVectorContainsNull() {
            ReflectionTestUtils.setField(mockClient, "models", mockModels);
            when(clientProvider.getClient()).thenReturn(mockClient);

            List<Float> vectorWithNull = createValidVector(DIMENSION);
            vectorWithNull.set(10, null);

            ContentEmbedding contentEmbedding = ContentEmbedding.builder()
                    .values(vectorWithNull)
                    .build();
            EmbedContentResponse mockResponse = EmbedContentResponse.builder()
                    .embeddings(List.of(contentEmbedding))
                    .build();

            when(mockModels.embedContent(eq(MODEL), eq("Sample text"), any(EmbedContentConfig.class)))
                    .thenReturn(mockResponse);

            EmbeddingException ex = assertThrows(EmbeddingException.class, () -> embeddingService.generateEmbedding("Sample text"));
            assertTrue(ex.getMessage().contains("contains null elements"));
        }

        @Test
        @DisplayName("Throws EmbeddingException when vector contains non-finite values (NaN / Infinity)")
        void throwsWhenVectorContainsNonFiniteValues() {
            ReflectionTestUtils.setField(mockClient, "models", mockModels);
            when(clientProvider.getClient()).thenReturn(mockClient);

            List<Float> vectorWithNaN = createValidVector(DIMENSION);
            vectorWithNaN.set(5, Float.NaN);

            ContentEmbedding contentEmbedding = ContentEmbedding.builder()
                    .values(vectorWithNaN)
                    .build();
            EmbedContentResponse mockResponse = EmbedContentResponse.builder()
                    .embeddings(List.of(contentEmbedding))
                    .build();

            when(mockModels.embedContent(eq(MODEL), eq("Sample text"), any(EmbedContentConfig.class)))
                    .thenReturn(mockResponse);

            EmbeddingException ex = assertThrows(EmbeddingException.class, () -> embeddingService.generateEmbedding("Sample text"));
            assertTrue(ex.getMessage().contains("non-finite"));
        }

        @Test
        @DisplayName("Wraps upstream SDK errors in EmbeddingException preserving cause")
        void wrapsUpstreamErrorsInEmbeddingException() {
            ReflectionTestUtils.setField(mockClient, "models", mockModels);
            when(clientProvider.getClient()).thenReturn(mockClient);

            RuntimeException sdkException = new RuntimeException("Underlying SDK connection reset");
            when(mockModels.embedContent(eq(MODEL), eq("Sample text"), any(EmbedContentConfig.class)))
                    .thenThrow(sdkException);

            EmbeddingException ex = assertThrows(
                    EmbeddingException.class,
                    () -> embeddingService.generateEmbedding("Sample text")
            );

            assertEquals("Failed to generate text embedding from the AI service", ex.getMessage());
            assertSame(sdkException, ex.getCause());
        }
    }
}
