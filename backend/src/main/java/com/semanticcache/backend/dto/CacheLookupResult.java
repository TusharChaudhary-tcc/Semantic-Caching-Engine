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
        String reason,
        Optional<Float> crossEncoderScore
) {
    public CacheLookupResult {
        Objects.requireNonNull(matchedResult, "matchedResult must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(crossEncoderScore, "crossEncoderScore must not be null");
        if (!Float.isFinite(score)) {
            throw new IllegalArgumentException("score must be a finite numerical value");
        }
    }

    public CacheLookupResult(
            boolean isHit,
            Optional<SearchResult> matchedResult,
            float score,
            String reason
    ) {
        this(isHit, matchedResult, score, reason, Optional.empty());
    }

    public static CacheLookupResult hit(SearchResult result) {
        Objects.requireNonNull(result, "result must not be null");
        return new CacheLookupResult(true, Optional.of(result), result.score(), "MATCH_EXCEEDS_SIMILARITY_THRESHOLD", Optional.empty());
    }

    public static CacheLookupResult hitWithCrossEncoder(SearchResult result, float crossEncoderScore) {
        Objects.requireNonNull(result, "result must not be null");
        return new CacheLookupResult(true, Optional.of(result), result.score(), "CROSS_ENCODER_VERIFIED", Optional.of(crossEncoderScore));
    }

    public static CacheLookupResult missNoCandidate() {
        return new CacheLookupResult(false, Optional.empty(), 0.0f, "NO_CANDIDATE_FOUND", Optional.empty());
    }

    public static CacheLookupResult missBelowThreshold(SearchResult candidate) {
        Objects.requireNonNull(candidate, "candidate must not be null");
        return new CacheLookupResult(false, Optional.of(candidate), candidate.score(), "SCORE_BELOW_SIMILARITY_THRESHOLD", Optional.empty());
    }

    public static CacheLookupResult missBelowCrossEncoderThreshold(SearchResult candidate, float crossEncoderScore) {
        Objects.requireNonNull(candidate, "candidate must not be null");
        return new CacheLookupResult(false, Optional.of(candidate), candidate.score(), "SCORE_BELOW_CROSS_ENCODER_THRESHOLD", Optional.of(crossEncoderScore));
    }

    public static CacheLookupResult missPrivacyRestriction(float score, String reason) {
        return new CacheLookupResult(false, Optional.empty(), score, reason, Optional.empty());
    }
}
