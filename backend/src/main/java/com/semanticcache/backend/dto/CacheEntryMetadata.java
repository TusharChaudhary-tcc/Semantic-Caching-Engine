package com.semanticcache.backend.dto;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Lightweight metadata tracked for each cached vector entry to support
 * Semantic Density & Cost-Weighted Eviction without scanning full vector payloads.
 */
public record CacheEntryMetadata(
        UUID id,
        Instant createdAt,
        Instant lastAccessedAt,
        long hitCount,
        long generationLatencyMs,
        int estimatedTokens,
        double clusterDensity
) {
    public CacheEntryMetadata {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(lastAccessedAt, "lastAccessedAt must not be null");
        if (hitCount < 0) {
            throw new IllegalArgumentException("hitCount must be non-negative");
        }
        if (generationLatencyMs < 0) {
            throw new IllegalArgumentException("generationLatencyMs must be non-negative");
        }
        if (estimatedTokens < 0) {
            throw new IllegalArgumentException("estimatedTokens must be non-negative");
        }
        if (!Double.isFinite(clusterDensity) || clusterDensity < 0.0 || clusterDensity > 1.0) {
            throw new IllegalArgumentException("clusterDensity must be in [0.0, 1.0]");
        }
    }

    public CacheEntryMetadata withRecordedHit(Instant accessTime) {
        return new CacheEntryMetadata(
                id,
                createdAt,
                accessTime,
                hitCount + 1,
                generationLatencyMs,
                estimatedTokens,
                clusterDensity
        );
    }
}
