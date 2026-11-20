package com.semanticcache.backend.dto;

import java.util.List;

public record EmbeddingResponse(
        List<Float> embedding,
        int dimension
) {
    public EmbeddingResponse {
        if (embedding == null) {
            throw new IllegalArgumentException("Embedding vector must not be null");
        }
        if (embedding.isEmpty()) {
            throw new IllegalArgumentException("Embedding vector must not be empty");
        }
        if (dimension <= 0) {
            throw new IllegalArgumentException("Dimension must be greater than zero. Provided: " + dimension);
        }
        if (dimension != embedding.size()) {
            throw new IllegalArgumentException(
                    "Dimension [" + dimension + "] does not match embedding size [" + embedding.size() + "]"
            );
        }
        embedding = List.copyOf(embedding);
    }
}
