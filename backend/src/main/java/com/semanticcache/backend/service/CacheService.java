package com.semanticcache.backend.service;

import com.semanticcache.backend.dto.CacheLookupResult;
import com.semanticcache.backend.dto.CacheRequest;
import com.semanticcache.backend.dto.CacheResponse;
import com.semanticcache.backend.dto.SearchFilter;
import com.semanticcache.backend.dto.SearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Optional;

@Service
public class CacheService {

    private static final Logger log = LoggerFactory.getLogger(CacheService.class);

    private final CacheRetrievalService cacheRetrievalService;
    private final CacheDecisionService cacheDecisionService;
    private final GeminiProvider geminiProvider;

    public CacheService(
            CacheRetrievalService cacheRetrievalService,
            CacheDecisionService cacheDecisionService,
            GeminiProvider geminiProvider
    ) {
        this.cacheRetrievalService = Objects.requireNonNull(cacheRetrievalService, "cacheRetrievalService must not be null");
        this.cacheDecisionService = Objects.requireNonNull(cacheDecisionService, "cacheDecisionService must not be null");
        this.geminiProvider = Objects.requireNonNull(geminiProvider, "geminiProvider must not be null");
    }

    /**
     * Processes an incoming prompt according to Privacy-First Policy (Option A).
     * 1. Retrieves candidates using SearchFilter.publicOnly() (anonymous caller context).
     * 2. Evaluates retrieved candidate through CacheDecisionService.
     * 3. On cache HIT: returns cached answer with cacheHit=true without invoking Gemini or persisting.
     * 4. On cache MISS: generates answer via GeminiProvider with cacheHit=false without persisting.
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

        log.debug("Initiating semantic cache retrieval for prompt with filter [{}]", anonymousFilter);
        Optional<SearchResult> candidate = cacheRetrievalService.findBestCandidate(prompt, anonymousFilter);
        if (candidate == null) {
            throw new IllegalStateException("CacheRetrievalService returned an unexpected null candidate Optional");
        }

        CacheLookupResult decision = cacheDecisionService.evaluate(candidate, anonymousFilter);
        if (decision == null) {
            throw new IllegalStateException("CacheDecisionService returned an unexpected null CacheLookupResult");
        }

        if (decision.isHit()) {
            if (decision.matchedResult() == null || decision.matchedResult().isEmpty()) {
                throw new IllegalStateException("Inconsistent CacheLookupResult: isHit is true but matchedResult is empty");
            }
            SearchResult match = decision.matchedResult().get();
            log.info("Semantic cache HIT for prompt with similarity score [{}]", decision.score());
            return new CacheResponse(match.answer(), true);
        }

        log.info("Semantic cache MISS for prompt (reason: [{}]). Delegating to Gemini provider.", decision.reason());
        String generatedAnswer = geminiProvider.generateAnswer(prompt);

        return new CacheResponse(generatedAnswer, false);
    }
}
