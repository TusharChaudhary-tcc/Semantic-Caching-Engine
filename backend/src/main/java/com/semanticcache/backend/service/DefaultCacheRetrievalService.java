package com.semanticcache.backend.service;

import com.semanticcache.backend.dto.SearchFilter;
import com.semanticcache.backend.dto.SearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Service
public class DefaultCacheRetrievalService implements CacheRetrievalService {

    private static final Logger log = LoggerFactory.getLogger(DefaultCacheRetrievalService.class);

    private final SimilaritySearchService similaritySearchService;

    public DefaultCacheRetrievalService(SimilaritySearchService similaritySearchService) {
        this.similaritySearchService = Objects.requireNonNull(similaritySearchService, "similaritySearchService must not be null");
    }

    @Override
    public Optional<SearchResult> findBestCandidate(String query) {
        return findBestCandidate(query, SearchFilter.publicOnly());
    }

    @Override
    public Optional<SearchResult> findBestCandidate(String query, SearchFilter filter) {
        if (query == null || query.trim().isEmpty()) {
            throw new IllegalArgumentException("query must not be null or blank");
        }
        Objects.requireNonNull(filter, "filter must not be null");

        log.debug("Retrieving best cache candidate with filter [{}]", filter);

        List<SearchResult> candidates = similaritySearchService.search(query.trim(), 1, filter);

        if (candidates == null || candidates.isEmpty()) {
            log.debug("No cache candidate found for query with filter [{}]", filter);
            return Optional.empty();
        }

        SearchResult best = candidates.get(0);
        log.debug("Found candidate with ID [{}] and score [{}]", best.id(), best.score());
        return Optional.of(best);
    }
}
