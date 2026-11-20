package com.semanticcache.backend.controller;

import com.semanticcache.backend.dto.HealthResponse;
import com.semanticcache.backend.service.HealthService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Objects;

@RestController
public class HealthController {

    private final HealthService healthService;

    public HealthController(HealthService healthService) {
        this.healthService = Objects.requireNonNull(healthService, "healthService must not be null");
    }

    @GetMapping("/api/health")
    public Map<String, String> healthCheck() {
        return Map.of(
                "status", "UP",
                "message", healthService.getStatus()
        );
    }

    @GetMapping("/api/health/liveness")
    public ResponseEntity<HealthResponse> livenessCheck() {
        HealthResponse response = healthService.checkLiveness();
        return ResponseEntity.ok(response);
    }

    @GetMapping("/api/health/readiness")
    public ResponseEntity<HealthResponse> readinessCheck() {
        HealthResponse response = healthService.checkReadiness();
        if (response.isUp()) {
            return ResponseEntity.ok(response);
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
    }
}
