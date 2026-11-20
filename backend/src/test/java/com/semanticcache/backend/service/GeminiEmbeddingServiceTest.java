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
import com.semanticcache.backend.telemetry.CacheMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    private SimpleMeterRegistry meterRegistry;
    private CacheMetrics cacheMetrics;
    private GeminiEmbeddingService embeddingService;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        cacheMetrics = new CacheMetrics(meterRegistry);
        embeddingService = new GeminiEmbeddingService(clientProvider, MODEL, DIMENSION, TASK_TYPE, cacheMetrics);
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
                    new GeminiEmbeddingService(null, MODEL, DIMENSION, TASK_TYPE, cacheMetrics)
            );
        }

        @Test
        @DisplayName("Rejects null cacheMetrics")
        void rejectsNullCacheMetrics() {
            assertThrows(NullPointerException.class, () ->
                    new GeminiEmbeddingService(clientProvider, MODEL, DIMENSION, TASK_TYPE, null)
            );
        }

        @Test
        @DisplayName("Rejects null or blank model")
        void rejectsNullOrBlankModel() {
            assertThrows(IllegalArgumentException.class, () ->
                    new GeminiEmbeddingService(clientProvider, null, DIMENSION, TASK_TYPE, cacheMetrics)
            );
            assertThrows(IllegalArgumentException.class, () ->
                    new GeminiEmbeddingService(clientProvider, "   ", DIMENSION, TASK_TYPE, cacheMetrics)
            );
        }

        @Test
        @DisplayName("Rejects non-positive dimension")
        void rejectsNonPositiveDimension() {
            assertThrows(IllegalArgumentException.class, () ->
                    new GeminiEmbeddingService(clientProvider, MODEL, 0, TASK_TYPE, cacheMetrics)
            );
            assertThrows(IllegalArgumentException.class, () ->
                    new GeminiEmbeddingService(clientProvider, MODEL, -1, TASK_TYPE, cacheMetrics)
            );
        }

        @Test
        @DisplayName("Rejects null or blank task type")
        void rejectsNullOrBlankTaskType() {
            assertThrows(IllegalArgumentException.class, () ->
                    new GeminiEmbeddingService(clientProvider, MODEL, DIMENSION, null, cacheMetrics)
            );
            assertThrows(IllegalArgumentException.class, () ->
                    new GeminiEmbeddingService(clientProvider, MODEL, DIMENSION, "   ", cacheMetrics)
            );
        }
    }

    @Nested
    @DisplayName("Embedding Generation Tests")
    class GenerationTests {

        @Test
        @DisplayName("Generates 768-dimensional embedding vector successfully and records SUCCESS metric")
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

            // Metrics assertions
            Counter successCalls = meterRegistry.find(CacheMetrics.METRIC_GEMINI_EMBEDDING_CALLS)
                    .tag(CacheMetrics.TAG_MODEL, MODEL)
                    .tag(CacheMetrics.TAG_STATUS, "success")
                    .counter();
            assertNotNull(successCalls);
            assertEquals(1.0, successCalls.count());

            Timer successTimer = meterRegistry.find(CacheMetrics.METRIC_GEMINI_EMBEDDING_DURATION)
                    .tag(CacheMetrics.TAG_MODEL, MODEL)
                    .tag(CacheMetrics.TAG_STATUS, "success")
                    .timer();
            assertNotNull(successTimer);
            assertEquals(1, successTimer.count());
            assertTrue(successTimer.totalTime(TimeUnit.NANOSECONDS) >= 0);

            assertNull(meterRegistry.find(CacheMetrics.METRIC_GEMINI_EMBEDDING_CALLS).tag(CacheMetrics.TAG_STATUS, "error").counter());
            assertNull(meterRegistry.find(CacheMetrics.METRIC_GEMINI_EMBEDDING_CALLS).tag(CacheMetrics.TAG_STATUS, "timeout").counter());
        }

        @Test
        @DisplayName("Rejects null or blank input text without recording any metrics")
        void rejectsNullOrBlankInput() {
            assertThrows(IllegalArgumentException.class, () -> embeddingService.generateEmbedding(null));
            assertThrows(IllegalArgumentException.class, () -> embeddingService.generateEmbedding(""));
            assertThrows(IllegalArgumentException.class, () -> embeddingService.generateEmbedding("   "));

            assertNull(meterRegistry.find(CacheMetrics.METRIC_GEMINI_EMBEDDING_CALLS).counter());
            assertNull(meterRegistry.find(CacheMetrics.METRIC_GEMINI_EMBEDDING_DURATION).timer());
        }

        @Test
        @DisplayName("Propagates GeminiMissingCredentialsException without wrapping and records ERROR metric")
        void propagatesMissingCredentialsException() {
            when(clientProvider.getClient()).thenThrow(new GeminiMissingCredentialsException("Missing API key"));

            GeminiMissingCredentialsException ex = assertThrows(
                    GeminiMissingCredentialsException.class,
                    () -> embeddingService.generateEmbedding("Sample text")
            );
            assertEquals("Missing API key", ex.getMessage());

            Counter errorCalls = meterRegistry.find(CacheMetrics.METRIC_GEMINI_EMBEDDING_CALLS)
                    .tag(CacheMetrics.TAG_MODEL, MODEL)
                    .tag(CacheMetrics.TAG_STATUS, "error")
                    .counter();
            assertNotNull(errorCalls);
            assertEquals(1.0, errorCalls.count());
        }

        @Test
        @DisplayName("Throws EmbeddingEmptyResponseException when response is null and records ERROR metric")
        void throwsWhenResponseIsNull() {
            ReflectionTestUtils.setField(mockClient, "models", mockModels);
            when(clientProvider.getClient()).thenReturn(mockClient);

            when(mockModels.embedContent(eq(MODEL), eq("Sample text"), any(EmbedContentConfig.class)))
                    .thenReturn(null);

            assertThrows(EmbeddingEmptyResponseException.class, () -> embeddingService.generateEmbedding("Sample text"));

            Counter errorCalls = meterRegistry.find(CacheMetrics.METRIC_GEMINI_EMBEDDING_CALLS)
                    .tag(CacheMetrics.TAG_MODEL, MODEL)
                    .tag(CacheMetrics.TAG_STATUS, "error")
                    .counter();
            assertNotNull(errorCalls);
            assertEquals(1.0, errorCalls.count());
        }

        @Test
        @DisplayName("Throws EmbeddingEmptyResponseException when embeddings list is empty and records ERROR metric")
        void throwsWhenEmbeddingsListIsEmpty() {
            ReflectionTestUtils.setField(mockClient, "models", mockModels);
            when(clientProvider.getClient()).thenReturn(mockClient);

            EmbedContentResponse mockResponse = EmbedContentResponse.builder()
                    .embeddings(Collections.emptyList())
                    .build();

            when(mockModels.embedContent(eq(MODEL), eq("Sample text"), any(EmbedContentConfig.class)))
                    .thenReturn(mockResponse);

            assertThrows(EmbeddingEmptyResponseException.class, () -> embeddingService.generateEmbedding("Sample text"));

            Counter errorCalls = meterRegistry.find(CacheMetrics.METRIC_GEMINI_EMBEDDING_CALLS)
                    .tag(CacheMetrics.TAG_MODEL, MODEL)
                    .tag(CacheMetrics.TAG_STATUS, "error")
                    .counter();
            assertNotNull(errorCalls);
            assertEquals(1.0, errorCalls.count());
        }

        @Test
        @DisplayName("Throws EmbeddingEmptyResponseException when values are missing or empty and records ERROR metric")
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

            Counter errorCalls = meterRegistry.find(CacheMetrics.METRIC_GEMINI_EMBEDDING_CALLS)
                    .tag(CacheMetrics.TAG_MODEL, MODEL)
                    .tag(CacheMetrics.TAG_STATUS, "error")
                    .counter();
            assertNotNull(errorCalls);
            assertEquals(1.0, errorCalls.count());
        }

        @Test
        @DisplayName("Throws EmbeddingException when vector dimension does not match configured dimension and records ERROR metric")
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

            Counter errorCalls = meterRegistry.find(CacheMetrics.METRIC_GEMINI_EMBEDDING_CALLS)
                    .tag(CacheMetrics.TAG_MODEL, MODEL)
                    .tag(CacheMetrics.TAG_STATUS, "error")
                    .counter();
            assertNotNull(errorCalls);
            assertEquals(1.0, errorCalls.count());
        }

        @Test
        @DisplayName("Throws EmbeddingException when vector contains null elements and records ERROR metric")
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

            Counter errorCalls = meterRegistry.find(CacheMetrics.METRIC_GEMINI_EMBEDDING_CALLS)
                    .tag(CacheMetrics.TAG_MODEL, MODEL)
                    .tag(CacheMetrics.TAG_STATUS, "error")
                    .counter();
            assertNotNull(errorCalls);
            assertEquals(1.0, errorCalls.count());
        }

        @Test
        @DisplayName("Throws EmbeddingException when vector contains non-finite values (NaN / Infinity) and records ERROR metric")
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

            Counter errorCalls = meterRegistry.find(CacheMetrics.METRIC_GEMINI_EMBEDDING_CALLS)
                    .tag(CacheMetrics.TAG_MODEL, MODEL)
                    .tag(CacheMetrics.TAG_STATUS, "error")
                    .counter();
            assertNotNull(errorCalls);
            assertEquals(1.0, errorCalls.count());
        }

        @Test
        @DisplayName("Wraps upstream SDK errors in EmbeddingException preserving cause and records ERROR metric")
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

            Counter errorCalls = meterRegistry.find(CacheMetrics.METRIC_GEMINI_EMBEDDING_CALLS)
                    .tag(CacheMetrics.TAG_MODEL, MODEL)
                    .tag(CacheMetrics.TAG_STATUS, "error")
                    .counter();
            assertNotNull(errorCalls);
            assertEquals(1.0, errorCalls.count());

            Timer errorTimer = meterRegistry.find(CacheMetrics.METRIC_GEMINI_EMBEDDING_DURATION)
                    .tag(CacheMetrics.TAG_MODEL, MODEL)
                    .tag(CacheMetrics.TAG_STATUS, "error")
                    .timer();
            assertNotNull(errorTimer);
            assertEquals(1, errorTimer.count());
        }

        @Test
        @DisplayName("Timeout caused by SocketTimeoutException records TIMEOUT metric and wraps in EmbeddingException")
        void timeoutWithSocketTimeoutExceptionRecordsTimeoutMetric() {
            ReflectionTestUtils.setField(mockClient, "models", mockModels);
            when(clientProvider.getClient()).thenReturn(mockClient);

            when(mockModels.embedContent(eq(MODEL), eq("Sample text"), any(EmbedContentConfig.class)))
                    .thenThrow(new RuntimeException(new SocketTimeoutException("Read timed out")));

            EmbeddingException ex = assertThrows(
                    EmbeddingException.class,
                    () -> embeddingService.generateEmbedding("Sample text")
            );

            assertEquals("Failed to generate text embedding from the AI service", ex.getMessage());
            assertNotNull(ex.getCause());

            Counter timeoutCalls = meterRegistry.find(CacheMetrics.METRIC_GEMINI_EMBEDDING_CALLS)
                    .tag(CacheMetrics.TAG_MODEL, MODEL)
                    .tag(CacheMetrics.TAG_STATUS, "timeout")
                    .counter();
            assertNotNull(timeoutCalls);
            assertEquals(1.0, timeoutCalls.count());

            Timer timeoutTimer = meterRegistry.find(CacheMetrics.METRIC_GEMINI_EMBEDDING_DURATION)
                    .tag(CacheMetrics.TAG_MODEL, MODEL)
                    .tag(CacheMetrics.TAG_STATUS, "timeout")
                    .timer();
            assertNotNull(timeoutTimer);
            assertEquals(1, timeoutTimer.count());

            assertNull(meterRegistry.find(CacheMetrics.METRIC_GEMINI_EMBEDDING_CALLS).tag(CacheMetrics.TAG_STATUS, "error").counter());
            assertNull(meterRegistry.find(CacheMetrics.METRIC_GEMINI_EMBEDDING_CALLS).tag(CacheMetrics.TAG_STATUS, "success").counter());
        }

        @Test
        @DisplayName("Direct TimeoutException records TIMEOUT metric")
        void directTimeoutExceptionRecordsTimeoutMetric() {
            ReflectionTestUtils.setField(mockClient, "models", mockModels);
            when(clientProvider.getClient()).thenReturn(mockClient);

            when(mockModels.embedContent(eq(MODEL), eq("Sample text"), any(EmbedContentConfig.class)))
                    .thenThrow(new RuntimeException(new TimeoutException("Operation timed out")));

            assertThrows(EmbeddingException.class, () -> embeddingService.generateEmbedding("Sample text"));

            Counter timeoutCalls = meterRegistry.find(CacheMetrics.METRIC_GEMINI_EMBEDDING_CALLS)
                    .tag(CacheMetrics.TAG_MODEL, MODEL)
                    .tag(CacheMetrics.TAG_STATUS, "timeout")
                    .counter();
            assertNotNull(timeoutCalls);
            assertEquals(1.0, timeoutCalls.count());
        }

        @Test
        @DisplayName("Preserves thread interruption when InterruptedException is handled")
        void preservesThreadInterruption() {
            ReflectionTestUtils.setField(mockClient, "models", mockModels);
            when(clientProvider.getClient()).thenReturn(mockClient);

            when(mockModels.embedContent(eq(MODEL), eq("Sample text"), any(EmbedContentConfig.class)))
                    .thenThrow(new RuntimeException(new InterruptedException("Interrupted during call")));

            try {
                assertThrows(EmbeddingException.class, () -> embeddingService.generateEmbedding("Sample text"));
                assertTrue(Thread.currentThread().isInterrupted(), "Thread interrupt flag must be preserved");
            } finally {
                Thread.interrupted();
            }
        }

        @Test
        @DisplayName("Restores thread interrupt flag when EmbeddingException with InterruptedException cause is caught")
        void restoresThreadInterruptionWhenEmbeddingExceptionWithInterruptedCauseThrown() {
            EmbeddingException rootException = new EmbeddingException(
                    "Interrupted during client retrieval",
                    new InterruptedException("Thread interrupted")
            );
            when(clientProvider.getClient()).thenThrow(rootException);

            try {
                EmbeddingException thrown = assertThrows(
                        EmbeddingException.class,
                        () -> embeddingService.generateEmbedding("Sample text")
                );
                assertSame(rootException, thrown);
                assertTrue(Thread.currentThread().isInterrupted(), "Thread interrupt flag must be restored");
            } finally {
                Thread.interrupted();
            }
        }

        @Test
        @DisplayName("Normalizes arbitrary embedding model name to other in metric tags")
        void normalizesArbitraryModelToOther() {
            GeminiEmbeddingService customService = new GeminiEmbeddingService(
                    clientProvider, "custom-unsupported-embedding-model", DIMENSION, TASK_TYPE, cacheMetrics
            );

            ReflectionTestUtils.setField(mockClient, "models", mockModels);
            when(clientProvider.getClient()).thenReturn(mockClient);

            List<Float> mockVector = createValidVector(DIMENSION);
            ContentEmbedding contentEmbedding = ContentEmbedding.builder()
                    .values(mockVector)
                    .build();
            EmbedContentResponse mockResponse = EmbedContentResponse.builder()
                    .embeddings(List.of(contentEmbedding))
                    .build();

            when(mockModels.embedContent(eq("custom-unsupported-embedding-model"), eq("Sample text"), any(EmbedContentConfig.class)))
                    .thenReturn(mockResponse);

            List<Float> result = customService.generateEmbedding("Sample text");
            assertNotNull(result);

            Counter otherCalls = meterRegistry.find(CacheMetrics.METRIC_GEMINI_EMBEDDING_CALLS)
                    .tag(CacheMetrics.TAG_MODEL, "other")
                    .tag(CacheMetrics.TAG_STATUS, "success")
                    .counter();
            assertNotNull(otherCalls);
            assertEquals(1.0, otherCalls.count());

            assertNull(meterRegistry.find(CacheMetrics.METRIC_GEMINI_EMBEDDING_CALLS)
                    .tag(CacheMetrics.TAG_MODEL, "custom-unsupported-embedding-model")
                    .counter());
        }
    }
}
