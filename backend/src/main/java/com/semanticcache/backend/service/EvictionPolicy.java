package com.semanticcache.backend.service;

import com.semanticcache.backend.dto.CacheEntryMetadata;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/**
 * Policy interface for scoring cache entries and selecting an eviction victim.
 */
public interface EvictionPolicy {

    /**
     * Computes the keep-score for a cache entry at time {@code now}.
     * Higher score means the entry is more valuable to retain; lower score is evicted first.
     */
    double computeKeepScore(CacheEntryMetadata entry, Instant now);

    /**
     * Selects the lowest-scoring victim from the supplied candidate subset (e.g., K-Tail).
     */
    Optional<UUID> selectVictim(Collection<CacheEntryMetadata> candidates, Instant now);
}
