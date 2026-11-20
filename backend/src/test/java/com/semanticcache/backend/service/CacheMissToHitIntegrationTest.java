package com.semanticcache.backend.service;

import com.semanticcache.backend.config.CacheConfig;
import com.semanticcache.backend.config.SearchConfig;
import com.semanticcache.backend.dto.CacheRequest;
import com.semanticcache.backend.dto.CacheResponse;
import com.semanticcache.backend.dto.EmbeddingResponse;
import com.semanticcache.backend.dto.SearchFilter;
import com.semanticcache.backend.dto.SearchResult;
import com.semanticcache.backend.dto.VectorRecord;
import com.semanticcache.backend.repository.VectorRepository;
import com.semanticcache.backend.telemetry.CacheMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Service-level integration test verifying the complete cache miss -> write-back -> cache hit loop.
 *
 * NOTE: This test uses a controlled in-memory fake {@link VectorRepository} backed by a concurrent map
 * and exact cosine similarity calculation to test the genuine write-back and search round-trip without
 * requiring a live external Qdrant server daemon.
 */
@ExtendWith(MockitoExtension.class)
class CacheMissToHitIntegrationTest {

    private static final int DIMENSION = 768;

    @Mock
    private EmbeddingService mockEmbeddingService;

    @Mock
    private GeminiProvider mockGeminiProvider;

    private InMemoryVectorRepository fakeVectorRepository;
    private CacheService cacheService;

    private List<Float> deterministicVector;

    @BeforeEach
    void setUp() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        CacheMetrics cacheMetrics = new CacheMetrics(meterRegistry);

        fakeVectorRepository = new InMemoryVectorRepository();

        SearchConfig searchConfig = new SearchConfig(5, 50);
        SimilaritySearchService similaritySearchService = new DefaultSimilaritySearchService(
                mockEmbeddingService,
                fakeVectorRepository,
                searchConfig,
                DIMENSION
        );

        CacheRetrievalService retrievalService = new DefaultCacheRetrievalService(similaritySearchService);
        CacheDecisionService decisionService = new DefaultCacheDecisionService(new CacheConfig(0.85f));

        cacheService = new CacheService(
                retrievalService,
                decisionService,
                mockGeminiProvider,
                fakeVectorRepository,
                cacheMetrics,
                "gemini-3.8-flash",
                "gemini-embedding-001"
        );

        deterministicVector = new ArrayList<>(Collections.nCopies(DIMENSION, 0.05f));
    }

    @Test
    @DisplayName("Cache miss generates answer, persists to repository with PUBLIC visibility, and second request produces cache hit without LLM call")
    void cacheMissThenCacheHitLifecycle() {
        String prompt = "What is semantic caching?";
        String llmAnswer = "Semantic caching saves latency and cost by reusing LLM responses for similar queries.";

        // Stub embedding service: returns deterministic 768d vector
        when(mockEmbeddingService.getEmbedding(prompt))
                .thenReturn(new EmbeddingResponse(deterministicVector, DIMENSION));

        // Stub Gemini provider for the initial cache miss
        when(mockGeminiProvider.generateAnswer(prompt))
                .thenReturn(llmAnswer);

        // -------------------------------------------------------------
        // Request 1: Cache Miss
        // -------------------------------------------------------------
        CacheResponse response1 = cacheService.processPrompt(new CacheRequest(prompt));

        assertThat(response1.answer()).isEqualTo(llmAnswer);
        assertThat(response1.cacheHit()).isFalse();

        // Verify Gemini LLM was called exactly once
        verify(mockGeminiProvider, times(1)).generateAnswer(prompt);

        // Verify fake repository stored the record
        assertThat(fakeVectorRepository.getSavedRecords()).hasSize(1);
        VectorRecord saved = fakeVectorRepository.getSavedRecords().get(0);
        assertThat(saved.prompt()).isEqualTo(prompt);
        assertThat(saved.answer()).isEqualTo(llmAnswer);
        assertThat(saved.vector()).isEqualTo(deterministicVector);
        assertThat(saved.model()).isEqualTo("gemini-3.8-flash");
        assertThat(saved.embeddingModel()).isEqualTo("gemini-embedding-001");
        assertThat(fakeVectorRepository.getStoredVisibility(saved.id())).isEqualTo("PUBLIC");

        // -------------------------------------------------------------
        // Request 2: Same prompt -> Cache Hit
        // -------------------------------------------------------------
        CacheResponse response2 = cacheService.processPrompt(new CacheRequest(prompt));

        assertThat(response2.answer()).isEqualTo(llmAnswer);
        assertThat(response2.cacheHit()).isTrue();

        // Verify Gemini was NOT called again on second request
        verify(mockGeminiProvider, times(1)).generateAnswer(anyString());

        // Verify NO second upsert occurred in the repository
        assertThat(fakeVectorRepository.getUpsertCount()).isEqualTo(1);
        assertThat(fakeVectorRepository.getSavedRecords()).hasSize(1);
    }

    /**
     * In-memory fake VectorRepository that supports upsert and cosine similarity search.
     */
    static class InMemoryVectorRepository implements VectorRepository {

        private final List<VectorRecord> savedRecords = new ArrayList<>();
        private final ConcurrentHashMap<UUID, String> visibilityMap = new ConcurrentHashMap<>();
        private int upsertCount = 0;

        @Override
        public void upsert(VectorRecord record) {
            Objects.requireNonNull(record, "record must not be null");
            savedRecords.add(record);
            // In accordance with QdrantVectorRepository, newly persisted records are stamped PUBLIC
            visibilityMap.put(record.id(), "PUBLIC");
            upsertCount++;
        }

        @Override
        public Optional<VectorRecord> findById(UUID id) {
            return savedRecords.stream().filter(r -> r.id().equals(id)).findFirst();
        }

        @Override
        public List<SearchResult> search(List<Float> queryVector, int limit) {
            return search(queryVector, limit, SearchFilter.publicOnly());
        }

        @Override
        public List<SearchResult> search(List<Float> queryVector, int limit, SearchFilter filter) {
            List<SearchResult> matches = new ArrayList<>();
            for (VectorRecord rec : savedRecords) {
                String vis = visibilityMap.get(rec.id());
                if (filter.isPublicOnly() && !"PUBLIC".equalsIgnoreCase(vis)) {
                    continue;
                }
                float score = cosineSimilarity(queryVector, rec.vector());
                matches.add(new SearchResult(
                        rec.id(),
                        rec.prompt(),
                        rec.answer(),
                        rec.model(),
                        rec.createdAt(),
                        rec.embeddingModel(),
                        score,
                        null,
                        vis
                ));
            }
            matches.sort((a, b) -> Float.compare(b.score(), a.score()));
            return matches.stream().limit(limit).toList();
        }

        private float cosineSimilarity(List<Float> v1, List<Float> v2) {
            double dot = 0.0;
            double normA = 0.0;
            double normB = 0.0;
            for (int i = 0; i < v1.size(); i++) {
                dot += v1.get(i) * v2.get(i);
                normA += v1.get(i) * v1.get(i);
                normB += v2.get(i) * v2.get(i);
            }
            if (normA == 0.0 || normB == 0.0) return 0.0f;
            return (float) (dot / (Math.sqrt(normA) * Math.sqrt(normB)));
        }

        public List<VectorRecord> getSavedRecords() {
            return Collections.unmodifiableList(savedRecords);
        }

        public String getStoredVisibility(UUID id) {
            return visibilityMap.get(id);
        }

        public int getUpsertCount() {
            return upsertCount;
        }
    }
}
