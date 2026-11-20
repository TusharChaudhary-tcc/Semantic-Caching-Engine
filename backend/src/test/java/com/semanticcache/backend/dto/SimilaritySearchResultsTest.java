package com.semanticcache.backend.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SimilaritySearchResultsTest {

    private SearchResult sampleResult() {
        return new SearchResult(
                UUID.randomUUID(),
                "sample prompt",
                "sample answer",
                "gemini-3.8-flash",
                java.time.Instant.now(),
                "gemini-embedding-001",
                0.91f,
                null,
                "PUBLIC"
        );
    }

    @Test
    @DisplayName("Should create SimilaritySearchResults successfully with results and vector")
    void shouldCreateWithResultsAndVector() {
        SearchResult match = sampleResult();
        List<SearchResult> results = List.of(match);
        List<Float> vector = List.of(0.1f, 0.2f, 0.3f);

        SimilaritySearchResults ssr = new SimilaritySearchResults(results, vector);

        assertThat(ssr.results()).containsExactly(match);
        assertThat(ssr.queryVector()).containsExactly(0.1f, 0.2f, 0.3f);
    }

    @Test
    @DisplayName("Should create with empty results list and valid vector")
    void shouldCreateWithEmptyResultsList() {
        List<Float> vector = List.of(0.5f, 0.6f);

        SimilaritySearchResults ssr = new SimilaritySearchResults(Collections.emptyList(), vector);

        assertThat(ssr.results()).isEmpty();
        assertThat(ssr.queryVector()).containsExactly(0.5f, 0.6f);
    }

    @Test
    @DisplayName("Should reject null results list")
    void shouldRejectNullResultsList() {
        List<Float> vector = List.of(0.1f);

        assertThatThrownBy(() -> new SimilaritySearchResults(null, vector))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("results must not be null");
    }

    @Test
    @DisplayName("Should reject null query vector")
    void shouldRejectNullQueryVector() {
        assertThatThrownBy(() -> new SimilaritySearchResults(Collections.emptyList(), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("queryVector must not be null");
    }

    @Test
    @DisplayName("Should reject empty query vector")
    void shouldRejectEmptyQueryVector() {
        assertThatThrownBy(() -> new SimilaritySearchResults(Collections.emptyList(), Collections.emptyList()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("queryVector must not be empty");
    }

    @Test
    @DisplayName("Should reject null element in query vector")
    void shouldRejectNullElementInQueryVector() {
        List<Float> vector = new ArrayList<>();
        vector.add(0.1f);
        vector.add(null);

        assertThatThrownBy(() -> new SimilaritySearchResults(Collections.emptyList(), vector))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("queryVector element must not be null");
    }

    @Test
    @DisplayName("Should reject NaN element in query vector without exposing values")
    void shouldRejectNaNElementInQueryVector() {
        List<Float> vector = List.of(0.1f, Float.NaN, 0.3f);

        assertThatThrownBy(() -> new SimilaritySearchResults(Collections.emptyList(), vector))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("queryVector element must not be NaN or infinite");
    }

    @Test
    @DisplayName("Should reject positive and negative infinity in query vector")
    void shouldRejectInfinityInQueryVector() {
        List<Float> posInf = List.of(Float.POSITIVE_INFINITY, 0.2f);
        assertThatThrownBy(() -> new SimilaritySearchResults(Collections.emptyList(), posInf))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("queryVector element must not be NaN or infinite");

        List<Float> negInf = List.of(0.1f, Float.NEGATIVE_INFINITY);
        assertThatThrownBy(() -> new SimilaritySearchResults(Collections.emptyList(), negInf))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("queryVector element must not be NaN or infinite");
    }

    @Test
    @DisplayName("Should defensively copy both results and queryVector")
    void shouldDefensivelyCopyCollections() {
        SearchResult match = sampleResult();
        List<SearchResult> mutableResults = new ArrayList<>(List.of(match));
        List<Float> mutableVector = new ArrayList<>(List.of(0.1f, 0.2f));

        SimilaritySearchResults ssr = new SimilaritySearchResults(mutableResults, mutableVector);

        mutableResults.clear();
        mutableVector.set(0, 0.88f);

        assertThat(ssr.results()).containsExactly(match);
        assertThat(ssr.queryVector().get(0)).isEqualTo(0.1f);

        assertThatThrownBy(() -> ssr.results().add(sampleResult()))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> ssr.queryVector().add(0.9f))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
