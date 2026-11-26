package com.semanticcache.backend.service;

public interface GeminiProvider extends LlmProvider {

    /**
     * Generates an answer for the given prompt using Google Gemini.
     *
     * @param prompt The user's input prompt
     * @return Generated answer text from Gemini
     */
    @Override
    String generateAnswer(String prompt);

    @Override
    default String getProviderName() {
        return "gemini";
    }
}
