package com.semanticcache.backend.service;

import com.semanticcache.backend.config.SearchConfig;
import com.semanticcache.backend.dto.EmbeddingResponse;
import com.semanticcache.backend.dto.SearchFilter;
import com.semanticcache.backend.dto.SearchResult;
import com.semanticcache.backend.exception.EmbeddingException;
import com.semanticcache.backend.repository.VectorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

@Service
public class DefaultSimilaritySearchService implements SimilaritySearchService {

    private static final Logger log = LoggerFactory.getLogger(DefaultSimilaritySearchService.class);

    private final EmbeddingService embeddingService;
    private final VectorRepository vectorRepository;
    private final SearchConfig searchConfig;
    private final int expectedDimension;

    @Autowired
    public DefaultSimilaritySearchService(
            EmbeddingService embeddingService,
            VectorRepository vectorRepository,
            SearchConfig searchConfig,
            @Value("${gemini.embedding-dimension:768}") int expectedDimension
    ) {
        this.embeddingService = Objects.requireNonNull(embeddingService, "embeddingService must not be null");
        this.vectorRepository = Objects.requireNonNull(vectorRepository, "vectorRepository must not be null");
        this.searchConfig = Objects.requireNonNull(searchConfig, "searchConfig must not be null");
        if (expectedDimension <= 0) {
            throw new IllegalArgumentException("expectedDimension must be greater than zero. Configured value: " + expectedDimension);
        }
        this.expectedDimension = expectedDimension;
    }

    public DefaultSimilaritySearchService(
            EmbeddingService embeddingService,
            VectorRepository vectorRepository,
            SearchConfig searchConfig
    ) {
        this(embeddingService, vectorRepository, searchConfig, 768);
    }

    @Override
    public List<SearchResult> search(String query) {
        return search(query, searchConfig.getDefaultLimit(), SearchFilter.publicOnly());
    }

    @Override
    public List<SearchResult> search(String query, int limit) {
        return search(query, limit, SearchFilter.publicOnly());
    }

    @Override
    public List<SearchResult> search(String query, int limit, SearchFilter filter) {
        if (query == null || query.trim().isEmpty()) {
            throw new IllegalArgumentException("query must not be null or blank");
        }

        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be greater than zero. Provided: " + limit);
        }

        int maxLimit = searchConfig.getMaxLimit();
        if (limit > maxLimit) {
            throw new IllegalArgumentException(
                    "limit [" + limit + "] exceeds maximum allowed limit [" + maxLimit + "]"
            );
        }

        Objects.requireNonNull(filter, "filter must not be null");

        log.debug("Initiating similarity search with limit [{}] and filter [{}]", limit, filter);

        EmbeddingResponse embeddingResponse = embeddingService.getEmbedding(query.trim());
        if (embeddingResponse == null || embeddingResponse.embedding() == null) {
            throw new EmbeddingException("EmbeddingService returned null response or embedding vector");
        }

        List<Float> queryVector = embeddingResponse.embedding();
        if (queryVector.size() != expectedDimension) {
            throw new EmbeddingException(
                    "Generated embedding dimension [" + queryVector.size() + "] does not match expected dimension [" + expectedDimension + "]"
            );
        }

        List<SearchResult> results = vectorRepository.search(queryVector, limit, filter);
        log.debug("Similarity search returned [{}] results", results != null ? results.size() : 0);

        return results;
    }

    public int getExpectedDimension() {
        return expectedDimension;
    }
}
