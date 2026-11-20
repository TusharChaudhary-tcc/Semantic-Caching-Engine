package com.semanticcache.backend.service;

import com.semanticcache.backend.config.SearchConfig;
import com.semanticcache.backend.dto.EmbeddingResponse;
import com.semanticcache.backend.dto.SearchFilter;
import com.semanticcache.backend.dto.SearchResult;
import com.semanticcache.backend.exception.EmbeddingException;
import com.semanticcache.backend.exception.QdrantOperationException;
import com.semanticcache.backend.repository.VectorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SimilaritySearchServiceTest {

    private static final int DEFAULT_LIMIT = 5;
    private static final int MAX_LIMIT = 50;
    private static final int CONFIGURED_DIMENSION = 768;

    @Mock
    private EmbeddingService mockEmbeddingService;

    @Mock
    private VectorRepository mockVectorRepository;

    private SearchConfig searchConfig;
    private DefaultSimilaritySearchService searchService;

    private List<Float> sampleVector;
    private EmbeddingResponse sampleEmbeddingResponse;
    private SearchResult sampleResult1;
    private SearchResult sampleResult2;

    @BeforeEach
    void setUp() {
        searchConfig = new SearchConfig(DEFAULT_LIMIT, MAX_LIMIT);
        searchService = new DefaultSimilaritySearchService(
                mockEmbeddingService,
                mockVectorRepository,
                searchConfig,
                CONFIGURED_DIMENSION
        );

        sampleVector = new ArrayList<>(Collections.nCopies(CONFIGURED_DIMENSION, 0.05f));
        sampleEmbeddingResponse = new EmbeddingResponse(sampleVector, CONFIGURED_DIMENSION);

        sampleResult1 = new SearchResult(
                UUID.randomUUID(),
                "What is semantic caching?",
                "Semantic caching stores embeddings to find similar queries.",
                "gemini-3.8-flash",
                Instant.parse("2026-11-08T10:00:00Z"),
                "gemini-embedding-001",
                0.95f
        );

        sampleResult2 = new SearchResult(
                UUID.randomUUID(),
                "How does semantic caching work?",
                "It performs vector search using cosine distance.",
                "gemini-3.8-flash",
                Instant.parse("2026-11-08T10:01:00Z"),
                "gemini-embedding-001",
                0.82f
        );
    }

    @Nested
    @DisplayName("Constructor and Configuration Validation")
    class ConstructorTests {

        @Test
        @DisplayName("Rejects null EmbeddingService")
        void rejectsNullEmbeddingService() {
            assertThrows(NullPointerException.class, () ->
                    new DefaultSimilaritySearchService(null, mockVectorRepository, searchConfig, CONFIGURED_DIMENSION));
        }

        @Test
        @DisplayName("Rejects null VectorRepository")
        void rejectsNullVectorRepository() {
            assertThrows(NullPointerException.class, () ->
                    new DefaultSimilaritySearchService(mockEmbeddingService, null, searchConfig, CONFIGURED_DIMENSION));
        }

        @Test
        @DisplayName("Rejects null SearchConfig")
        void rejectsNullSearchConfig() {
            assertThrows(NullPointerException.class, () ->
                    new DefaultSimilaritySearchService(mockEmbeddingService, mockVectorRepository, null, CONFIGURED_DIMENSION));
        }

        @Test
        @DisplayName("Rejects non-positive expectedDimension")
        void rejectsNonPositiveDimension() {
            assertThrows(IllegalArgumentException.class, () ->
                    new DefaultSimilaritySearchService(mockEmbeddingService, mockVectorRepository, searchConfig, 0));
            assertThrows(IllegalArgumentException.class, () ->
                    new DefaultSimilaritySearchService(mockEmbeddingService, mockVectorRepository, searchConfig, -1));
        }

        @Test
        @DisplayName("Convenience constructor uses default dimension 768")
        void convenienceConstructorUsesDefaultDimension() {
            DefaultSimilaritySearchService service = new DefaultSimilaritySearchService(
                    mockEmbeddingService,
                    mockVectorRepository,
                    searchConfig
            );
            assertEquals(768, service.getExpectedDimension());
        }
    }

    @Nested
    @DisplayName("Successful Search Operations")
    class SuccessfulSearchTests {

        @Test
        @DisplayName("Successful search with default limit coordinates embedding and vector repository with SearchFilter.publicOnly()")
        void successfulSearchWithDefaultLimit() {
            String query = "semantic cache query";
            when(mockEmbeddingService.getEmbedding("semantic cache query")).thenReturn(sampleEmbeddingResponse);
            when(mockVectorRepository.search(sampleVector, DEFAULT_LIMIT, SearchFilter.publicOnly()))
                    .thenReturn(List.of(sampleResult1, sampleResult2));

            List<SearchResult> results = searchService.search(query);

            assertEquals(2, results.size());
            assertSame(sampleResult1, results.get(0));
            assertSame(sampleResult2, results.get(1));
            assertEquals(0.95f, results.get(0).score());
            assertEquals(0.82f, results.get(1).score());

            verify(mockEmbeddingService).getEmbedding("semantic cache query");
            verify(mockVectorRepository).search(sampleVector, DEFAULT_LIMIT, SearchFilter.publicOnly());
        }

        @Test
        @DisplayName("Successful search with custom limit passes specified limit and SearchFilter.publicOnly() to repository")
        void successfulSearchWithCustomLimit() {
            String query = "  custom limit query  ";
            int customLimit = 15;
            when(mockEmbeddingService.getEmbedding("custom limit query")).thenReturn(sampleEmbeddingResponse);
            when(mockVectorRepository.search(sampleVector, customLimit, SearchFilter.publicOnly()))
                    .thenReturn(List.of(sampleResult1));

            List<SearchResult> results = searchService.search(query, customLimit);

            assertEquals(1, results.size());
            assertSame(sampleResult1, results.get(0));

            verify(mockEmbeddingService).getEmbedding("custom limit query");
            verify(mockVectorRepository).search(sampleVector, customLimit, SearchFilter.publicOnly());
        }

        @Test
        @DisplayName("Successful search with explicit SearchFilter delegates vector, limit, and filter to repository")
        void successfulSearchWithExplicitFilter() {
            String query = "owner query";
            SearchFilter ownerFilter = SearchFilter.forOwner("user-abc");
            when(mockEmbeddingService.getEmbedding("owner query")).thenReturn(sampleEmbeddingResponse);
            when(mockVectorRepository.search(sampleVector, 10, ownerFilter)).thenReturn(List.of(sampleResult1));

            List<SearchResult> results = searchService.search(query, 10, ownerFilter);

            assertEquals(1, results.size());
            assertSame(sampleResult1, results.get(0));
            verify(mockVectorRepository).search(sampleVector, 10, ownerFilter);
        }

        @Test
        @DisplayName("Correctly delegates exact embedding vector, limit, and public-only filter to repository")
        void correctlyDelegatesVectorAndLimit() {
            String query = "delegation test";
            when(mockEmbeddingService.getEmbedding(query)).thenReturn(sampleEmbeddingResponse);
            when(mockVectorRepository.search(any(), anyInt(), any())).thenReturn(Collections.emptyList());

            searchService.search(query, 10);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<Float>> vectorCaptor = ArgumentCaptor.forClass(List.class);
            ArgumentCaptor<Integer> limitCaptor = ArgumentCaptor.forClass(Integer.class);
            ArgumentCaptor<SearchFilter> filterCaptor = ArgumentCaptor.forClass(SearchFilter.class);

            verify(mockVectorRepository).search(vectorCaptor.capture(), limitCaptor.capture(), filterCaptor.capture());

            assertEquals(sampleVector, vectorCaptor.getValue());
            assertEquals(10, limitCaptor.getValue());
            assertEquals(SearchFilter.publicOnly(), filterCaptor.getValue());
        }

        @Test
        @DisplayName("Preserves repository ordering and scores without alteration")
        void preservesRepositoryOrderingAndScores() {
            String query = "ranking test";
            List<SearchResult> orderedResults = List.of(sampleResult1, sampleResult2);
            when(mockEmbeddingService.getEmbedding(query)).thenReturn(sampleEmbeddingResponse);
            when(mockVectorRepository.search(sampleVector, DEFAULT_LIMIT, SearchFilter.publicOnly())).thenReturn(orderedResults);

            List<SearchResult> results = searchService.search(query);

            assertEquals(2, results.size());
            assertEquals(sampleResult1.id(), results.get(0).id());
            assertEquals(0.95f, results.get(0).score());
            assertEquals(sampleResult2.id(), results.get(1).id());
            assertEquals(0.82f, results.get(1).score());
        }

        @Test
        @DisplayName("Application search calls never invoke the unrestricted 2-argument VectorRepository.search overload")
        void neverInvokesUnfilteredRepositoryOverload() {
            when(mockEmbeddingService.getEmbedding(anyString())).thenReturn(sampleEmbeddingResponse);
            when(mockVectorRepository.search(any(), anyInt(), any())).thenReturn(Collections.emptyList());

            searchService.search("test query");
            searchService.search("test query", 5);
            searchService.search("test query", 5, SearchFilter.forOwner("owner1"));

            verify(mockVectorRepository, never()).search(any(), anyInt());
        }
    }

    @Nested
    @DisplayName("searchWithVector Tests")
    class SearchWithVectorTests {

        @Test
        @DisplayName("Calls embedding service exactly once and returns both results and exact vector")
        void callsEmbeddingServiceOnceAndReturnsCarrier() {
            String query = "search with vector query";
            SearchFilter filter = SearchFilter.publicOnly();
            when(mockEmbeddingService.getEmbedding(query)).thenReturn(sampleEmbeddingResponse);
            when(mockVectorRepository.search(sampleVector, 5, filter)).thenReturn(List.of(sampleResult1));

            com.semanticcache.backend.dto.SimilaritySearchResults response =
                    searchService.searchWithVector(query, 5, filter);

            verify(mockEmbeddingService, org.mockito.Mockito.times(1)).getEmbedding(query);
            verify(mockVectorRepository, org.mockito.Mockito.times(1)).search(sampleVector, 5, filter);

            assertEquals(List.of(sampleResult1), response.results());
            assertEquals(sampleVector, response.queryVector());
        }

        @Test
        @DisplayName("Rejects null or blank query")
        void rejectsNullOrBlankQuery() {
            assertThrows(IllegalArgumentException.class, () ->
                    searchService.searchWithVector(null, 5, SearchFilter.publicOnly()));
            assertThrows(IllegalArgumentException.class, () ->
                    searchService.searchWithVector("   ", 5, SearchFilter.publicOnly()));
        }

        @Test
        @DisplayName("Rejects invalid limit")
        void rejectsInvalidLimit() {
            assertThrows(IllegalArgumentException.class, () ->
                    searchService.searchWithVector("valid", 0, SearchFilter.publicOnly()));
            assertThrows(IllegalArgumentException.class, () ->
                    searchService.searchWithVector("valid", MAX_LIMIT + 1, SearchFilter.publicOnly()));
        }

        @Test
        @DisplayName("Rejects null SearchFilter")
        void rejectsNullFilter() {
            assertThrows(NullPointerException.class, () ->
                    searchService.searchWithVector("valid", 5, null));
        }

        @Test
        @DisplayName("Enforces configured dimension in searchWithVector")
        void enforcesDimension() {
            List<Float> shortVector = new ArrayList<>(Collections.nCopies(512, 0.05f));
            EmbeddingResponse shortResponse = new EmbeddingResponse(shortVector, 512);
            when(mockEmbeddingService.getEmbedding("valid")).thenReturn(shortResponse);

            assertThrows(EmbeddingException.class, () ->
                    searchService.searchWithVector("valid", 5, SearchFilter.publicOnly()));
        }
    }

    @Nested
    @DisplayName("Query, Limit, and Filter Validation Tests")
    class ValidationTests {

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\t", "\n", " \t \n "})
        @DisplayName("Rejects null, empty, or whitespace-only queries")
        void rejectsNullOrBlankQueries(String invalidQuery) {
            IllegalArgumentException ex = assertThrows(
                    IllegalArgumentException.class,
                    () -> searchService.search(invalidQuery)
            );
            assertTrue(ex.getMessage().contains("query must not be null or blank"));
            verify(mockEmbeddingService, never()).getEmbedding(anyString());
            verify(mockVectorRepository, never()).search(any(), anyInt(), any());
        }

        @ParameterizedTest
        @ValueSource(ints = {0, -1, -50})
        @DisplayName("Rejects zero and negative limits")
        void rejectsZeroAndNegativeLimits(int invalidLimit) {
            IllegalArgumentException ex = assertThrows(
                    IllegalArgumentException.class,
                    () -> searchService.search("valid query", invalidLimit)
            );
            assertTrue(ex.getMessage().contains("limit must be greater than zero"));
            verify(mockEmbeddingService, never()).getEmbedding(anyString());
            verify(mockVectorRepository, never()).search(any(), anyInt(), any());
        }

        @Test
        @DisplayName("Rejects limits exceeding configured maxLimit")
        void rejectsLimitExceedingMaxLimit() {
            int overLimit = MAX_LIMIT + 1;
            IllegalArgumentException ex = assertThrows(
                    IllegalArgumentException.class,
                    () -> searchService.search("valid query", overLimit)
            );
            assertTrue(ex.getMessage().contains("exceeds maximum allowed limit"));
            verify(mockEmbeddingService, never()).getEmbedding(anyString());
            verify(mockVectorRepository, never()).search(any(), anyInt(), any());
        }

        @Test
        @DisplayName("Allows limit equal to configured maxLimit")
        void allowsLimitEqualToMaxLimit() {
            when(mockEmbeddingService.getEmbedding("valid query")).thenReturn(sampleEmbeddingResponse);
            when(mockVectorRepository.search(sampleVector, MAX_LIMIT, SearchFilter.publicOnly())).thenReturn(Collections.emptyList());

            List<SearchResult> results = searchService.search("valid query", MAX_LIMIT);
            assertEquals(Collections.emptyList(), results);
            verify(mockVectorRepository).search(sampleVector, MAX_LIMIT, SearchFilter.publicOnly());
        }

        @Test
        @DisplayName("Rejects null SearchFilter with NullPointerException")
        void rejectsNullSearchFilter() {
            assertThrows(
                    NullPointerException.class,
                    () -> searchService.search("valid query", 5, null)
            );
            verify(mockEmbeddingService, never()).getEmbedding(anyString());
            verify(mockVectorRepository, never()).search(any(), anyInt(), any());
        }
    }

    @Nested
    @DisplayName("Embedding and Repository Failure Propagation")
    class FailurePropagationTests {

        @Test
        @DisplayName("Propagates EmbeddingException when embedding generation fails")
        void propagatesEmbeddingException() {
            EmbeddingException rootCause = new EmbeddingException("Failed to generate embedding");
            when(mockEmbeddingService.getEmbedding("failing query")).thenThrow(rootCause);

            EmbeddingException ex = assertThrows(
                    EmbeddingException.class,
                    () -> searchService.search("failing query")
            );
            assertSame(rootCause, ex);
            verify(mockVectorRepository, never()).search(any(), anyInt(), any());
        }

        @Test
        @DisplayName("Throws EmbeddingException when EmbeddingService returns null response or vector")
        void throwsWhenEmbeddingResponseIsNull() {
            when(mockEmbeddingService.getEmbedding("null embedding query")).thenReturn(null);

            EmbeddingException ex = assertThrows(
                    EmbeddingException.class,
                    () -> searchService.search("null embedding query")
            );
            assertTrue(ex.getMessage().contains("returned null response or embedding vector"));
            verify(mockVectorRepository, never()).search(any(), anyInt(), any());
        }

        @Test
        @DisplayName("Throws EmbeddingException when embedding dimension does not match configured dimension")
        void throwsOnEmbeddingDimensionMismatch() {
            List<Float> shortVector = new ArrayList<>(Collections.nCopies(512, 0.05f));
            EmbeddingResponse shortResponse = new EmbeddingResponse(shortVector, 512);
            when(mockEmbeddingService.getEmbedding("mismatched query")).thenReturn(shortResponse);

            EmbeddingException ex = assertThrows(
                    EmbeddingException.class,
                    () -> searchService.search("mismatched query")
            );
            assertTrue(ex.getMessage().contains("does not match expected dimension"));
            verify(mockVectorRepository, never()).search(any(), anyInt(), any());
        }

        @Test
        @DisplayName("Propagates QdrantOperationException when repository search fails")
        void propagatesRepositorySearchFailure() {
            QdrantOperationException rootCause = new QdrantOperationException("Qdrant search failed");
            when(mockEmbeddingService.getEmbedding("query")).thenReturn(sampleEmbeddingResponse);
            when(mockVectorRepository.search(sampleVector, DEFAULT_LIMIT, SearchFilter.publicOnly())).thenThrow(rootCause);

            QdrantOperationException ex = assertThrows(
                    QdrantOperationException.class,
                    () -> searchService.search("query")
            );
            assertSame(rootCause, ex);
        }
    }
}
