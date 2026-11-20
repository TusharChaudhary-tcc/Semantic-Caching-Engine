package com.semanticcache.backend.service;

import com.google.common.util.concurrent.ListenableFuture;
import com.semanticcache.backend.dto.HealthResponse;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.QdrantOuterClass.HealthCheckReply;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HealthServiceTest {

    private QdrantClient qdrantClient;
    private ListenableFuture<HealthCheckReply> future;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        qdrantClient = mock(QdrantClient.class);
        future = mock(ListenableFuture.class);
    }

    @AfterEach
    void cleanUpInterruptFlag() {
        // Clear any interrupted status on the test thread defensively
        Thread.interrupted();
    }

    @Test
    @DisplayName("getStatus returns backward-compatible message")
    void getStatusReturnsExpectedMessage() {
        HealthService healthService = new HealthService();
        assertEquals("Semantic Cache backend is running!", healthService.getStatus());
    }

    @Test
    @DisplayName("Liveness check returns UP and does not interact with external services")
    void livenessCheckReturnsUpWithoutExternalCalls() {
        HealthService healthService = new HealthService(qdrantClient, 3, () -> true);

        HealthResponse response = healthService.checkLiveness();

        assertNotNull(response);
        assertEquals("UP", response.status());
        assertEquals("Semantic Cache backend is live", response.message());
        assertNull(response.components());
        assertTrue(response.isUp());
        verify(qdrantClient, never()).healthCheckAsync();
    }

    @Test
    @DisplayName("Readiness check returns UP when Qdrant is healthy and Gemini credentials configured")
    void readinessCheckReturnsUpWhenDependenciesHealthy() throws Exception {
        when(qdrantClient.healthCheckAsync()).thenReturn(future);
        when(future.get(anyLong(), any(TimeUnit.class))).thenReturn(HealthCheckReply.getDefaultInstance());

        HealthService healthService = new HealthService(qdrantClient, 3, () -> true);

        HealthResponse response = healthService.checkReadiness();

        assertNotNull(response);
        assertEquals("UP", response.status());
        assertEquals("Semantic Cache backend is ready", response.message());
        assertTrue(response.isUp());
        assertNotNull(response.components());
        assertEquals("UP", response.components().get("qdrant"));
        assertEquals("CONFIGURED", response.components().get("geminiCredentials"));
    }

    @Test
    @DisplayName("Readiness check returns DOWN when Qdrant health check throws ExecutionException")
    void readinessCheckReturnsDownWhenQdrantFails() throws Exception {
        when(qdrantClient.healthCheckAsync()).thenReturn(future);
        when(future.get(anyLong(), any(TimeUnit.class)))
                .thenThrow(new ExecutionException(new RuntimeException("Connection refused")));

        HealthService healthService = new HealthService(qdrantClient, 3, () -> true);

        HealthResponse response = healthService.checkReadiness();

        assertNotNull(response);
        assertEquals("DOWN", response.status());
        assertEquals("Semantic Cache backend is not ready", response.message());
        assertFalse(response.isUp());
        assertEquals("DOWN", response.components().get("qdrant"));
        assertEquals("CONFIGURED", response.components().get("geminiCredentials"));
    }

    @Test
    @DisplayName("Readiness check returns DOWN when Qdrant health check times out and cancels future")
    void readinessCheckReturnsDownWhenQdrantTimesOut() throws Exception {
        when(qdrantClient.healthCheckAsync()).thenReturn(future);
        when(future.get(anyLong(), any(TimeUnit.class))).thenThrow(new TimeoutException("Timed out"));

        HealthService healthService = new HealthService(qdrantClient, 3, () -> true);

        HealthResponse response = healthService.checkReadiness();

        assertNotNull(response);
        assertEquals("DOWN", response.status());
        assertFalse(response.isUp());
        assertEquals("DOWN", response.components().get("qdrant"));
        assertEquals("CONFIGURED", response.components().get("geminiCredentials"));
        verify(future).cancel(true);
    }

    @Test
    @DisplayName("Readiness check returns DOWN when interrupted, cancels future, and restores interrupt flag")
    void readinessCheckReturnsDownWhenInterruptedAndRestoresFlag() throws Exception {
        when(qdrantClient.healthCheckAsync()).thenReturn(future);
        when(future.get(anyLong(), any(TimeUnit.class))).thenThrow(new InterruptedException("Interrupted"));

        HealthService healthService = new HealthService(qdrantClient, 3, () -> true);

        HealthResponse response = healthService.checkReadiness();

        assertNotNull(response);
        assertEquals("DOWN", response.status());
        assertFalse(response.isUp());
        assertEquals("DOWN", response.components().get("qdrant"));
        assertEquals("CONFIGURED", response.components().get("geminiCredentials"));
        verify(future).cancel(true);
        assertTrue(Thread.currentThread().isInterrupted(), "Interrupt flag must be restored on the calling thread");
    }

    @Test
    @DisplayName("Readiness check returns DOWN when Gemini credentials are missing")
    void readinessCheckReturnsDownWhenGeminiCredentialsMissing() throws Exception {
        when(qdrantClient.healthCheckAsync()).thenReturn(future);
        when(future.get(anyLong(), any(TimeUnit.class))).thenReturn(HealthCheckReply.getDefaultInstance());

        HealthService healthService = new HealthService(qdrantClient, 3, () -> false);

        HealthResponse response = healthService.checkReadiness();

        assertNotNull(response);
        assertEquals("DOWN", response.status());
        assertEquals("Semantic Cache backend is not ready", response.message());
        assertFalse(response.isUp());
        assertEquals("UP", response.components().get("qdrant"));
        assertEquals("MISSING", response.components().get("geminiCredentials"));
    }

    @Test
    @DisplayName("Readiness check returns DOWN when QdrantClient is null")
    void readinessCheckReturnsDownWhenQdrantClientIsNull() {
        HealthService healthService = new HealthService(null, 3, () -> true);

        HealthResponse response = healthService.checkReadiness();

        assertNotNull(response);
        assertEquals("DOWN", response.status());
        assertFalse(response.isUp());
        assertEquals("DOWN", response.components().get("qdrant"));
        assertEquals("CONFIGURED", response.components().get("geminiCredentials"));
    }

    @Test
    @DisplayName("Readiness check returns DOWN when both Qdrant and Gemini credentials fail")
    void readinessCheckReturnsDownWhenBothFail() throws Exception {
        when(qdrantClient.healthCheckAsync()).thenReturn(future);
        when(future.get(anyLong(), any(TimeUnit.class))).thenThrow(new TimeoutException("Timed out"));

        HealthService healthService = new HealthService(qdrantClient, 3, () -> false);

        HealthResponse response = healthService.checkReadiness();

        assertNotNull(response);
        assertEquals("DOWN", response.status());
        assertFalse(response.isUp());
        assertEquals("DOWN", response.components().get("qdrant"));
        assertEquals("MISSING", response.components().get("geminiCredentials"));
    }
}
