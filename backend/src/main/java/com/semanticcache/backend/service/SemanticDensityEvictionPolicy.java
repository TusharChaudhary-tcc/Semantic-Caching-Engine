package com.semanticcache.backend.service;

import com.semanticcache.backend.config.EvictionConfig;
import com.semanticcache.backend.dto.CacheEntryMetadata;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Core research eviction algorithm:
 * Score = w1 * Recency + w2 * Frequency + w3 * SemanticClusterDensity + w4 * CostWeight.
 */
@Service
public class SemanticDensityEvictionPolicy implements EvictionPolicy {

    private static final double FREQUENCY_SATURATION_K = 5.0;
    private static final double REFERENCE_LATENCY_MS = 5000.0;
    private static final double REFERENCE_TOKENS = 1000.0;

    private final EvictionConfig config;

    public SemanticDensityEvictionPolicy(EvictionConfig config) {
        this.config = Objects.requireNonNull(config, "config must not be null");
    }

    @Override
    public double computeKeepScore(CacheEntryMetadata entry, Instant now) {
        Objects.requireNonNull(entry, "entry must not be null");
        Objects.requireNonNull(now, "now must not be null");

        double recency = computeRecency(entry.lastAccessedAt(), now);
        double frequency = computeFrequency(entry.hitCount());
        double density = entry.clusterDensity();
        double cost = computeCostWeight(entry.generationLatencyMs(), entry.estimatedTokens());

        return config.getW1Recency() * recency
                + config.getW2Frequency() * frequency
                + config.getW3Density() * density
                + config.getW4Cost() * cost;
    }

    @Override
    public Optional<UUID> selectVictim(Collection<CacheEntryMetadata> candidates, Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        if (candidates == null || candidates.isEmpty()) {
            return Optional.empty();
        }

        UUID victimId = null;
        double lowestScore = Double.POSITIVE_INFINITY;

        for (CacheEntryMetadata candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            double score = computeKeepScore(candidate, now);
            if (score < lowestScore) {
                lowestScore = score;
                victimId = candidate.id();
            }
        }
        return Optional.ofNullable(victimId);
    }

    double computeRecency(Instant lastAccessedAt, Instant now) {
        long millis = Math.max(0L, Duration.between(lastAccessedAt, now).toMillis());
        double hours = millis / 3_600_000.0;
        return Math.exp(-config.getRecencyDecayPerHour() * hours);
    }

    static double computeFrequency(long hitCount) {
        if (hitCount <= 0) {
            return 0.0;
        }
        return hitCount / (hitCount + FREQUENCY_SATURATION_K);
    }

    static double computeCostWeight(long generationLatencyMs, int estimatedTokens) {
        double latencyFactor = Math.min(1.0, generationLatencyMs / REFERENCE_LATENCY_MS);
        double tokenFactor = Math.min(1.0, estimatedTokens / REFERENCE_TOKENS);
        return 0.5 * latencyFactor + 0.5 * tokenFactor;
    }
}
