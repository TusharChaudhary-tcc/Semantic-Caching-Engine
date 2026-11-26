package com.semanticcache.backend.service;

import com.semanticcache.backend.config.CacheConfig;
import com.semanticcache.backend.config.CrossEncoderConfig;
import com.semanticcache.backend.dto.CacheLookupResult;
import com.semanticcache.backend.dto.SearchFilter;
import com.semanticcache.backend.dto.SearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Service
public class DefaultCacheDecisionService implements CacheDecisionService {

    private static final Logger log = LoggerFactory.getLogger(DefaultCacheDecisionService.class);

    private final CacheConfig cacheConfig;
    private final CrossEncoderService crossEncoderService;
    private final CrossEncoderConfig crossEncoderConfig;

    public DefaultCacheDecisionService(CacheConfig cacheConfig) {
        this(cacheConfig, null, null);
    }

    @Autowired
    public DefaultCacheDecisionService(
            CacheConfig cacheConfig,
            @Autowired(required = false) CrossEncoderService crossEncoderService,
            @Autowired(required = false) CrossEncoderConfig crossEncoderConfig
    ) {
        this.cacheConfig = Objects.requireNonNull(cacheConfig, "cacheConfig must not be null");
        this.crossEncoderService = crossEncoderService;
        this.crossEncoderConfig = crossEncoderConfig;
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
        CacheLookupResult privacyFailure = checkPrivacy(result, filter);
        if (privacyFailure != null) {
            return privacyFailure;
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

    @Override
    public CacheLookupResult evaluateCandidates(String query, List<SearchResult> candidates, SearchFilter filter) {
        Objects.requireNonNull(filter, "filter must not be null");
        if (candidates == null || candidates.isEmpty()) {
            log.debug("Cache lookup decision: MISS (no candidates found)");
            return CacheLookupResult.missNoCandidate();
        }

        boolean crossEncoderActive = crossEncoderService != null
                && crossEncoderService.isAvailable()
                && (crossEncoderConfig == null || crossEncoderConfig.isEnabled());

        if (!crossEncoderActive) {
            return evaluate(Optional.of(candidates.get(0)), filter);
        }

        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query must not be null or blank");
        }

        float stage1Threshold = crossEncoderConfig != null
                ? crossEncoderConfig.getStage1Threshold()
                : cacheConfig.getSimilarityThreshold();
        float stage2Threshold = crossEncoderConfig != null
                ? crossEncoderConfig.getThreshold()
                : 0.85f;
        int topK = crossEncoderConfig != null ? crossEncoderConfig.getTopK() : 3;

        SearchResult bestAuthorizedCandidate = null;
        CacheLookupResult firstPrivacyFailure = null;

        SearchResult bestVerifiedCandidate = null;
        float bestCrossEncoderScore = Float.NEGATIVE_INFINITY;

        int evaluated = 0;
        for (SearchResult candidate : candidates) {
            if (candidate == null || !Float.isFinite(candidate.score())) {
                continue;
            }
            CacheLookupResult privacyFailure = checkPrivacy(candidate, filter);
            if (privacyFailure != null) {
                if (firstPrivacyFailure == null) {
                    firstPrivacyFailure = privacyFailure;
                }
                continue;
            }
            if (bestAuthorizedCandidate == null) {
                bestAuthorizedCandidate = candidate;
            }
            if (candidate.score() < stage1Threshold) {
                continue;
            }
            if (evaluated >= topK) {
                break;
            }
            evaluated++;

            float ceScore = crossEncoderService.score(query.trim(), candidate.prompt());
            log.debug("Stage-2 Cross-Encoder scored candidate [{}] (stage1={}, stage2={})",
                    candidate.id(), candidate.score(), ceScore);

            if (ceScore > bestCrossEncoderScore) {
                bestCrossEncoderScore = ceScore;
                bestVerifiedCandidate = candidate;
            }
        }

        if (bestAuthorizedCandidate == null) {
            return firstPrivacyFailure != null ? firstPrivacyFailure : CacheLookupResult.missNoCandidate();
        }

        if (bestVerifiedCandidate == null) {
            log.debug("Stage-1 cutoff rejected all authorized candidates (top score={} < stage1Threshold={})",
                    bestAuthorizedCandidate.score(), stage1Threshold);
            return CacheLookupResult.missBelowThreshold(bestAuthorizedCandidate);
        }

        if (bestCrossEncoderScore >= stage2Threshold) {
            log.debug("Stage-2 Cross-Encoder HIT: stage1={} stage2={} (threshold={})",
                    bestVerifiedCandidate.score(), bestCrossEncoderScore, stage2Threshold);
            return CacheLookupResult.hitWithCrossEncoder(bestVerifiedCandidate, bestCrossEncoderScore);
        }

        log.debug("Stage-2 Cross-Encoder MISS: best stage2={} < threshold={}",
                bestCrossEncoderScore, stage2Threshold);
        return CacheLookupResult.missBelowCrossEncoderThreshold(bestVerifiedCandidate, bestCrossEncoderScore);
    }

    private CacheLookupResult checkPrivacy(SearchResult result, SearchFilter filter) {
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
        return null;
    }
}
