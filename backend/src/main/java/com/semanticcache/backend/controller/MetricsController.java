package com.semanticcache.backend.controller;

import com.semanticcache.backend.dto.CacheMetricsResponse;
import com.semanticcache.backend.telemetry.CacheMetrics;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * Controller exposing aggregated cache telemetry metrics.
 */
@RestController
@RequestMapping("/api")
public class MetricsController {

    private final CacheMetrics cacheMetrics;

    public MetricsController(CacheMetrics cacheMetrics) {
        this.cacheMetrics = Objects.requireNonNull(cacheMetrics, "cacheMetrics must not be null");
    }

    @GetMapping("/metrics")
    public CacheMetricsResponse getMetrics() {
        return cacheMetrics.getSnapshot();
    }
}
