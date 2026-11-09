package com.semanticcache.backend.controller;

import com.semanticcache.backend.dto.HealthResponse;
import com.semanticcache.backend.service.HealthService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HealthControllerTest {

    @Test
    @DisplayName("Health endpoint returns UP status and expected message")
    void healthCheckReturnsUpAndExpectedMessage() {
        HealthService healthService = new HealthService();
        HealthController controller = new HealthController(healthService);

        Map<String, String> response = controller.healthCheck();

        assertNotNull(response);
        assertEquals("UP", response.get("status"));
        assertEquals("Semantic Cache backend is running!", response.get("message"));
    }

    @Test
    @DisplayName("Liveness endpoint returns 200 OK and UP status independently of external services")
    void livenessEndpointReturnsOkAndUpStatus() {
        HealthService healthService = mock(HealthService.class);
        when(healthService.checkLiveness())
                .thenReturn(HealthResponse.of("UP", "Semantic Cache backend is live"));

        HealthController controller = new HealthController(healthService);
        ResponseEntity<HealthResponse> response = controller.livenessCheck();

        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("UP", response.getBody().status());
        assertEquals("Semantic Cache backend is live", response.getBody().message());
    }

    @Test
    @DisplayName("Readiness endpoint returns 200 OK when service is ready")
    void readinessEndpointReturns200WhenReady() {
        HealthService healthService = mock(HealthService.class);
        HealthResponse readyResponse = HealthResponse.of(
                "UP",
                "Semantic Cache backend is ready",
                Map.of("qdrant", "UP", "geminiCredentials", "CONFIGURED")
        );
        when(healthService.checkReadiness()).thenReturn(readyResponse);

        HealthController controller = new HealthController(healthService);
        ResponseEntity<HealthResponse> response = controller.readinessCheck();

        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("UP", response.getBody().status());
        assertEquals("UP", response.getBody().components().get("qdrant"));
        assertEquals("CONFIGURED", response.getBody().components().get("geminiCredentials"));
    }

    @Test
    @DisplayName("Readiness endpoint returns 503 Service Unavailable when service is not ready")
    void readinessEndpointReturns503WhenNotReady() {
        HealthService healthService = mock(HealthService.class);
        HealthResponse unreadyResponse = HealthResponse.of(
                "DOWN",
                "Semantic Cache backend is not ready",
                Map.of("qdrant", "DOWN", "geminiCredentials", "CONFIGURED")
        );
        when(healthService.checkReadiness()).thenReturn(unreadyResponse);

        HealthController controller = new HealthController(healthService);
        ResponseEntity<HealthResponse> response = controller.readinessCheck();

        assertNotNull(response);
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("DOWN", response.getBody().status());
        assertEquals("DOWN", response.getBody().components().get("qdrant"));
        assertEquals("CONFIGURED", response.getBody().components().get("geminiCredentials"));
    }

    @Test
    @DisplayName("HealthResponse defensively copies components map preventing subsequent external mutation")
    void healthResponseDefensivelyCopiesComponentsMap() {
        Map<String, String> mutableMap = new HashMap<>();
        mutableMap.put("qdrant", "UP");
        mutableMap.put("geminiCredentials", "CONFIGURED");

        HealthResponse response = HealthResponse.of("UP", "Ready", mutableMap);

        // Mutate original map
        mutableMap.put("qdrant", "DOWN");
        mutableMap.put("newComponent", "TAMPERED");

        // Assert response components are unaffected by subsequent external mutations
        assertEquals("UP", response.components().get("qdrant"));
        assertNull(response.components().get("newComponent"));
        assertEquals(2, response.components().size());

        // Assert response components map is unmodifiable
        assertThrows(UnsupportedOperationException.class, () -> response.components().put("extra", "value"));
    }
}
