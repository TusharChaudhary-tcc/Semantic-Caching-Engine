package com.semanticcache.backend.dto;

public record CacheResponse(
        String answer,
        boolean cacheHit
) {
}
