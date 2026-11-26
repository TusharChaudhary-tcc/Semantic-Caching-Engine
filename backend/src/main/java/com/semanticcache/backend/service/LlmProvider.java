package com.semanticcache.backend.service;

/**
 * Common abstraction for upstream LLM generation providers (e.g. Google Gemini, Groq Cloud).
 */
public interface LlmProvider {

    String generateAnswer(String prompt);

    default String getProviderName() {
        return "unknown";
    }

    default String getModelName() {
        return "unknown";
    }

    default boolean isAvailable() {
        return true;
    }
}
