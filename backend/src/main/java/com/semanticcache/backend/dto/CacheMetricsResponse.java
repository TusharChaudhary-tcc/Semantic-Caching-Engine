package com.semanticcache.backend.dto;

/**
 * Immutable carrier exposing aggregated high-level cache telemetry.
 */
public record CacheMetricsResponse(
        long totalRequests,
        long cacheHits,
        long cacheMisses,
        long requestErrors,
        double hitRate,
        long geminiGenerationCalls,
        long geminiEmbeddingCalls,
        long qdrantOperations
) {
    public CacheMetricsResponse {
        if (totalRequests < 0) {
            throw new IllegalArgumentException("totalRequests must not be negative");
        }
        if (cacheHits < 0) {
            throw new IllegalArgumentException("cacheHits must not be negative");
        }
        if (cacheMisses < 0) {
            throw new IllegalArgumentException("cacheMisses must not be negative");
        }
        if (requestErrors < 0) {
            throw new IllegalArgumentException("requestErrors must not be negative");
        }
        if (geminiGenerationCalls < 0) {
            throw new IllegalArgumentException("geminiGenerationCalls must not be negative");
        }
        if (geminiEmbeddingCalls < 0) {
            throw new IllegalArgumentException("geminiEmbeddingCalls must not be negative");
        }
        if (qdrantOperations < 0) {
            throw new IllegalArgumentException("qdrantOperations must not be negative");
        }
        if (Double.isNaN(hitRate)) {
            throw new IllegalArgumentException("hitRate must not be NaN");
        }
        if (Double.isInfinite(hitRate)) {
            throw new IllegalArgumentException("hitRate must not be infinite");
        }
        if (hitRate < 0.0 || hitRate > 1.0) {
            throw new IllegalArgumentException("hitRate must be between 0.0 and 1.0");
        }
    }
}
