package com.semanticcache.backend.service;

import com.semanticcache.backend.dto.SearchFilter;
import com.semanticcache.backend.dto.SearchResult;
import com.semanticcache.backend.exception.EmbeddingException;
import com.semanticcache.backend.exception.QdrantOperationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CacheRetrievalServiceTest {

    @Mock
    private SimilaritySearchService mockSimilaritySearchService;

    private DefaultCacheRetrievalService retrievalService;

    private SearchResult sampleCandidate;

    @BeforeEach
    void setUp() {
        retrievalService = new DefaultCacheRetrievalService(mockSimilaritySearchService);

        sampleCandidate = new SearchResult(
                UUID.randomUUID(),
                "What is semantic caching?",
                "Semantic caching stores embeddings to find similar queries.",
                "gemini-3.8-flash",
                Instant.parse("2026-11-08T12:00:00Z"),
                "gemini-embedding-001",
                0.93f,
                null,
                "PUBLIC"
        );
    }

    @Nested
    @DisplayName("Constructor and Query Validation")
    class ValidationTests {

        @Test
        @DisplayName("Rejects null SimilaritySearchService in constructor")
        void rejectsNullService() {
            assertThrows(NullPointerException.class, () -> new DefaultCacheRetrievalService(null));
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\t", "\n"})
        @DisplayName("Rejects null, empty, or blank queries")
        void rejectsNullOrBlankQueries(String invalidQuery) {
            IllegalArgumentException ex = assertThrows(
                    IllegalArgumentException.class,
                    () -> retrievalService.findBestCandidate(invalidQuery)
            );
            assertTrue(ex.getMessage().contains("query must not be null or blank"));
            verify(mockSimilaritySearchService, never()).search(anyString(), anyInt(), any());
        }

        @Test
        @DisplayName("Rejects null SearchFilter with NullPointerException")
        void rejectsNullSearchFilter() {
            assertThrows(
                    NullPointerException.class,
                    () -> retrievalService.findBestCandidate("valid query", null)
            );
            verify(mockSimilaritySearchService, never()).search(anyString(), anyInt(), any());
        }
    }

    @Nested
    @DisplayName("Candidate Retrieval Operations")
    class RetrievalTests {

        @Test
        @DisplayName("findBestCandidate with default query delegates with limit 1 and SearchFilter.publicOnly()")
        void findBestCandidateDefaultPublicOnly() {
            when(mockSimilaritySearchService.search("test query", 1, SearchFilter.publicOnly()))
                    .thenReturn(List.of(sampleCandidate));

            Optional<SearchResult> result = retrievalService.findBestCandidate("  test query  ");

            assertTrue(result.isPresent());
            assertSame(sampleCandidate, result.get());
            verify(mockSimilaritySearchService).search("test query", 1, SearchFilter.publicOnly());
        }

        @Test
        @DisplayName("findBestCandidate with explicit owner filter delegates with limit 1 and owner filter")
        void findBestCandidateExplicitOwnerFilter() {
            SearchFilter ownerFilter = SearchFilter.forOwner("user-42");
            when(mockSimilaritySearchService.search("owner query", 1, ownerFilter))
                    .thenReturn(List.of(sampleCandidate));

            Optional<SearchResult> result = retrievalService.findBestCandidate("owner query", ownerFilter);

            assertTrue(result.isPresent());
            assertSame(sampleCandidate, result.get());
            verify(mockSimilaritySearchService).search("owner query", 1, ownerFilter);
        }

        @Test
        @DisplayName("Returns Optional.empty() when no candidates found by search service")
        void returnsEmptyWhenNoCandidates() {
            when(mockSimilaritySearchService.search("unknown query", 1, SearchFilter.publicOnly()))
                    .thenReturn(Collections.emptyList());

            Optional<SearchResult> result = retrievalService.findBestCandidate("unknown query");

            assertFalse(result.isPresent());
        }

        @Test
        @DisplayName("Returns Optional.empty() when search service returns null list")
        void returnsEmptyWhenNullReturned() {
            when(mockSimilaritySearchService.search("null query", 1, SearchFilter.publicOnly()))
                    .thenReturn(null);

            Optional<SearchResult> result = retrievalService.findBestCandidate("null query");

            assertFalse(result.isPresent());
        }
    }

    @Nested
    @DisplayName("findBestCandidateWithVector Operations")
    class FindBestCandidateWithVectorTests {

        @Test
        @DisplayName("Returns candidate with exact vector when search finds a match")
        void returnsCandidateWithVector() {
            List<Float> vector = List.of(0.1f, 0.2f, 0.3f);
            SearchFilter filter = SearchFilter.publicOnly();
            com.semanticcache.backend.dto.SimilaritySearchResults searchResults =
                    new com.semanticcache.backend.dto.SimilaritySearchResults(List.of(sampleCandidate), vector);

            when(mockSimilaritySearchService.searchWithVector("test query", 1, filter))
                    .thenReturn(searchResults);

            com.semanticcache.backend.dto.CacheRetrievalResult result =
                    retrievalService.findBestCandidateWithVector("  test query  ", filter);

            verify(mockSimilaritySearchService, org.mockito.Mockito.times(1))
                    .searchWithVector("test query", 1, filter);

            assertTrue(result.candidate().isPresent());
            assertSame(sampleCandidate, result.candidate().get());
            assertEquals(vector, result.queryVector());
        }

        @Test
        @DisplayName("Returns Optional.empty() candidate while retaining vector when search returns empty")
        void returnsEmptyCandidateWithVector() {
            List<Float> vector = List.of(0.4f, 0.5f, 0.6f);
            SearchFilter filter = SearchFilter.publicOnly();
            com.semanticcache.backend.dto.SimilaritySearchResults searchResults =
                    new com.semanticcache.backend.dto.SimilaritySearchResults(Collections.emptyList(), vector);

            when(mockSimilaritySearchService.searchWithVector("unknown query", 1, filter))
                    .thenReturn(searchResults);

            com.semanticcache.backend.dto.CacheRetrievalResult result =
                    retrievalService.findBestCandidateWithVector("unknown query", filter);

            assertTrue(result.candidate().isEmpty());
            assertEquals(vector, result.queryVector());
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\t", "\n"})
        @DisplayName("Rejects null or blank queries")
        void rejectsNullOrBlankQuery(String invalidQuery) {
            assertThrows(IllegalArgumentException.class, () ->
                    retrievalService.findBestCandidateWithVector(invalidQuery, SearchFilter.publicOnly()));
            verify(mockSimilaritySearchService, never()).searchWithVector(any(), anyInt(), any());
        }

        @Test
        @DisplayName("Rejects null SearchFilter")
        void rejectsNullFilter() {
            assertThrows(NullPointerException.class, () ->
                    retrievalService.findBestCandidateWithVector("query", null));
            verify(mockSimilaritySearchService, never()).searchWithVector(any(), anyInt(), any());
        }
    }

    @Nested
    @DisplayName("Exception Propagation")
    class ExceptionPropagationTests {

        @Test
        @DisplayName("Propagates EmbeddingException from search service")
        void propagatesEmbeddingException() {
            EmbeddingException ex = new EmbeddingException("Embedding failure");
            when(mockSimilaritySearchService.search("fail query", 1, SearchFilter.publicOnly())).thenThrow(ex);

            EmbeddingException thrown = assertThrows(
                    EmbeddingException.class,
                    () -> retrievalService.findBestCandidate("fail query")
            );
            assertSame(ex, thrown);
        }

        @Test
        @DisplayName("Propagates QdrantOperationException from search service")
        void propagatesQdrantOperationException() {
            QdrantOperationException ex = new QdrantOperationException("Database error");
            when(mockSimilaritySearchService.search("fail query", 1, SearchFilter.publicOnly())).thenThrow(ex);

            QdrantOperationException thrown = assertThrows(
                    QdrantOperationException.class,
                    () -> retrievalService.findBestCandidate("fail query")
            );
            assertSame(ex, thrown);
        }
    }
}
