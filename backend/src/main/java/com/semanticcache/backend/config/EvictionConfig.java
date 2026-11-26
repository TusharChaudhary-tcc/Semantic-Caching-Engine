package com.semanticcache.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration properties for Semantic Density & Cost-Weighted Cache Eviction (Task 2.2).
 */
@Configuration
public class EvictionConfig {

    private final boolean enabled;
    private final int maxEntries;
    private final int kTail;
    private final double w1Recency;
    private final double w2Frequency;
    private final double w3Density;
    private final double w4Cost;
    private final double recencyDecayPerHour;

    public EvictionConfig(
            @Value("${cache.eviction.enabled:true}") boolean enabled,
            @Value("${cache.eviction.max-entries:10000}") int maxEntries,
            @Value("${cache.eviction.k-tail:100}") int kTail,
            @Value("${cache.eviction.weight-recency:0.30}") double w1Recency,
            @Value("${cache.eviction.weight-frequency:0.25}") double w2Frequency,
            @Value("${cache.eviction.weight-density:0.25}") double w3Density,
            @Value("${cache.eviction.weight-cost:0.20}") double w4Cost,
            @Value("${cache.eviction.recency-decay-per-hour:0.05}") double recencyDecayPerHour
    ) {
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("cache.eviction.max-entries must be positive");
        }
        if (kTail <= 0) {
            throw new IllegalArgumentException("cache.eviction.k-tail must be positive");
        }
        if (w1Recency < 0 || w2Frequency < 0 || w3Density < 0 || w4Cost < 0) {
            throw new IllegalArgumentException("Eviction weights must be non-negative");
        }
        if (recencyDecayPerHour < 0) {
            throw new IllegalArgumentException("recencyDecayPerHour must be non-negative");
        }
        this.enabled = enabled;
        this.maxEntries = maxEntries;
        this.kTail = kTail;
        this.w1Recency = w1Recency;
        this.w2Frequency = w2Frequency;
        this.w3Density = w3Density;
        this.w4Cost = w4Cost;
        this.recencyDecayPerHour = recencyDecayPerHour;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getMaxEntries() {
        return maxEntries;
    }

    public int getKTail() {
        return kTail;
    }

    public double getW1Recency() {
        return w1Recency;
    }

    public double getW2Frequency() {
        return w2Frequency;
    }

    public double getW3Density() {
        return w3Density;
    }

    public double getW4Cost() {
        return w4Cost;
    }

    public double getRecencyDecayPerHour() {
        return recencyDecayPerHour;
    }
}
