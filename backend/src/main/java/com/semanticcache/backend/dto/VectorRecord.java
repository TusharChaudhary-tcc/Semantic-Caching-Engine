package com.semanticcache.backend.dto;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record VectorRecord(
        UUID id,
        List<Float> vector,
        String prompt,
        String answer,
        String model,
        Instant createdAt,
        String embeddingModel
) {
    public VectorRecord {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(vector, "vector must not be null");
        if (vector.isEmpty()) {
            throw new IllegalArgumentException("vector must not be empty");
        }
        for (Float val : vector) {
            if (val == null) {
                throw new IllegalArgumentException("vector elements must not be null");
            }
            if (!Float.isFinite(val)) {
                throw new IllegalArgumentException("vector elements must be finite numerical values: " + val);
            }
        }
        if (prompt == null || prompt.trim().isEmpty()) {
            throw new IllegalArgumentException("prompt must not be null or blank");
        }
        if (answer == null || answer.trim().isEmpty()) {
            throw new IllegalArgumentException("answer must not be null or blank");
        }
        if (model == null || model.trim().isEmpty()) {
            throw new IllegalArgumentException("model must not be null or blank");
        }
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        if (embeddingModel == null || embeddingModel.trim().isEmpty()) {
            throw new IllegalArgumentException("embeddingModel must not be null or blank");
        }

        vector = List.copyOf(vector);
        prompt = prompt.trim();
        answer = answer.trim();
        model = model.trim();
        embeddingModel = embeddingModel.trim();
    }

    /**
     * Overloaded constructor validating the vector against a dynamically supplied expected dimension.
     */
    public VectorRecord(
            UUID id,
            List<Float> vector,
            String prompt,
            String answer,
            String model,
            Instant createdAt,
            String embeddingModel,
            int expectedDimension
    ) {
        this(id, vector, prompt, answer, model, createdAt, embeddingModel);
        validateDimension(expectedDimension);
    }

    /**
     * Factory method creating a VectorRecord with expected dimension validation.
     */
    public static VectorRecord of(
            UUID id,
            List<Float> vector,
            String prompt,
            String answer,
            String model,
            Instant createdAt,
            String embeddingModel,
            int expectedDimension
    ) {
        return new VectorRecord(id, vector, prompt, answer, model, createdAt, embeddingModel, expectedDimension);
    }

    /**
     * Validates that the record's vector size matches the expected dimension.
     *
     * @param expectedDimension the expected dimension to check against
     */
    public void validateDimension(int expectedDimension) {
        if (expectedDimension <= 0) {
            throw new IllegalArgumentException("Expected dimension must be greater than zero. Provided: " + expectedDimension);
        }
        if (vector.size() != expectedDimension) {
            throw new IllegalArgumentException(
                    "Vector dimension [" + vector.size() + "] does not match expected dimension [" + expectedDimension + "]"
            );
        }
    }
}
