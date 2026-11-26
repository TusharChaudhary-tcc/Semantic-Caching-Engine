package com.semanticcache.backend.service;

import com.semanticcache.backend.config.EvictionConfig;
import com.semanticcache.backend.dto.CacheEntryMetadata;
import com.semanticcache.backend.repository.VectorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages cache capacity using the "Dusty Corner" (K-Tail) selector paired with
 * {@link EvictionPolicy}. Tracks entries in access-order so only the oldest K entries
 * are evaluated during eviction, achieving O(K) = O(1) bounded eviction overhead.
 */
@Service
public class SemanticEvictionManager {

    private static final Logger log = LoggerFactory.getLogger(SemanticEvictionManager.class);

    private final EvictionConfig config;
    private final EvictionPolicy evictionPolicy;
    private final VectorRepository vectorRepository;
    private final Map<UUID, CacheEntryMetadata> accessOrderIndex = new LinkedHashMap<>(256, 0.75f, true);
    private final AtomicLong totalEvictions = new AtomicLong(0L);

    @Autowired
    public SemanticEvictionManager(
            EvictionConfig config,
            EvictionPolicy evictionPolicy,
            @Autowired(required = false) VectorRepository vectorRepository
    ) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.evictionPolicy = Objects.requireNonNull(evictionPolicy, "evictionPolicy must not be null");
        this.vectorRepository = vectorRepository;
    }

    /**
     * Updates recency and hit frequency for a cache hit.
     */
    public synchronized void recordHit(UUID id) {
        if (!config.isEnabled() || id == null) {
            return;
        }
        CacheEntryMetadata existing = accessOrderIndex.get(id);
        if (existing != null) {
            accessOrderIndex.put(id, existing.withRecordedHit(Instant.now()));
        }
    }

    /**
     * Registers a newly cached entry and evicts the lowest-scoring K-Tail victim if capacity is exceeded.
     *
     * @return Optional containing the evicted entry UUID, if an eviction occurred
     */
    public synchronized Optional<UUID> recordInsert(
            UUID id,
            long generationLatencyMs,
            int estimatedTokens,
            double clusterDensity
    ) {
        Objects.requireNonNull(id, "id must not be null");
        if (!config.isEnabled()) {
            return Optional.empty();
        }

        Instant now = Instant.now();
        Optional<UUID> evictedId = Optional.empty();

        if (!accessOrderIndex.containsKey(id) && accessOrderIndex.size() >= config.getMaxEntries()) {
            List<CacheEntryMetadata> kTailCandidates = sampleKTail(config.getKTail());
            evictedId = evictionPolicy.selectVictim(kTailCandidates, now);
            if (evictedId.isPresent()) {
                UUID victim = evictedId.get();
                accessOrderIndex.remove(victim);
                totalEvictions.incrementAndGet();
                deleteFromVectorStore(victim);
                log.info("Evicted cache entry [{}] via K-Tail (k={}) Semantic Density Policy",
                        victim, kTailCandidates.size());
            }
        }

        double boundedDensity = Math.max(0.0, Math.min(1.0, Double.isFinite(clusterDensity) ? clusterDensity : 0.0));
        CacheEntryMetadata metadata = new CacheEntryMetadata(
                id,
                now,
                now,
                0L,
                Math.max(0L, generationLatencyMs),
                Math.max(0, estimatedTokens),
                boundedDensity
        );
        accessOrderIndex.put(id, metadata);
        return evictedId;
    }

    public synchronized int getTrackedCount() {
        return accessOrderIndex.size();
    }

    public long getTotalEvictions() {
        return totalEvictions.get();
    }

    private List<CacheEntryMetadata> sampleKTail(int k) {
        List<CacheEntryMetadata> tail = new ArrayList<>(Math.min(k, accessOrderIndex.size()));
        Iterator<CacheEntryMetadata> iterator = accessOrderIndex.values().iterator();
        while (iterator.hasNext() && tail.size() < k) {
            tail.add(iterator.next());
        }
        return tail;
    }

    private void deleteFromVectorStore(UUID victimId) {
        if (vectorRepository == null) {
            return;
        }
        try {
            vectorRepository.deleteById(victimId);
        } catch (Exception e) {
            log.warn("Failed to delete evicted vector [{}] from repository: [{}]", victimId, e.getClass().getName());
        }
    }
}
