package com.semanticcache.backend.service;

import com.semanticcache.backend.dto.CacheLookupResult;
import com.semanticcache.backend.dto.CacheRequest;
import com.semanticcache.backend.dto.CacheResponse;
import com.semanticcache.backend.dto.SearchFilter;
import com.semanticcache.backend.dto.SearchResult;
import com.semanticcache.backend.exception.EmbeddingException;
import com.semanticcache.backend.exception.GeminiApiException;
import com.semanticcache.backend.exception.GeminiMissingCredentialsException;
import com.semanticcache.backend.exception.QdrantOperationException;
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

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CacheServiceTest {

    @Mock
    private CacheRetrievalService mockCacheRetrievalService;

    @Mock
    private CacheDecisionService mockCacheDecisionService;

    @Mock
    private GeminiProvider mockGeminiProvider;

    private CacheService cacheService;

    private SearchResult sampleCandidate;

    @BeforeEach
    void setUp() {
        cacheService = new CacheService(mockCacheRetrievalService, mockCacheDecisionService, mockGeminiProvider);

        sampleCandidate = new SearchResult(
                UUID.randomUUID(),
                "What is semantic caching?",
                "Semantic caching stores embeddings to find similar queries.",
                "gemini-3.8-flash",
                Instant.parse("2026-11-09T10:00:00Z"),
                "gemini-embedding-001",
                0.94f,
                null,
                "PUBLIC"
        );
    }

    @Nested
    @DisplayName("Constructor and Input Validation Tests")
    class ValidationTests {

        @Test
        @DisplayName("Rejects null CacheRetrievalService with NullPointerException")
        void rejectsNullRetrievalService() {
            NullPointerException ex = assertThrows(
                    NullPointerException.class,
                    () -> new CacheService(null, mockCacheDecisionService, mockGeminiProvider)
            );
            assertEquals("cacheRetrievalService must not be null", ex.getMessage());
        }

        @Test
        @DisplayName("Rejects null CacheDecisionService with NullPointerException")
        void rejectsNullDecisionService() {
            NullPointerException ex = assertThrows(
                    NullPointerException.class,
                    () -> new CacheService(mockCacheRetrievalService, null, mockGeminiProvider)
            );
            assertEquals("cacheDecisionService must not be null", ex.getMessage());
        }

        @Test
        @DisplayName("Rejects null GeminiProvider with NullPointerException")
        void rejectsNullGeminiProvider() {
            NullPointerException ex = assertThrows(
                    NullPointerException.class,
                    () -> new CacheService(mockCacheRetrievalService, mockCacheDecisionService, null)
            );
            assertEquals("geminiProvider must not be null", ex.getMessage());
        }

        @Test
        @DisplayName("Rejects null CacheRequest with IllegalArgumentException")
        void rejectsNullRequest() {
            IllegalArgumentException ex = assertThrows(
                    IllegalArgumentException.class,
                    () -> cacheService.processPrompt(null)
            );
            assertEquals("Prompt must not be null or blank", ex.getMessage());
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\t", "\n"})
        @DisplayName("Rejects null or blank prompt with IllegalArgumentException")
        void rejectsNullOrBlankPrompt(String invalidPrompt) {
            CacheRequest request = new CacheRequest(invalidPrompt);
            IllegalArgumentException ex = assertThrows(
                    IllegalArgumentException.class,
                    () -> cacheService.processPrompt(request)
            );
            assertEquals("Prompt must not be null or blank", ex.getMessage());
            verify(mockCacheRetrievalService, never()).findBestCandidate(any(), any());
            verify(mockGeminiProvider, never()).generateAnswer(any());
        }
    }

    @Nested
    @DisplayName("Cache Hit Operations")
    class CacheHitTests {

        @Test
        @DisplayName("Returns cached answer and cacheHit=true when candidate exceeds similarity threshold")
        void returnsCachedAnswerOnHit() {
            String prompt = "What is semantic caching?";
            CacheRequest request = new CacheRequest(prompt);
            Optional<SearchResult> candidateOpt = Optional.of(sampleCandidate);
            CacheLookupResult hitDecision = CacheLookupResult.hit(sampleCandidate);

            when(mockCacheRetrievalService.findBestCandidate(eq(prompt), eq(SearchFilter.publicOnly())))
                    .thenReturn(candidateOpt);
            when(mockCacheDecisionService.evaluate(eq(candidateOpt), eq(SearchFilter.publicOnly())))
                    .thenReturn(hitDecision);

            CacheResponse response = cacheService.processPrompt(request);

            assertNotNull(response);
            assertEquals("Semantic caching stores embeddings to find similar queries.", response.answer());
            assertTrue(response.cacheHit());

            verify(mockCacheRetrievalService).findBestCandidate(eq(prompt), eq(SearchFilter.publicOnly()));
            verify(mockCacheDecisionService).evaluate(eq(candidateOpt), eq(SearchFilter.publicOnly()));
            verify(mockGeminiProvider, never()).generateAnswer(anyString());
        }

        @Test
        @DisplayName("Trims input prompt before requesting retrieval on cache hit")
        void trimsPromptBeforeRetrieval() {
            String rawPrompt = "   What is semantic caching?   ";
            String trimmedPrompt = "What is semantic caching?";
            CacheRequest request = new CacheRequest(rawPrompt);
            Optional<SearchResult> candidateOpt = Optional.of(sampleCandidate);
            CacheLookupResult hitDecision = CacheLookupResult.hit(sampleCandidate);

            when(mockCacheRetrievalService.findBestCandidate(eq(trimmedPrompt), eq(SearchFilter.publicOnly())))
                    .thenReturn(candidateOpt);
            when(mockCacheDecisionService.evaluate(eq(candidateOpt), eq(SearchFilter.publicOnly())))
                    .thenReturn(hitDecision);

            CacheResponse response = cacheService.processPrompt(request);

            assertNotNull(response);
            assertTrue(response.cacheHit());
            verify(mockCacheRetrievalService).findBestCandidate(eq(trimmedPrompt), eq(SearchFilter.publicOnly()));
            verify(mockGeminiProvider, never()).generateAnswer(anyString());
        }
    }

    @Nested
    @DisplayName("Cache Miss Operations")
    class CacheMissTests {

        @Test
        @DisplayName("Generates answer via Gemini and returns cacheHit=false when no candidate is found")
        void generatesAnswerWhenNoCandidateFound() {
            String prompt = "Explain quantum computing";
            CacheRequest request = new CacheRequest(prompt);
            Optional<SearchResult> emptyCandidate = Optional.empty();
            CacheLookupResult missDecision = CacheLookupResult.missNoCandidate();

            when(mockCacheRetrievalService.findBestCandidate(eq(prompt), eq(SearchFilter.publicOnly())))
                    .thenReturn(emptyCandidate);
            when(mockCacheDecisionService.evaluate(eq(emptyCandidate), eq(SearchFilter.publicOnly())))
                    .thenReturn(missDecision);
            when(mockGeminiProvider.generateAnswer(prompt))
                    .thenReturn("Quantum computing uses qubits for computation.");

            CacheResponse response = cacheService.processPrompt(request);

            assertNotNull(response);
            assertEquals("Quantum computing uses qubits for computation.", response.answer());
            assertFalse(response.cacheHit());

            verify(mockCacheRetrievalService).findBestCandidate(eq(prompt), eq(SearchFilter.publicOnly()));
            verify(mockCacheDecisionService).evaluate(eq(emptyCandidate), eq(SearchFilter.publicOnly()));
            verify(mockGeminiProvider).generateAnswer(prompt);
        }

        @Test
        @DisplayName("Generates answer via Gemini and returns cacheHit=false when candidate score is below threshold")
        void generatesAnswerWhenScoreBelowThreshold() {
            String prompt = "Explain caching in distributed systems";
            CacheRequest request = new CacheRequest(prompt);
            Optional<SearchResult> candidateOpt = Optional.of(sampleCandidate);
            CacheLookupResult missDecision = CacheLookupResult.missBelowThreshold(sampleCandidate);

            when(mockCacheRetrievalService.findBestCandidate(eq(prompt), eq(SearchFilter.publicOnly())))
                    .thenReturn(candidateOpt);
            when(mockCacheDecisionService.evaluate(eq(candidateOpt), eq(SearchFilter.publicOnly())))
                    .thenReturn(missDecision);
            when(mockGeminiProvider.generateAnswer(prompt))
                    .thenReturn("Caching stores frequently accessed data in memory.");

            CacheResponse response = cacheService.processPrompt(request);

            assertNotNull(response);
            assertEquals("Caching stores frequently accessed data in memory.", response.answer());
            assertFalse(response.cacheHit());

            verify(mockGeminiProvider).generateAnswer(prompt);
        }

        @Test
        @DisplayName("Generates answer via Gemini and returns cacheHit=false when candidate fails privacy restriction")
        void generatesAnswerWhenPrivacyRestrictionTriggered() {
            String prompt = "Explain confidential internal architecture";
            CacheRequest request = new CacheRequest(prompt);
            Optional<SearchResult> candidateOpt = Optional.of(sampleCandidate);
            CacheLookupResult missDecision = CacheLookupResult.missPrivacyRestriction(0.99f, "CANDIDATE_NOT_PUBLIC");

            when(mockCacheRetrievalService.findBestCandidate(eq(prompt), eq(SearchFilter.publicOnly())))
                    .thenReturn(candidateOpt);
            when(mockCacheDecisionService.evaluate(eq(candidateOpt), eq(SearchFilter.publicOnly())))
                    .thenReturn(missDecision);
            when(mockGeminiProvider.generateAnswer(prompt))
                    .thenReturn("Generated fresh public response.");

            CacheResponse response = cacheService.processPrompt(request);

            assertNotNull(response);
            assertEquals("Generated fresh public response.", response.answer());
            assertFalse(response.cacheHit());

            verify(mockGeminiProvider).generateAnswer(prompt);
        }
    }

    @Nested
    @DisplayName("Privacy and Filter Invariant Tests (Option A)")
    class PrivacyInvariantTests {

        @Test
        @DisplayName("Always passes SearchFilter.publicOnly() to both retrieval and decision services")
        void enforcesPublicOnlyFilterInvariant() {
            String prompt = "Privacy test prompt";
            CacheRequest request = new CacheRequest(prompt);
            Optional<SearchResult> candidateOpt = Optional.of(sampleCandidate);
            CacheLookupResult hitDecision = CacheLookupResult.hit(sampleCandidate);

            when(mockCacheRetrievalService.findBestCandidate(eq(prompt), any(SearchFilter.class)))
                    .thenReturn(candidateOpt);
            when(mockCacheDecisionService.evaluate(eq(candidateOpt), any(SearchFilter.class)))
                    .thenReturn(hitDecision);

            cacheService.processPrompt(request);

            ArgumentCaptor<SearchFilter> retrievalFilterCaptor = ArgumentCaptor.forClass(SearchFilter.class);
            verify(mockCacheRetrievalService).findBestCandidate(eq(prompt), retrievalFilterCaptor.capture());
            assertTrue(retrievalFilterCaptor.getValue().isPublicOnly());
            assertFalse(retrievalFilterCaptor.getValue().hasOwner());

            ArgumentCaptor<SearchFilter> decisionFilterCaptor = ArgumentCaptor.forClass(SearchFilter.class);
            verify(mockCacheDecisionService).evaluate(eq(candidateOpt), decisionFilterCaptor.capture());
            assertTrue(decisionFilterCaptor.getValue().isPublicOnly());
            assertFalse(decisionFilterCaptor.getValue().hasOwner());
        }
    }

    @Nested
    @DisplayName("Inconsistent State and Null Validation Tests")
    class InconsistentStateTests {

        @Test
        @DisplayName("Throws IllegalStateException when CacheRetrievalService returns unexpected null")
        void throwsWhenRetrievalReturnsNull() {
            String prompt = "test prompt";
            when(mockCacheRetrievalService.findBestCandidate(eq(prompt), eq(SearchFilter.publicOnly())))
                    .thenReturn(null);

            IllegalStateException ex = assertThrows(
                    IllegalStateException.class,
                    () -> cacheService.processPrompt(new CacheRequest(prompt))
            );
            assertTrue(ex.getMessage().contains("CacheRetrievalService returned an unexpected null"));
            verify(mockCacheDecisionService, never()).evaluate(any(), any());
            verify(mockGeminiProvider, never()).generateAnswer(any());
        }

        @Test
        @DisplayName("Throws IllegalStateException when CacheDecisionService returns unexpected null")
        void throwsWhenDecisionReturnsNull() {
            String prompt = "test prompt";
            Optional<SearchResult> candidateOpt = Optional.of(sampleCandidate);
            when(mockCacheRetrievalService.findBestCandidate(eq(prompt), eq(SearchFilter.publicOnly())))
                    .thenReturn(candidateOpt);
            when(mockCacheDecisionService.evaluate(eq(candidateOpt), eq(SearchFilter.publicOnly())))
                    .thenReturn(null);

            IllegalStateException ex = assertThrows(
                    IllegalStateException.class,
                    () -> cacheService.processPrompt(new CacheRequest(prompt))
            );
            assertTrue(ex.getMessage().contains("CacheDecisionService returned an unexpected null"));
            verify(mockGeminiProvider, never()).generateAnswer(any());
        }

        @Test
        @DisplayName("Throws IllegalStateException when CacheLookupResult has isHit=true but empty matchedResult")
        void throwsWhenHitHasEmptyMatchedResult() {
            String prompt = "test prompt";
            Optional<SearchResult> candidateOpt = Optional.of(sampleCandidate);
            // Inconsistent result: isHit=true, but matchedResult is empty
            CacheLookupResult inconsistentHit = new CacheLookupResult(true, Optional.empty(), 0.95f, "MATCH_EXCEEDS_SIMILARITY_THRESHOLD");

            when(mockCacheRetrievalService.findBestCandidate(eq(prompt), eq(SearchFilter.publicOnly())))
                    .thenReturn(candidateOpt);
            when(mockCacheDecisionService.evaluate(eq(candidateOpt), eq(SearchFilter.publicOnly())))
                    .thenReturn(inconsistentHit);

            IllegalStateException ex = assertThrows(
                    IllegalStateException.class,
                    () -> cacheService.processPrompt(new CacheRequest(prompt))
            );
            assertTrue(ex.getMessage().contains("isHit is true but matchedResult is empty"));
            verify(mockGeminiProvider, never()).generateAnswer(any());
        }
    }

    @Nested
    @DisplayName("Exception Propagation Tests")
    class ExceptionPropagationTests {

        @Test
        @DisplayName("Propagates QdrantOperationException from CacheRetrievalService")
        void propagatesQdrantExceptionFromRetrieval() {
            String prompt = "test query";
            QdrantOperationException rootCause = new QdrantOperationException("Qdrant connection failure");
            when(mockCacheRetrievalService.findBestCandidate(eq(prompt), eq(SearchFilter.publicOnly())))
                    .thenThrow(rootCause);

            QdrantOperationException thrown = assertThrows(
                    QdrantOperationException.class,
                    () -> cacheService.processPrompt(new CacheRequest(prompt))
            );
            assertEquals("Qdrant connection failure", thrown.getMessage());
            verify(mockGeminiProvider, never()).generateAnswer(any());
        }

        @Test
        @DisplayName("Propagates EmbeddingException from CacheRetrievalService")
        void propagatesEmbeddingExceptionFromRetrieval() {
            String prompt = "test query";
            EmbeddingException rootCause = new EmbeddingException("Failed to generate embedding");
            when(mockCacheRetrievalService.findBestCandidate(eq(prompt), eq(SearchFilter.publicOnly())))
                    .thenThrow(rootCause);

            EmbeddingException thrown = assertThrows(
                    EmbeddingException.class,
                    () -> cacheService.processPrompt(new CacheRequest(prompt))
            );
            assertEquals("Failed to generate embedding", thrown.getMessage());
            verify(mockGeminiProvider, never()).generateAnswer(any());
        }

        @Test
        @DisplayName("Propagates GeminiApiException when Gemini fails on cache miss")
        void propagatesGeminiApiExceptionOnMiss() {
            String prompt = "test query";
            Optional<SearchResult> emptyCandidate = Optional.empty();
            CacheLookupResult missDecision = CacheLookupResult.missNoCandidate();

            when(mockCacheRetrievalService.findBestCandidate(eq(prompt), eq(SearchFilter.publicOnly())))
                    .thenReturn(emptyCandidate);
            when(mockCacheDecisionService.evaluate(eq(emptyCandidate), eq(SearchFilter.publicOnly())))
                    .thenReturn(missDecision);
            when(mockGeminiProvider.generateAnswer(prompt))
                    .thenThrow(new GeminiApiException("Gemini API quota exceeded"));

            GeminiApiException thrown = assertThrows(
                    GeminiApiException.class,
                    () -> cacheService.processPrompt(new CacheRequest(prompt))
            );
            assertEquals("Gemini API quota exceeded", thrown.getMessage());
        }

        @Test
        @DisplayName("Propagates GeminiMissingCredentialsException when credentials missing on cache miss")
        void propagatesMissingCredentialsExceptionOnMiss() {
            String prompt = "test query";
            Optional<SearchResult> emptyCandidate = Optional.empty();
            CacheLookupResult missDecision = CacheLookupResult.missNoCandidate();

            when(mockCacheRetrievalService.findBestCandidate(eq(prompt), eq(SearchFilter.publicOnly())))
                    .thenReturn(emptyCandidate);
            when(mockCacheDecisionService.evaluate(eq(emptyCandidate), eq(SearchFilter.publicOnly())))
                    .thenReturn(missDecision);
            when(mockGeminiProvider.generateAnswer(prompt))
                    .thenThrow(new GeminiMissingCredentialsException("GOOGLE_API_KEY environment variable is not set."));

            GeminiMissingCredentialsException thrown = assertThrows(
                    GeminiMissingCredentialsException.class,
                    () -> cacheService.processPrompt(new CacheRequest(prompt))
            );
            assertEquals("GOOGLE_API_KEY environment variable is not set.", thrown.getMessage());
        }
    }

    @Nested
    @DisplayName("Non-Persistence Architectural Verification Tests")
    class NonPersistenceTests {

        @Test
        @DisplayName("CacheService contains no persistence dependencies or write components")
        void verifiesNoPersistenceDependenciesExist() {
            Field[] fields = CacheService.class.getDeclaredFields();
            for (Field field : fields) {
                String fieldTypeName = field.getType().getName().toLowerCase();
                assertFalse(fieldTypeName.contains("repository"), "CacheService must not depend on repository: " + field.getName());
                assertFalse(fieldTypeName.contains("qdrant"), "CacheService must not depend on qdrant: " + field.getName());
                assertFalse(fieldTypeName.contains("upsert"), "CacheService must not contain upsert dependency: " + field.getName());
                assertFalse(fieldTypeName.contains("write"), "CacheService must not contain write dependency: " + field.getName());
            }
        }

        @Test
        @DisplayName("Cache miss path performs only retrieval, evaluation, and Gemini generation without write operations")
        void verifiesMissPathPerformsNoPersistence() {
            String prompt = "Non-persistence test";
            CacheRequest request = new CacheRequest(prompt);
            Optional<SearchResult> emptyCandidate = Optional.empty();
            CacheLookupResult missDecision = CacheLookupResult.missNoCandidate();

            when(mockCacheRetrievalService.findBestCandidate(eq(prompt), eq(SearchFilter.publicOnly())))
                    .thenReturn(emptyCandidate);
            when(mockCacheDecisionService.evaluate(eq(emptyCandidate), eq(SearchFilter.publicOnly())))
                    .thenReturn(missDecision);
            when(mockGeminiProvider.generateAnswer(prompt))
                    .thenReturn("Answer generated without persistence.");

            CacheResponse response = cacheService.processPrompt(request);

            assertNotNull(response);
            assertFalse(response.cacheHit());

            // Verify interactions on mocks - only retrieval, decision, and gemini answer generation occurred
            verify(mockCacheRetrievalService).findBestCandidate(eq(prompt), eq(SearchFilter.publicOnly()));
            verify(mockCacheDecisionService).evaluate(eq(emptyCandidate), eq(SearchFilter.publicOnly()));
            verify(mockGeminiProvider).generateAnswer(prompt);

            verifyNoMoreInteractions(mockCacheRetrievalService, mockCacheDecisionService, mockGeminiProvider);
        }
    }
}
