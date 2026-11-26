package com.semanticcache.backend.service;

import com.semanticcache.backend.config.GroqConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Objects;

/**
 * Routes cache-miss LLM generation requests between configured providers (Gemini and Groq).
 */
@Service
public class LlmRouterService {

    private final GeminiProvider geminiProvider;
    private final GroqService groqService;
    private final GroqConfig groqConfig;

    @Autowired
    public LlmRouterService(
            GeminiProvider geminiProvider,
            @Autowired(required = false) GroqService groqService,
            @Autowired(required = false) GroqConfig groqConfig
    ) {
        this.geminiProvider = Objects.requireNonNull(geminiProvider, "geminiProvider must not be null");
        this.groqService = groqService;
        this.groqConfig = groqConfig;
    }

    public LlmProvider resolve(String requestedProvider) {
        String target = (requestedProvider != null && !requestedProvider.isBlank())
                ? requestedProvider.trim().toLowerCase(Locale.ROOT)
                : (groqConfig != null ? groqConfig.getDefaultProvider() : "gemini");

        if ("groq".equals(target)) {
            if (groqService != null && groqService.isAvailable()) {
                return groqService;
            }
            // If explicitly requested "groq", let GroqService throw clear missing-credentials error
            if (requestedProvider != null && !requestedProvider.isBlank() && groqService != null) {
                return groqService;
            }
            // Otherwise gracefully fall back to Gemini
            return geminiProvider;
        }
        return geminiProvider;
    }
}
