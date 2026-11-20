package com.semanticcache.backend.service;

import java.util.List;

public interface EmbeddingProvider {

    /**
     * Generates a semantic vector embedding for the given input text.
     *
     * @param text the input text to embed; must not be null or blank
     * @return an immutable list of floating-point values representing the embedding vector
     */
    List<Float> generateEmbedding(String text);
}
