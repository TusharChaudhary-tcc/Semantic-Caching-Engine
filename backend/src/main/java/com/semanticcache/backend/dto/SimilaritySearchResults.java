package com.semanticcache.backend.dto;

import java.util.List;
import java.util.Objects;

/**
 * Immutable carrier holding similarity search results along with the
 * exact query embedding vector used during the vector database search.
 */
public record SimilaritySearchResults(
        List<SearchResult> results,
        List<Float> queryVector
) {
    public SimilaritySearchResults {
        Objects.requireNonNull(results, "results must not be null");
        Objects.requireNonNull(queryVector, "queryVector must not be null");
        if (queryVector.isEmpty()) {
            throw new IllegalArgumentException("queryVector must not be empty");
        }
        for (Float val : queryVector) {
            if (val == null) {
                throw new IllegalArgumentException("queryVector element must not be null");
            }
            if (val.isNaN() || val.isInfinite()) {
                throw new IllegalArgumentException("queryVector element must not be NaN or infinite");
            }
        }
        results = List.copyOf(results);
        queryVector = List.copyOf(queryVector);
    }
}
