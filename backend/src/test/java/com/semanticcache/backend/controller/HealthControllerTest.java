package com.semanticcache.backend.controller;

import com.semanticcache.backend.service.HealthService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
}
