package com.semanticcache.backend.dto;

public record CacheResponse(
        String answer,
        boolean cacheHit,
        Float stage1Similarity,
        Float stage2CrossEncoderScore,
        String verificationStage,
        Long latencyMs,
        String provider
) {
    public CacheResponse(String answer, boolean cacheHit) {
        this(answer, cacheHit, null, null, null, null, null);
    }

    public CacheResponse(
            String answer,
            boolean cacheHit,
            Float stage1Similarity,
            Float stage2CrossEncoderScore,
            String verificationStage,
            Long latencyMs
    ) {
        this(answer, cacheHit, stage1Similarity, stage2CrossEncoderScore, verificationStage, latencyMs, null);
    }
}
