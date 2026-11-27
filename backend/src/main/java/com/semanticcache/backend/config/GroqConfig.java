package com.semanticcache.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration properties for Groq Cloud LPU inference and multi-LLM routing (Task 2.3).
 */
@Configuration
public class GroqConfig {

    private final String apiKey;
    private final String model;
    private final String baseUrl;
    private final int timeoutSeconds;
    private final String defaultProvider;

    public GroqConfig(
            @Value("${groq.api-key:}") String apiKey,
            @Value("${groq.model:openai/gpt-oss-20b}") String model,
            @Value("${groq.base-url:https://api.groq.com/openai/v1/chat/completions}") String baseUrl,
            @Value("${groq.timeout-seconds:30}") int timeoutSeconds,
            @Value("${llm.default-provider:gemini}") String defaultProvider
    ) {
        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException("groq.timeout-seconds must be positive");
        }
        this.apiKey = apiKey != null ? apiKey.trim() : "";
        this.model = (model != null && !model.isBlank()) ? model.trim() : "llama-3.3-70b-versatile";
        this.baseUrl = (baseUrl != null && !baseUrl.isBlank())
                ? baseUrl.trim()
                : "https://api.groq.com/openai/v1/chat/completions";
        this.timeoutSeconds = timeoutSeconds;
        this.defaultProvider = (defaultProvider != null && !defaultProvider.isBlank())
                ? defaultProvider.trim().toLowerCase()
                : "gemini";
    }

    public String getApiKey() {
        return apiKey;
    }

    public String getModel() {
        return model;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public String getDefaultProvider() {
        return defaultProvider;
    }
}
