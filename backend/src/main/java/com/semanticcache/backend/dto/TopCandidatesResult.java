package com.semanticcache.backend.dto;

import java.util.Collections;
import java.util.List;

/**
 * Immutable domain record holding Stage-1 top-K vector search candidates alongside the query embedding vector.
 */
public record TopCandidatesResult(
        List<SearchResult> candidates,
        List<Float> queryVector
) {
    public TopCandidatesResult {
        candidates = candidates != null ? List.copyOf(candidates) : Collections.emptyList();
        queryVector = queryVector != null ? List.copyOf(queryVector) : Collections.emptyList();
    }
}
