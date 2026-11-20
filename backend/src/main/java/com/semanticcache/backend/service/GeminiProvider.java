package com.semanticcache.backend.service;

public interface GeminiProvider {

    /**
     * Generates an answer for the given prompt using Google Gemini.
     *
     * @param prompt The user's input prompt
     * @return Generated answer text from Gemini
     */
    String generateAnswer(String prompt);
}
