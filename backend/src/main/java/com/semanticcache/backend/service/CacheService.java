package com.semanticcache.backend.service;

import com.semanticcache.backend.dto.CacheLookupResult;
import com.semanticcache.backend.dto.CacheRequest;
import com.semanticcache.backend.dto.CacheResponse;
import com.semanticcache.backend.dto.CacheRetrievalResult;
import com.semanticcache.backend.dto.SearchFilter;
import com.semanticcache.backend.dto.SearchResult;
import com.semanticcache.backend.dto.VectorRecord;
import com.semanticcache.backend.repository.VectorRepository;
import com.semanticcache.backend.telemetry.CacheMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class CacheService {

    private static final Logger log = LoggerFactory.getLogger(CacheService.class);

    private final CacheRetrievalService cacheRetrievalService;
    private final CacheDecisionService cacheDecisionService;
    private final GeminiProvider geminiProvider;
    private final VectorRepository vectorRepository;
    private final CacheMetrics cacheMetrics;
    private final String geminiModel;
    private final String embeddingModel;

    @Autowired
    public CacheService(
            CacheRetrievalService cacheRetrievalService,
            CacheDecisionService cacheDecisionService,
            GeminiProvider geminiProvider,
            VectorRepository vectorRepository,
            CacheMetrics cacheMetrics,
            @Value("${gemini.model:gemini-3.8-flash}") String geminiModel,
            @Value("${gemini.embedding-model:gemini-embedding-001}") String embeddingModel
    ) {
        this.cacheRetrievalService = Objects.requireNonNull(cacheRetrievalService, "cacheRetrievalService must not be null");
        this.cacheDecisionService = Objects.requireNonNull(cacheDecisionService, "cacheDecisionService must not be null");
        this.geminiProvider = Objects.requireNonNull(geminiProvider, "geminiProvider must not be null");
        this.vectorRepository = vectorRepository;
        this.cacheMetrics = Objects.requireNonNull(cacheMetrics, "cacheMetrics must not be null");
        this.geminiModel = (geminiModel != null && !geminiModel.isBlank()) ? geminiModel.trim() : "gemini-3.8-flash";
        this.embeddingModel = (embeddingModel != null && !embeddingModel.isBlank()) ? embeddingModel.trim() : "gemini-embedding-001";
    }

    public CacheService(
            CacheRetrievalService cacheRetrievalService,
            CacheDecisionService cacheDecisionService,
            GeminiProvider geminiProvider,
            VectorRepository vectorRepository,
            CacheMetrics cacheMetrics
    ) {
        this(cacheRetrievalService, cacheDecisionService, geminiProvider, vectorRepository, cacheMetrics, "gemini-3.8-flash", "gemini-embedding-001");
    }

    public CacheService(
            CacheRetrievalService cacheRetrievalService,
            CacheDecisionService cacheDecisionService,
            GeminiProvider geminiProvider,
            CacheMetrics cacheMetrics
    ) {
        this(cacheRetrievalService, cacheDecisionService, geminiProvider, null, cacheMetrics, "gemini-3.8-flash", "gemini-embedding-001");
    }

    /**
     * Processes an incoming prompt according to Privacy-First Policy (Option A).
     * 1. Retrieves candidates using SearchFilter.publicOnly() (anonymous caller context).
     * 2. Evaluates retrieved candidate through CacheDecisionService.
     * 3. On cache HIT: returns cached answer with cacheHit=true without invoking Gemini or persisting.
     * 4. On cache MISS: generates answer via GeminiProvider and writes back to vector store with cacheHit=false.
     *
     * @param request the incoming CacheRequest containing the prompt
     * @return CacheResponse with answer and cacheHit flag
     * @throws IllegalArgumentException if request or prompt is null/blank
     * @throws IllegalStateException if retrieval/decision service returns unexpected null or inconsistent state
     */
    public CacheResponse processPrompt(CacheRequest request) {
        if (request == null || request.prompt() == null || request.prompt().trim().isEmpty()) {
            throw new IllegalArgumentException("Prompt must not be null or blank");
        }

        String prompt = request.prompt().trim();
        SearchFilter anonymousFilter = SearchFilter.publicOnly();
        long startNanos = System.nanoTime();

        Optional<SearchResult> candidate;
        List<Float> queryVector;
        CacheLookupResult decision;
        try {
            log.debug("Initiating semantic cache retrieval for prompt with filter [{}]", anonymousFilter);
            CacheRetrievalResult retrieval = cacheRetrievalService.findBestCandidateWithVector(prompt, anonymousFilter);
            if (retrieval == null) {
                throw new IllegalStateException("CacheRetrievalService returned an unexpected null CacheRetrievalResult");
            }
            candidate = retrieval.candidate();
            queryVector = retrieval.queryVector();

            if (candidate == null) {
                throw new IllegalStateException("CacheRetrievalService returned an unexpected null candidate Optional");
            }

            decision = cacheDecisionService.evaluate(candidate, anonymousFilter);
            if (decision == null) {
                throw new IllegalStateException("CacheDecisionService returned an unexpected null CacheLookupResult");
            }
        } catch (Exception e) {
            Duration duration = Duration.ofNanos(System.nanoTime() - startNanos);
            cacheMetrics.recordCacheError(duration);
            throw e;
        }

        if (decision.isHit()) {
            if (decision.matchedResult() == null || decision.matchedResult().isEmpty()) {
                Duration duration = Duration.ofNanos(System.nanoTime() - startNanos);
                cacheMetrics.recordCacheError(duration);
                throw new IllegalStateException("Inconsistent CacheLookupResult: isHit is true but matchedResult is empty");
            }
            SearchResult match = decision.matchedResult().get();
            Duration duration = Duration.ofNanos(System.nanoTime() - startNanos);
            cacheMetrics.recordSimilarityScore(decision.score(), CacheMetrics.CacheOutcome.HIT);
            cacheMetrics.recordCacheHit(match.visibility(), duration);
            log.info("Semantic cache HIT for prompt with similarity score [{}]", decision.score());
            return new CacheResponse(match.answer(), true);
        }

        if (candidate.isPresent()) {
            cacheMetrics.recordSimilarityScore(decision.score(), CacheMetrics.CacheOutcome.MISS);
        }

        CacheMetrics.MissReason missReason = resolveMissReason(decision);
        cacheMetrics.recordCacheMiss(missReason);

        log.info("Semantic cache MISS for prompt (reason: [{}]). Delegating to Gemini provider.", decision.reason());
        String generatedAnswer;
        try {
            generatedAnswer = geminiProvider.generateAnswer(prompt);
        } catch (Exception e) {
            Duration duration = Duration.ofNanos(System.nanoTime() - startNanos);
            cacheMetrics.recordCacheError(duration);
            throw e;
        }

        if (vectorRepository != null && queryVector != null && !queryVector.isEmpty()) {
            try {
                VectorRecord newRecord = new VectorRecord(
                        UUID.randomUUID(),
                        queryVector,
                        prompt,
                        generatedAnswer,
                        geminiModel,
                        Instant.now(),
                        embeddingModel
                );
                vectorRepository.upsert(newRecord);
                log.debug("Successfully persisted newly generated response to semantic cache for prompt");
            } catch (Exception e) {
                log.warn("Failed to persist entry to Qdrant vector repository: exception class [{}]", e.getClass().getName());
            }
        }

        Duration duration = Duration.ofNanos(System.nanoTime() - startNanos);
        cacheMetrics.recordCacheMissCompleted(duration);
        return new CacheResponse(generatedAnswer, false);
    }

    private CacheMetrics.MissReason resolveMissReason(CacheLookupResult decision) {
        String reason = decision.reason();
        if (reason == null) {
            return CacheMetrics.MissReason.NO_CANDIDATE;
        }

        String normalized = reason.trim().toUpperCase(Locale.ROOT);
        switch (normalized) {
            case "SCORE_BELOW_SIMILARITY_THRESHOLD":
                return CacheMetrics.MissReason.BELOW_THRESHOLD;
            case "CANDIDATE_NOT_PUBLIC":
            case "OWNERSHIP_MISMATCH":
            case "UNSUPPORTED_FILTER_STATE":
            case "PRIVACY_FILTER_RESTRICTION":
                return CacheMetrics.MissReason.PRIVACY_RESTRICTION;
            case "NO_CANDIDATE_FOUND":
            default:
                return CacheMetrics.MissReason.NO_CANDIDATE;
        }
    }
}
