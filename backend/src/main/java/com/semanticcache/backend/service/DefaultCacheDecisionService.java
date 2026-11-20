package com.semanticcache.backend.service;

import com.semanticcache.backend.config.CacheConfig;
import com.semanticcache.backend.dto.CacheLookupResult;
import com.semanticcache.backend.dto.SearchFilter;
import com.semanticcache.backend.dto.SearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Optional;

@Service
public class DefaultCacheDecisionService implements CacheDecisionService {

    private static final Logger log = LoggerFactory.getLogger(DefaultCacheDecisionService.class);

    private final CacheConfig cacheConfig;

    public DefaultCacheDecisionService(CacheConfig cacheConfig) {
        this.cacheConfig = Objects.requireNonNull(cacheConfig, "cacheConfig must not be null");
    }

    @Override
    public CacheLookupResult evaluate(Optional<SearchResult> candidate, SearchFilter filter) {
        Objects.requireNonNull(candidate, "candidate must not be null");
        Objects.requireNonNull(filter, "filter must not be null");

        if (candidate.isEmpty()) {
            log.debug("Cache lookup decision: MISS (no candidate found)");
            return CacheLookupResult.missNoCandidate();
        }

        SearchResult result = candidate.get();

        // Defensive validation against non-finite scores
        if (!Float.isFinite(result.score())) {
            log.warn("Candidate has non-finite score. Treating as cache miss.");
            return CacheLookupResult.missBelowThreshold(result);
        }

        // Privacy authorization check (defense-in-depth)
        if (filter.isPublicOnly()) {
            if (!"PUBLIC".equals(result.visibility())) {
                log.debug("Cache lookup decision: MISS (candidate with visibility [{}] is not public for anonymous caller)",
                        result.visibility());
                return CacheLookupResult.missPrivacyRestriction(result.score(), "CANDIDATE_NOT_PUBLIC");
            }
        } else if (filter.hasOwner()) {
            boolean isPublic = "PUBLIC".equals(result.visibility());
            boolean isOwnedPrivate = "PRIVATE".equals(result.visibility()) && filter.ownerId().equals(result.ownerId());

            if (!isPublic && !isOwnedPrivate) {
                log.debug("Cache lookup decision: MISS (ownership mismatch: candidate owner [{}] does not match filter owner [{}])",
                        result.ownerId(), filter.ownerId());
                return CacheLookupResult.missPrivacyRestriction(result.score(), "OWNERSHIP_MISMATCH");
            }
        } else {
            log.warn("Unsupported filter state encountered. Rejecting candidate for safety.");
            return CacheLookupResult.missPrivacyRestriction(result.score(), "UNSUPPORTED_FILTER_STATE");
        }

        // Threshold evaluation (performed only after privacy checks pass)
        float threshold = cacheConfig.getSimilarityThreshold();
        if (result.score() >= threshold) {
            log.debug("Cache lookup decision: HIT (score [{}] satisfies threshold [{}])", result.score(), threshold);
            return CacheLookupResult.hit(result);
        } else {
            log.debug("Cache lookup decision: MISS (score [{}] below threshold [{}])", result.score(), threshold);
            return CacheLookupResult.missBelowThreshold(result);
        }
    }
}
