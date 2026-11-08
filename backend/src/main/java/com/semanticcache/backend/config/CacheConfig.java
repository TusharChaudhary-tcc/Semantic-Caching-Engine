package com.semanticcache.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Centralized configuration for semantic cache decision thresholds and parameters.
 */
@Configuration
public class CacheConfig {

    private final float similarityThreshold;

    public CacheConfig(@Value("${cache.similarity-threshold:0.85}") float similarityThreshold) {
        if (!Float.isFinite(similarityThreshold) || similarityThreshold < 0.0f || similarityThreshold > 1.0f) {
            throw new IllegalArgumentException(
                    "cache.similarity-threshold must be a finite numerical value between 0.0 and 1.0 (inclusive). Configured value: "
                            + similarityThreshold
            );
        }
        this.similarityThreshold = similarityThreshold;
    }

    public float getSimilarityThreshold() {
        return similarityThreshold;
    }
}
