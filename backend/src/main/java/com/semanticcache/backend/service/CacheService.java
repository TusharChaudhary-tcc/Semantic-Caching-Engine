package com.semanticcache.backend.service;

import com.semanticcache.backend.dto.CacheRequest;
import com.semanticcache.backend.dto.CacheResponse;
import org.springframework.stereotype.Service;

@Service
public class CacheService {

    private final GeminiProvider geminiProvider;

    public CacheService(GeminiProvider geminiProvider) {
        this.geminiProvider = geminiProvider;
    }

    /**
     * Processes the incoming prompt.
     * In Task 5 (Gemini API Integration):
     * - Generates answer via Gemini.
     * - Returns CacheResponse with cacheHit=false because semantic caching is not yet active.
     */
    public CacheResponse processPrompt(CacheRequest request) {
        String answer = geminiProvider.generateAnswer(request.prompt());
        return new CacheResponse(answer, false);
    }
}
