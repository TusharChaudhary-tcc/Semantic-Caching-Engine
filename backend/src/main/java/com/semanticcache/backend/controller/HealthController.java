package com.semanticcache.backend.controller;

import com.semanticcache.backend.service.HealthService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class HealthController {

    private final HealthService healthService;

    public HealthController(HealthService healthService) {
        this.healthService = healthService;
    }

    @GetMapping("/api/health")
    public Map<String, String> healthCheck() {
        return Map.of(
                "status", "UP",
                "message", healthService.getStatus()
        );
    }
}
