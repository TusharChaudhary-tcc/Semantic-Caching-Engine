package com.semanticcache.backend.dto;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable carrier holding the result of a cache retrieval operation,
 * pairing an optional candidate SearchResult with the exact query vector
 * generated during the search stage to enable zero-redundancy write-back.
 */
public record CacheRetrievalResult(
        Optional<SearchResult> candidate,
        List<Float> queryVector
) {
    public CacheRetrievalResult {
        Objects.requireNonNull(candidate, "candidate must not be null");
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
        queryVector = List.copyOf(queryVector);
    }

    public static CacheRetrievalResult of(Optional<SearchResult> candidate, List<Float> queryVector) {
        return new CacheRetrievalResult(candidate, queryVector);
    }
}
