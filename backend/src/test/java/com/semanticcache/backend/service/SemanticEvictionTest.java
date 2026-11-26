package com.semanticcache.backend.service;

import com.semanticcache.backend.config.EvictionConfig;
import com.semanticcache.backend.dto.CacheEntryMetadata;
import com.semanticcache.backend.repository.VectorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SemanticEvictionTest {

    @Mock
    private VectorRepository mockVectorRepository;

    private EvictionConfig config;
    private SemanticDensityEvictionPolicy policy;
    private SemanticEvictionManager manager;

    @BeforeEach
    void setUp() {
        config = new EvictionConfig(true, 3, 2, 0.30, 0.25, 0.25, 0.20, 0.05);
        policy = new SemanticDensityEvictionPolicy(config);
        manager = new SemanticEvictionManager(config, policy, mockVectorRepository);
    }

    @Test
    @DisplayName("High-cost, dense, frequent entry outscores cheap, isolated, infrequent entry")
    void highValueEntryOutscoresCheapEntry() {
        Instant now = Instant.now();
        CacheEntryMetadata valuable = new CacheEntryMetadata(
                UUID.randomUUID(), now.minus(2, ChronoUnit.HOURS), now.minus(10, ChronoUnit.MINUTES),
                10L, 4200L, 900, 0.90
        );
        CacheEntryMetadata cheap = new CacheEntryMetadata(
                UUID.randomUUID(), now.minus(2, ChronoUnit.HOURS), now.minus(10, ChronoUnit.MINUTES),
                0L, 150L, 25, 0.05
        );

        double valuableScore = policy.computeKeepScore(valuable, now);
        double cheapScore = policy.computeKeepScore(cheap, now);

        assertTrue(valuableScore > cheapScore);
        Optional<UUID> victim = policy.selectVictim(List.of(valuable, cheap), now);
        assertEquals(Optional.of(cheap.id()), victim);
    }

    @Test
    @DisplayName("K-Tail manager evicts lowest-scoring cold entry and deletes it from VectorRepository")
    void kTailEvictsAndDeletesFromRepository() {
        UUID cheapOldId = UUID.randomUUID();
        UUID expensiveOldId = UUID.randomUUID();
        UUID thirdId = UUID.randomUUID();
        UUID fourthId = UUID.randomUUID();

        // Insert 3 entries to fill capacity (maxEntries = 3)
        manager.recordInsert(cheapOldId, 100L, 20, 0.0);
        manager.recordInsert(expensiveOldId, 4500L, 950, 0.95);
        manager.recordInsert(thirdId, 2000L, 400, 0.50);

        // 4th insert triggers K-Tail (k=2 -> considers cheapOldId & expensiveOldId)
        Optional<UUID> evicted = manager.recordInsert(fourthId, 1200L, 300, 0.40);

        assertEquals(Optional.of(cheapOldId), evicted);
        assertEquals(3, manager.getTrackedCount());
        assertEquals(1L, manager.getTotalEvictions());
        verify(mockVectorRepository).deleteById(cheapOldId);
    }

    @Test
    @DisplayName("Recording a cache hit moves entry out of K-Tail cold window")
    void hitProtectsEntryFromKTail() {
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        UUID thirdId = UUID.randomUUID();
        UUID fourthId = UUID.randomUUID();

        manager.recordInsert(firstId, 100L, 20, 0.0);
        manager.recordInsert(secondId, 100L, 20, 0.0);
        manager.recordInsert(thirdId, 4000L, 800, 0.90);

        // Access firstId so it moves to MRU; now K-Tail (k=2) contains [secondId, thirdId]
        manager.recordHit(firstId);

        Optional<UUID> evicted = manager.recordInsert(fourthId, 500L, 100, 0.30);

        assertEquals(Optional.of(secondId), evicted);
        verify(mockVectorRepository).deleteById(secondId);
    }
}
