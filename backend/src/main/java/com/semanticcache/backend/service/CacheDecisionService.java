package com.semanticcache.backend.service;

import com.semanticcache.backend.dto.CacheLookupResult;
import com.semanticcache.backend.dto.SearchFilter;
import com.semanticcache.backend.dto.SearchResult;

import java.util.Optional;

/**
 * Service responsible for evaluating whether a retrieved candidate qualifies as a semantic cache hit
 * based on privacy authorization and configurable similarity threshold criteria.
 */
public interface CacheDecisionService {

    /**
     * Evaluates a candidate search result against privacy boundaries and the configured similarity threshold.
     *
     * @param candidate the retrieved candidate search result, or empty if none found
     * @param filter the trusted search filter representing the caller's context
     * @return an immutable CacheLookupResult indicating HIT or MISS with diagnostic reason
     * @throws NullPointerException if candidate or filter is null
     */
    CacheLookupResult evaluate(Optional<SearchResult> candidate, SearchFilter filter);
}
