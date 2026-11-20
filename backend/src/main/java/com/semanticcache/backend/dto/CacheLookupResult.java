package com.semanticcache.backend.dto;

import java.util.Objects;
import java.util.Optional;

/**
 * Immutable domain record representing the outcome of a semantic cache lookup and decision evaluation.
 */
public record CacheLookupResult(
        boolean isHit,
        Optional<SearchResult> matchedResult,
        float score,
        String reason
) {
    public CacheLookupResult {
        Objects.requireNonNull(matchedResult, "matchedResult must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        if (!Float.isFinite(score)) {
            throw new IllegalArgumentException("score must be a finite numerical value");
        }
    }

    public static CacheLookupResult hit(SearchResult result) {
        Objects.requireNonNull(result, "result must not be null");
        return new CacheLookupResult(true, Optional.of(result), result.score(), "MATCH_EXCEEDS_SIMILARITY_THRESHOLD");
    }

    public static CacheLookupResult missNoCandidate() {
        return new CacheLookupResult(false, Optional.empty(), 0.0f, "NO_CANDIDATE_FOUND");
    }

    public static CacheLookupResult missBelowThreshold(SearchResult candidate) {
        Objects.requireNonNull(candidate, "candidate must not be null");
        return new CacheLookupResult(false, Optional.of(candidate), candidate.score(), "SCORE_BELOW_SIMILARITY_THRESHOLD");
    }

    public static CacheLookupResult missPrivacyRestriction(float score, String reason) {
        return new CacheLookupResult(false, Optional.empty(), score, reason);
    }
}
