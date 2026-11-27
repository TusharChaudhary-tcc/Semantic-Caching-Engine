package com.semanticcache.backend.repository;

import com.semanticcache.backend.dto.SearchFilter;
import com.semanticcache.backend.dto.SearchResult;
import com.semanticcache.backend.dto.VectorRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Zero-dependency in-memory VectorRepository fallback using exact cosine similarity.
 * Activated when {@code cache.vector-store=memory} (e.g., local development without Docker/Qdrant).
 */
@Repository
@ConditionalOnProperty(name = "cache.vector-store", havingValue = "memory")
public class InMemoryVectorRepository implements VectorRepository {

    private static final Logger log = LoggerFactory.getLogger(InMemoryVectorRepository.class);

    private final Map<UUID, VectorRecord> store = new ConcurrentHashMap<>();

    public InMemoryVectorRepository() {
        log.warn("InMemoryVectorRepository active (cache.vector-store=memory). Vectors will not persist across restarts.");
    }

    @Override
    public void upsert(VectorRecord record) {
        Objects.requireNonNull(record, "record must not be null");
        store.put(record.id(), record);
    }

    @Override
    public Optional<VectorRecord> findById(UUID id) {
        Objects.requireNonNull(id, "id must not be null");
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public List<SearchResult> search(List<Float> queryVector, int limit) {
        return search(queryVector, limit, SearchFilter.publicOnly());
    }

    @Override
    public List<SearchResult> search(List<Float> queryVector, int limit, SearchFilter filter) {
        Objects.requireNonNull(queryVector, "queryVector must not be null");
        Objects.requireNonNull(filter, "filter must not be null");
        if (limit <= 0) {
            return List.of();
        }

        return store.values().stream()
                .map(rec -> new SearchResult(
                        rec.id(),
                        rec.prompt(),
                        rec.answer(),
                        rec.model(),
                        rec.createdAt(),
                        rec.embeddingModel(),
                        cosineSimilarity(queryVector, rec.vector()),
                        null,
                        "PUBLIC"
                ))
                .sorted(Comparator.comparingDouble(SearchResult::score).reversed())
                .limit(limit)
                .toList();
    }

    @Override
    public void deleteById(UUID id) {
        if (id != null) {
            store.remove(id);
        }
    }

    private static float cosineSimilarity(List<Float> a, List<Float> b) {
        int len = Math.min(a.size(), b.size());
        if (len == 0) return 0.0f;
        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < len; i++) {
            double va = a.get(i);
            double vb = b.get(i);
            dot += va * vb;
            normA += va * va;
            normB += vb * vb;
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom == 0.0 ? 0.0f : (float) (dot / denom);
    }
}
