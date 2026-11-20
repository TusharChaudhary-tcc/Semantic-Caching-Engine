package com.semanticcache.backend.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CacheMetricsResponseTest {

    @Test
    @DisplayName("Should create CacheMetricsResponse successfully with valid values")
    void shouldCreateWithValidValues() {
        CacheMetricsResponse response = new CacheMetricsResponse(
                100L,
                80L,
                20L,
                2L,
                0.80,
                20L,
                100L,
                120L
        );

        assertThat(response.totalRequests()).isEqualTo(100L);
        assertThat(response.cacheHits()).isEqualTo(80L);
        assertThat(response.cacheMisses()).isEqualTo(20L);
        assertThat(response.requestErrors()).isEqualTo(2L);
        assertThat(response.hitRate()).isEqualTo(0.80);
        assertThat(response.geminiGenerationCalls()).isEqualTo(20L);
        assertThat(response.geminiEmbeddingCalls()).isEqualTo(100L);
        assertThat(response.qdrantOperations()).isEqualTo(120L);
    }

    @Test
    @DisplayName("Should allow boundary hitRate values 0.0 and 1.0")
    void shouldAllowBoundaryHitRateValues() {
        CacheMetricsResponse zeroHitRate = new CacheMetricsResponse(0L, 0L, 0L, 0L, 0.0, 0L, 0L, 0L);
        assertThat(zeroHitRate.hitRate()).isEqualTo(0.0);

        CacheMetricsResponse fullHitRate = new CacheMetricsResponse(10L, 10L, 0L, 0L, 1.0, 0L, 10L, 10L);
        assertThat(fullHitRate.hitRate()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Should reject negative totalRequests")
    void shouldRejectNegativeTotalRequests() {
        assertThatThrownBy(() -> new CacheMetricsResponse(-1L, 0L, 0L, 0L, 0.0, 0L, 0L, 0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("totalRequests must not be negative");
    }

    @Test
    @DisplayName("Should reject negative cacheHits")
    void shouldRejectNegativeCacheHits() {
        assertThatThrownBy(() -> new CacheMetricsResponse(0L, -1L, 0L, 0L, 0.0, 0L, 0L, 0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cacheHits must not be negative");
    }

    @Test
    @DisplayName("Should reject negative cacheMisses")
    void shouldRejectNegativeCacheMisses() {
        assertThatThrownBy(() -> new CacheMetricsResponse(0L, 0L, -1L, 0L, 0.0, 0L, 0L, 0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cacheMisses must not be negative");
    }

    @Test
    @DisplayName("Should reject negative requestErrors")
    void shouldRejectNegativeRequestErrors() {
        assertThatThrownBy(() -> new CacheMetricsResponse(0L, 0L, 0L, -1L, 0.0, 0L, 0L, 0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requestErrors must not be negative");
    }

    @Test
    @DisplayName("Should reject negative geminiGenerationCalls")
    void shouldRejectNegativeGeminiGenerationCalls() {
        assertThatThrownBy(() -> new CacheMetricsResponse(0L, 0L, 0L, 0L, 0.0, -1L, 0L, 0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("geminiGenerationCalls must not be negative");
    }

    @Test
    @DisplayName("Should reject negative geminiEmbeddingCalls")
    void shouldRejectNegativeGeminiEmbeddingCalls() {
        assertThatThrownBy(() -> new CacheMetricsResponse(0L, 0L, 0L, 0L, 0.0, 0L, -1L, 0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("geminiEmbeddingCalls must not be negative");
    }

    @Test
    @DisplayName("Should reject negative qdrantOperations")
    void shouldRejectNegativeQdrantOperations() {
        assertThatThrownBy(() -> new CacheMetricsResponse(0L, 0L, 0L, 0L, 0.0, 0L, 0L, -1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("qdrantOperations must not be negative");
    }

    @Test
    @DisplayName("Should reject NaN hitRate")
    void shouldRejectNaNHitRate() {
        assertThatThrownBy(() -> new CacheMetricsResponse(0L, 0L, 0L, 0L, Double.NaN, 0L, 0L, 0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("hitRate must not be NaN");
    }

    @Test
    @DisplayName("Should reject positive and negative infinite hitRate")
    void shouldRejectInfiniteHitRate() {
        assertThatThrownBy(() -> new CacheMetricsResponse(0L, 0L, 0L, 0L, Double.POSITIVE_INFINITY, 0L, 0L, 0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("hitRate must not be infinite");

        assertThatThrownBy(() -> new CacheMetricsResponse(0L, 0L, 0L, 0L, Double.NEGATIVE_INFINITY, 0L, 0L, 0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("hitRate must not be infinite");
    }

    @Test
    @DisplayName("Should reject hitRate below 0.0 or above 1.0")
    void shouldRejectOutOfRangeHitRate() {
        assertThatThrownBy(() -> new CacheMetricsResponse(0L, 0L, 0L, 0L, -0.01, 0L, 0L, 0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("hitRate must be between 0.0 and 1.0");

        assertThatThrownBy(() -> new CacheMetricsResponse(0L, 0L, 0L, 0L, 1.01, 0L, 0L, 0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("hitRate must be between 0.0 and 1.0");
    }
}
