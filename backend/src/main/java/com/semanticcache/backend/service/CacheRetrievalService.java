package com.semanticcache.backend.service;

import com.semanticcache.backend.dto.CacheRetrievalResult;
import com.semanticcache.backend.dto.SearchFilter;
import com.semanticcache.backend.dto.SearchResult;
import com.semanticcache.backend.dto.TopCandidatesResult;

import java.util.List;
import java.util.Optional;

/**
 * Service responsible for retrieving semantically similar cached candidate entries
 * using privacy-filtered similarity search.
 */
public interface CacheRetrievalService {

    /**
     * Retrieves the top semantically similar candidate using the safe public-only filter.
     *
     * @param query the natural-language prompt
     * @return an Optional containing the top candidate SearchResult, or empty if none found
     */
    Optional<SearchResult> findBestCandidate(String query);

    /**
     * Retrieves the top semantically similar candidate using a trusted caller search filter.
     *
     * @param query the natural-language prompt
     * @param filter trusted privacy and visibility filter
     * @return an Optional containing the top candidate SearchResult, or empty if none found
     */
    Optional<SearchResult> findBestCandidate(String query, SearchFilter filter);

    /**
     * Retrieves the top semantically similar candidate along with the generated query vector
     * using a trusted caller search filter.
     *
     * @param query the natural-language prompt
     * @param filter trusted privacy and visibility filter
     * @return CacheRetrievalResult containing optional top candidate and the query vector
     */
    CacheRetrievalResult findBestCandidateWithVector(String query, SearchFilter filter);

    /**
     * Retrieves up to {@code limit} semantically similar candidates along with the generated query vector
     * for Stage-2 cross-encoder re-ranking.
     */
    default TopCandidatesResult findTopCandidatesWithVector(String query, int limit, SearchFilter filter) {
        CacheRetrievalResult single = findBestCandidateWithVector(query, filter);
        List<SearchResult> list = single.candidate().map(List::of).orElse(List.of());
        return new TopCandidatesResult(list, single.queryVector());
    }
}
