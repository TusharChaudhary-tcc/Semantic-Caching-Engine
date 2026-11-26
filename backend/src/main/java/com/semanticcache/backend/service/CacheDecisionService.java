package com.semanticcache.backend.service;

import com.semanticcache.backend.dto.CacheLookupResult;
import com.semanticcache.backend.dto.SearchFilter;
import com.semanticcache.backend.dto.SearchResult;

import java.util.List;
import java.util.Optional;

/**
 * Service responsible for evaluating whether retrieved candidates qualify as a semantic cache hit
 * based on privacy authorization, Stage-1 vector similarity, and optional Stage-2 Cross-Encoder intent verification.
 */
public interface CacheDecisionService {

    /**
     * Evaluates a single candidate search result against privacy boundaries and the configured similarity threshold.
     *
     * @param candidate the retrieved candidate search result, or empty if none found
     * @param filter    the trusted search filter representing the caller's context
     * @return an immutable CacheLookupResult indicating HIT or MISS with diagnostic reason
     * @throws NullPointerException if candidate or filter is null
     */
    CacheLookupResult evaluate(Optional<SearchResult> candidate, SearchFilter filter);

    /**
     * Evaluates top-K Stage-1 candidates with privacy checks and optional Stage-2 Cross-Encoder re-ranking.
     *
     * @param query      the incoming user prompt
     * @param candidates Stage-1 candidates ordered by vector similarity
     * @param filter     the trusted search filter representing the caller's context
     * @return an immutable CacheLookupResult indicating HIT or MISS with Stage-1 and Stage-2 diagnostics
     */
    default CacheLookupResult evaluateCandidates(String query, List<SearchResult> candidates, SearchFilter filter) {
        Optional<SearchResult> best = (candidates == null || candidates.isEmpty())
                ? Optional.empty()
                : Optional.of(candidates.get(0));
        return evaluate(best, filter);
    }
}
