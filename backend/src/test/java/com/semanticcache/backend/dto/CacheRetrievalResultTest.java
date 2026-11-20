package com.semanticcache.backend.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CacheRetrievalResultTest {

    private SearchResult sampleCandidate() {
        return new SearchResult(
                UUID.randomUUID(),
                "test prompt",
                "test answer",
                "gemini-3.8-flash",
                java.time.Instant.now(),
                "gemini-embedding-001",
                0.95f,
                null,
                "PUBLIC"
        );
    }

    @Test
    @DisplayName("Should create CacheRetrievalResult successfully with candidate and vector")
    void shouldCreateWithCandidateAndVector() {
        SearchResult candidate = sampleCandidate();
        List<Float> vector = List.of(0.1f, 0.2f, 0.3f);

        CacheRetrievalResult result = new CacheRetrievalResult(Optional.of(candidate), vector);

        assertThat(result.candidate()).contains(candidate);
        assertThat(result.queryVector()).containsExactly(0.1f, 0.2f, 0.3f);
    }

    @Test
    @DisplayName("Should create CacheRetrievalResult with empty candidate and vector via factory method")
    void shouldCreateWithEmptyCandidateViaFactory() {
        List<Float> vector = List.of(0.5f, -0.2f);

        CacheRetrievalResult result = CacheRetrievalResult.of(Optional.empty(), vector);

        assertThat(result.candidate()).isEmpty();
        assertThat(result.queryVector()).containsExactly(0.5f, -0.2f);
    }

    @Test
    @DisplayName("Should reject null candidate Optional")
    void shouldRejectNullCandidateOptional() {
        List<Float> vector = List.of(0.1f, 0.2f);

        assertThatThrownBy(() -> new CacheRetrievalResult(null, vector))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("candidate must not be null");
    }

    @Test
    @DisplayName("Should reject null query vector")
    void shouldRejectNullQueryVector() {
        assertThatThrownBy(() -> new CacheRetrievalResult(Optional.empty(), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("queryVector must not be null");
    }

    @Test
    @DisplayName("Should reject empty query vector")
    void shouldRejectEmptyQueryVector() {
        assertThatThrownBy(() -> new CacheRetrievalResult(Optional.empty(), Collections.emptyList()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("queryVector must not be empty");
    }

    @Test
    @DisplayName("Should reject null element in query vector")
    void shouldRejectNullElementInQueryVector() {
        List<Float> vector = new ArrayList<>();
        vector.add(0.1f);
        vector.add(null);

        assertThatThrownBy(() -> new CacheRetrievalResult(Optional.empty(), vector))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("queryVector element must not be null");
    }

    @Test
    @DisplayName("Should reject NaN element in query vector without exposing values")
    void shouldRejectNaNElementInQueryVector() {
        List<Float> vector = List.of(0.1f, Float.NaN, 0.3f);

        assertThatThrownBy(() -> new CacheRetrievalResult(Optional.empty(), vector))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("queryVector element must not be NaN or infinite");
    }

    @Test
    @DisplayName("Should reject positive and negative infinity in query vector")
    void shouldRejectInfinityInQueryVector() {
        List<Float> posInf = List.of(Float.POSITIVE_INFINITY, 0.2f);
        assertThatThrownBy(() -> new CacheRetrievalResult(Optional.empty(), posInf))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("queryVector element must not be NaN or infinite");

        List<Float> negInf = List.of(0.1f, Float.NEGATIVE_INFINITY);
        assertThatThrownBy(() -> new CacheRetrievalResult(Optional.empty(), negInf))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("queryVector element must not be NaN or infinite");
    }

    @Test
    @DisplayName("Should defensively copy query vector to prevent external modification")
    void shouldDefensivelyCopyQueryVector() {
        List<Float> mutableVector = new ArrayList<>(List.of(0.1f, 0.2f, 0.3f));
        CacheRetrievalResult result = new CacheRetrievalResult(Optional.empty(), mutableVector);

        mutableVector.set(0, 0.99f);
        assertThat(result.queryVector().get(0)).isEqualTo(0.1f);

        assertThatThrownBy(() -> result.queryVector().add(0.4f))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
