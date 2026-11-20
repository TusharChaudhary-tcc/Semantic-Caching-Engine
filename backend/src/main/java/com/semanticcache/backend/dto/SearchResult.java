package com.semanticcache.backend.dto;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain record representing a matched vector record retrieved during semantic similarity search,
 * including its calculated similarity score and privacy/ownership metadata.
 */
public record SearchResult(
        UUID id,
        String prompt,
        String answer,
        String model,
        Instant createdAt,
        String embeddingModel,
        float score,
        String ownerId,
        String visibility
) {
    public SearchResult {
        Objects.requireNonNull(id, "id must not be null");
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
        if (!Float.isFinite(score)) {
            throw new IllegalArgumentException("score must be a finite numerical value");
        }

        prompt = prompt.trim();
        answer = answer.trim();
        model = model.trim();
        embeddingModel = embeddingModel.trim();
        ownerId = (ownerId != null && !ownerId.trim().isEmpty()) ? ownerId.trim() : null;

        if (visibility == null || visibility.trim().isEmpty()) {
            visibility = "PRIVATE";
        } else {
            visibility = visibility.trim().toUpperCase(Locale.ROOT);
            if (!visibility.equals("PRIVATE") && !visibility.equals("PUBLIC")) {
                throw new IllegalArgumentException(
                        "visibility must be either 'PRIVATE' or 'PUBLIC'. Provided: " + visibility
                );
            }
        }
    }

    /**
     * Backward-compatible 7-argument constructor defaulting ownerId to null and visibility to PRIVATE.
     */
    public SearchResult(
            UUID id,
            String prompt,
            String answer,
            String model,
            Instant createdAt,
            String embeddingModel,
            float score
    ) {
        this(id, prompt, answer, model, createdAt, embeddingModel, score, null, "PRIVATE");
    }
}
