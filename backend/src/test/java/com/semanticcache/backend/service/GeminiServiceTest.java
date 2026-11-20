package com.semanticcache.backend.service;

import com.google.genai.Client;
import com.google.genai.Models;
import com.google.genai.types.GenerateContentResponse;
import com.semanticcache.backend.exception.GeminiApiException;
import com.semanticcache.backend.exception.GeminiEmptyResponseException;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GeminiServiceTest {

    @Mock
    private GeminiClientProvider clientProvider;

    @Mock
    private Client mockClient;

    @Mock
    private Models mockModels;

    private SimpleMeterRegistry meterRegistry;
    private CacheMetrics cacheMetrics;
    private GeminiService geminiService;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        cacheMetrics = new CacheMetrics(meterRegistry);
        geminiService = new GeminiService(clientProvider, "gemini-3.8-flash", cacheMetrics);
    }

    @Nested
    @DisplayName("Constructor and Initialization Tests")
    class ConstructorTests {

        @Test
        @DisplayName("Rejects null clientProvider with NullPointerException")
        void rejectsNullClientProvider() {
            NullPointerException ex = assertThrows(
                    NullPointerException.class,
                    () -> new GeminiService(null, "gemini-3.8-flash", cacheMetrics)
            );
            assertEquals("clientProvider must not be null", ex.getMessage());
        }

        @Test
        @DisplayName("Rejects null cacheMetrics with NullPointerException")
        void rejectsNullCacheMetrics() {
            NullPointerException ex = assertThrows(
                    NullPointerException.class,
                    () -> new GeminiService(clientProvider, "gemini-3.8-flash", null)
            );
            assertEquals("cacheMetrics must not be null", ex.getMessage());
        }

        @Test
        @DisplayName("Returns configured model")
        void returnsConfiguredModel() {
            assertEquals("gemini-3.8-flash", geminiService.getModel());
        }
    }

    @Nested
    @DisplayName("Generation and Telemetry Tests")
    class GenerationTests {

        @Test
        @DisplayName("Generates answer successfully and records SUCCESS metric and duration")
        void generateAnswerSuccess() {
            ReflectionTestUtils.setField(mockClient, "models", mockModels);
            when(clientProvider.getClient()).thenReturn(mockClient);

            GenerateContentResponse mockResponse = mock(GenerateContentResponse.class);
            when(mockResponse.text()).thenReturn("Quantum computing uses qubits.");
            when(mockModels.generateContent(eq("gemini-3.8-flash"), eq("What is quantum computing?"), any()))
                    .thenReturn(mockResponse);

            String answer = geminiService.generateAnswer("What is quantum computing?");

            assertNotNull(answer);
            assertEquals("Quantum computing uses qubits.", answer);
            verify(mockModels).generateContent("gemini-3.8-flash", "What is quantum computing?", null);

            // Verify SUCCESS metric recorded
            Counter successCalls = meterRegistry.find(CacheMetrics.METRIC_GEMINI_GENERATION_CALLS)
                    .tag(CacheMetrics.TAG_MODEL, "gemini-3.8-flash")
                    .tag(CacheMetrics.TAG_STATUS, "success")
                    .counter();
            assertNotNull(successCalls);
            assertEquals(1.0, successCalls.count());

            Timer successTimer = meterRegistry.find(CacheMetrics.METRIC_GEMINI_GENERATION_DURATION)
                    .tag(CacheMetrics.TAG_MODEL, "gemini-3.8-flash")
                    .tag(CacheMetrics.TAG_STATUS, "success")
                    .timer();
            assertNotNull(successTimer);
            assertEquals(1, successTimer.count());
            assertTrue(successTimer.totalTime(TimeUnit.NANOSECONDS) >= 0);

            // Verify no error or timeout recorded
            Counter errorCalls = meterRegistry.find(CacheMetrics.METRIC_GEMINI_GENERATION_CALLS)
                    .tag(CacheMetrics.TAG_STATUS, "error")
                    .counter();
            assertNull(errorCalls);

            Counter timeoutCalls = meterRegistry.find(CacheMetrics.METRIC_GEMINI_GENERATION_CALLS)
                    .tag(CacheMetrics.TAG_STATUS, "timeout")
                    .counter();
            assertNull(timeoutCalls);
        }

        @Test
        @DisplayName("Throws GeminiEmptyResponseException when response text is null and records ERROR metric")
        void throwsWhenResponseTextIsNull() {
            ReflectionTestUtils.setField(mockClient, "models", mockModels);
            when(clientProvider.getClient()).thenReturn(mockClient);

            GenerateContentResponse mockResponse = mock(GenerateContentResponse.class);
            when(mockResponse.text()).thenReturn(null);
            when(mockModels.generateContent(eq("gemini-3.8-flash"), eq("test prompt"), any()))
                    .thenReturn(mockResponse);

            assertThrows(GeminiEmptyResponseException.class, () -> geminiService.generateAnswer("test prompt"));

            Counter errorCalls = meterRegistry.find(CacheMetrics.METRIC_GEMINI_GENERATION_CALLS)
                    .tag(CacheMetrics.TAG_MODEL, "gemini-3.8-flash")
                    .tag(CacheMetrics.TAG_STATUS, "error")
                    .counter();
            assertNotNull(errorCalls);
            assertEquals(1.0, errorCalls.count());

            Timer errorTimer = meterRegistry.find(CacheMetrics.METRIC_GEMINI_GENERATION_DURATION)
                    .tag(CacheMetrics.TAG_MODEL, "gemini-3.8-flash")
                    .tag(CacheMetrics.TAG_STATUS, "error")
                    .timer();
            assertNotNull(errorTimer);
            assertEquals(1, errorTimer.count());
        }

        @Test
        @DisplayName("Throws GeminiEmptyResponseException when response text is blank and records ERROR metric")
        void throwsWhenResponseTextIsBlank() {
            ReflectionTestUtils.setField(mockClient, "models", mockModels);
            when(clientProvider.getClient()).thenReturn(mockClient);

            GenerateContentResponse mockResponse = mock(GenerateContentResponse.class);
            when(mockResponse.text()).thenReturn("   ");
            when(mockModels.generateContent(eq("gemini-3.8-flash"), eq("test prompt"), any()))
                    .thenReturn(mockResponse);

            assertThrows(GeminiEmptyResponseException.class, () -> geminiService.generateAnswer("test prompt"));

            Counter errorCalls = meterRegistry.find(CacheMetrics.METRIC_GEMINI_GENERATION_CALLS)
                    .tag(CacheMetrics.TAG_MODEL, "gemini-3.8-flash")
                    .tag(CacheMetrics.TAG_STATUS, "error")
                    .counter();
            assertNotNull(errorCalls);
            assertEquals(1.0, errorCalls.count());
        }

        @Test
        @DisplayName("Throws GeminiEmptyResponseException when response is null and records ERROR metric")
        void throwsWhenResponseIsNull() {
            ReflectionTestUtils.setField(mockClient, "models", mockModels);
            when(clientProvider.getClient()).thenReturn(mockClient);

            when(mockModels.generateContent(eq("gemini-3.8-flash"), eq("test prompt"), any()))
                    .thenReturn(null);

            assertThrows(GeminiEmptyResponseException.class, () -> geminiService.generateAnswer("test prompt"));

            Counter errorCalls = meterRegistry.find(CacheMetrics.METRIC_GEMINI_GENERATION_CALLS)
                    .tag(CacheMetrics.TAG_MODEL, "gemini-3.8-flash")
                    .tag(CacheMetrics.TAG_STATUS, "error")
                    .counter();
            assertNotNull(errorCalls);
            assertEquals(1.0, errorCalls.count());
        }

        @Test
        @DisplayName("Propagates GeminiMissingCredentialsException directly and records ERROR metric")
        void propagatesMissingCredentialsException() {
            when(clientProvider.getClient()).thenThrow(new GeminiMissingCredentialsException("Missing API key"));

            assertThrows(GeminiMissingCredentialsException.class, () -> geminiService.generateAnswer("test prompt"));

            Counter errorCalls = meterRegistry.find(CacheMetrics.METRIC_GEMINI_GENERATION_CALLS)
                    .tag(CacheMetrics.TAG_MODEL, "gemini-3.8-flash")
                    .tag(CacheMetrics.TAG_STATUS, "error")
                    .counter();
            assertNotNull(errorCalls);
            assertEquals(1.0, errorCalls.count());
        }

        @Test
        @DisplayName("Wraps API/SDK runtime failures in GeminiApiException and records ERROR metric")
        void wrapsRuntimeFailureInGeminiApiException() {
            ReflectionTestUtils.setField(mockClient, "models", mockModels);
            when(clientProvider.getClient()).thenReturn(mockClient);

            when(mockModels.generateContent(eq("gemini-3.8-flash"), eq("test prompt"), any()))
                    .thenThrow(new RuntimeException("Rate limit exceeded"));

            GeminiApiException ex = assertThrows(
                    GeminiApiException.class,
                    () -> geminiService.generateAnswer("test prompt")
            );

            assertEquals("Failed to generate response from Gemini API", ex.getMessage());
            assertNotNull(ex.getCause());
            assertEquals("Rate limit exceeded", ex.getCause().getMessage());

            Counter errorCalls = meterRegistry.find(CacheMetrics.METRIC_GEMINI_GENERATION_CALLS)
                    .tag(CacheMetrics.TAG_MODEL, "gemini-3.8-flash")
                    .tag(CacheMetrics.TAG_STATUS, "error")
                    .counter();
            assertNotNull(errorCalls);
            assertEquals(1.0, errorCalls.count());

            Timer errorTimer = meterRegistry.find(CacheMetrics.METRIC_GEMINI_GENERATION_DURATION)
                    .tag(CacheMetrics.TAG_MODEL, "gemini-3.8-flash")
                    .tag(CacheMetrics.TAG_STATUS, "error")
                    .timer();
            assertNotNull(errorTimer);
            assertEquals(1, errorTimer.count());
        }

        @Test
        @DisplayName("Timeout caused by SocketTimeoutException records TIMEOUT metric and wraps in GeminiApiException")
        void timeoutWithSocketTimeoutExceptionRecordsTimeoutMetric() {
            ReflectionTestUtils.setField(mockClient, "models", mockModels);
            when(clientProvider.getClient()).thenReturn(mockClient);

            when(mockModels.generateContent(eq("gemini-3.8-flash"), eq("test prompt"), any()))
                    .thenThrow(new RuntimeException(new SocketTimeoutException("Read timed out")));

            GeminiApiException ex = assertThrows(
                    GeminiApiException.class,
                    () -> geminiService.generateAnswer("test prompt")
            );

            assertEquals("Failed to generate response from Gemini API", ex.getMessage());
            assertNotNull(ex.getCause());

            Counter timeoutCalls = meterRegistry.find(CacheMetrics.METRIC_GEMINI_GENERATION_CALLS)
                    .tag(CacheMetrics.TAG_MODEL, "gemini-3.8-flash")
                    .tag(CacheMetrics.TAG_STATUS, "timeout")
                    .counter();
            assertNotNull(timeoutCalls);
            assertEquals(1.0, timeoutCalls.count());

            Timer timeoutTimer = meterRegistry.find(CacheMetrics.METRIC_GEMINI_GENERATION_DURATION)
                    .tag(CacheMetrics.TAG_MODEL, "gemini-3.8-flash")
                    .tag(CacheMetrics.TAG_STATUS, "timeout")
                    .timer();
            assertNotNull(timeoutTimer);
            assertEquals(1, timeoutTimer.count());

            // Ensure no error or success recorded
            assertNull(meterRegistry.find(CacheMetrics.METRIC_GEMINI_GENERATION_CALLS).tag(CacheMetrics.TAG_STATUS, "error").counter());
            assertNull(meterRegistry.find(CacheMetrics.METRIC_GEMINI_GENERATION_CALLS).tag(CacheMetrics.TAG_STATUS, "success").counter());
        }

        @Test
        @DisplayName("Timeout caused by HttpTimeoutException records TIMEOUT metric")
        void timeoutWithHttpTimeoutExceptionRecordsTimeoutMetric() {
            ReflectionTestUtils.setField(mockClient, "models", mockModels);
            when(clientProvider.getClient()).thenReturn(mockClient);

            when(mockModels.generateContent(eq("gemini-3.8-flash"), eq("test prompt"), any()))
                    .thenThrow(new RuntimeException(new HttpTimeoutException("HTTP request timed out")));

            assertThrows(GeminiApiException.class, () -> geminiService.generateAnswer("test prompt"));

            Counter timeoutCalls = meterRegistry.find(CacheMetrics.METRIC_GEMINI_GENERATION_CALLS)
                    .tag(CacheMetrics.TAG_MODEL, "gemini-3.8-flash")
                    .tag(CacheMetrics.TAG_STATUS, "timeout")
                    .counter();
            assertNotNull(timeoutCalls);
            assertEquals(1.0, timeoutCalls.count());
        }

        @Test
        @DisplayName("Direct TimeoutException records TIMEOUT metric")
        void directTimeoutExceptionRecordsTimeoutMetric() {
            ReflectionTestUtils.setField(mockClient, "models", mockModels);
            when(clientProvider.getClient()).thenReturn(mockClient);

            when(mockModels.generateContent(eq("gemini-3.8-flash"), eq("test prompt"), any()))
                    .thenThrow(new RuntimeException(new TimeoutException("Operation timed out")));

            assertThrows(GeminiApiException.class, () -> geminiService.generateAnswer("test prompt"));

            Counter timeoutCalls = meterRegistry.find(CacheMetrics.METRIC_GEMINI_GENERATION_CALLS)
                    .tag(CacheMetrics.TAG_MODEL, "gemini-3.8-flash")
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

            when(mockModels.generateContent(eq("gemini-3.8-flash"), eq("test prompt"), any()))
                    .thenThrow(new RuntimeException(new InterruptedException("Interrupted during call")));

            try {
                assertThrows(GeminiApiException.class, () -> geminiService.generateAnswer("test prompt"));
                assertTrue(Thread.currentThread().isInterrupted(), "Thread interrupt flag must be preserved");
            } finally {
                // Clear interrupt status for test runner thread
                Thread.interrupted();
            }
        }

        @Test
        @DisplayName("Normalizes arbitrary model name to other in metric tags")
        void normalizesArbitraryModelToOther() {
            GeminiService customService = new GeminiService(clientProvider, "arbitrary-unknown-model-xyz", cacheMetrics);

            ReflectionTestUtils.setField(mockClient, "models", mockModels);
            when(clientProvider.getClient()).thenReturn(mockClient);

            GenerateContentResponse mockResponse = mock(GenerateContentResponse.class);
            when(mockResponse.text()).thenReturn("Custom answer");
            when(mockModels.generateContent(eq("arbitrary-unknown-model-xyz"), eq("prompt"), any()))
                    .thenReturn(mockResponse);

            String answer = customService.generateAnswer("prompt");
            assertEquals("Custom answer", answer);

            Counter otherCalls = meterRegistry.find(CacheMetrics.METRIC_GEMINI_GENERATION_CALLS)
                    .tag(CacheMetrics.TAG_MODEL, "other")
                    .tag(CacheMetrics.TAG_STATUS, "success")
                    .counter();
            assertNotNull(otherCalls);
            assertEquals(1.0, otherCalls.count());

            // Arbitrary model string must not be a tag
            assertNull(meterRegistry.find(CacheMetrics.METRIC_GEMINI_GENERATION_CALLS)
                    .tag(CacheMetrics.TAG_MODEL, "arbitrary-unknown-model-xyz")
                    .counter());
        }
    }
}
