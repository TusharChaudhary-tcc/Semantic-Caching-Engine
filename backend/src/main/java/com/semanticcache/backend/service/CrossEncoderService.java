package com.semanticcache.backend.service;

/**
 * Stage-2 Cross-Encoder intent verification service.
 * Evaluates a (query, candidatePrompt) pair jointly to produce a normalized semantic match probability in [0.0, 1.0].
 */
public interface CrossEncoderService {

    /**
     * Computes the intent-alignment probability between the incoming query and a cached candidate prompt.
     *
     * @param query           the incoming user prompt
     * @param candidatePrompt the cached prompt from Stage-1 retrieval
     * @return relevance probability in [0.0, 1.0]
     */
    float score(String query, String candidatePrompt);

    /**
     * Indicates whether the cross-encoder runtime and model weights are loaded and ready.
     */
    boolean isAvailable();
}
