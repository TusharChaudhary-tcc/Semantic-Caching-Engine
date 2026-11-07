package com.semanticcache.backend.service;

import com.semanticcache.backend.dto.EmbeddingResponse;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

@Service
public class EmbeddingService {

    private final EmbeddingProvider embeddingProvider;

    public EmbeddingService(EmbeddingProvider embeddingProvider) {
        this.embeddingProvider = Objects.requireNonNull(embeddingProvider, "embeddingProvider must not be null");
    }

    /**
     * Generates an embedding response containing the vector and its dimension.
     *
     * @param text the input text to embed
     * @return an EmbeddingResponse containing the immutable vector and dimension
     */
    public EmbeddingResponse getEmbedding(String text) {
        List<Float> vector = embeddingProvider.generateEmbedding(text);
        return new EmbeddingResponse(vector, vector.size());
    }
}
