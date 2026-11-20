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
import com.semanticcache.backend.telemetry.CacheMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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

import com.semanticcache.backend.dto.CacheRetrievalResult;
import com.semanticcache.backend.dto.VectorRecord;
import com.semanticcache.backend.repository.VectorRepository;
import java.util.Collections;
import java.util.List;

@ExtendWith(MockitoExtension.class)
class CacheServiceTest {

    @Mock
    private CacheRetrievalService mockCacheRetrievalService;

    @Mock
    private CacheDecisionService mockCacheDecisionService;

    @Mock
    private GeminiProvider mockGeminiProvider;

    @Mock
    private VectorRepository mockVectorRepository;

    private SimpleMeterRegistry meterRegistry;
    private CacheMetrics cacheMetrics;
    private CacheService cacheService;

    private SearchResult sampleCandidate;
    private List<Float> sampleQueryVector;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        cacheMetrics = new CacheMetrics(meterRegistry);
        cacheService = new CacheService(
                mockCacheRetrievalService,
                mockCacheDecisionService,
                mockGeminiProvider,
                mockVectorRepository,
                cacheMetrics,
                "gemini-3.8-flash",
                "gemini-embedding-001"
        );

        sampleQueryVector = List.of(0.1f, 0.2f, 0.3f);

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
                    () -> new CacheService(null, mockCacheDecisionService, mockGeminiProvider, cacheMetrics)
            );
            assertEquals("cacheRetrievalService must not be null", ex.getMessage());
        }

        @Test
        @DisplayName("Rejects null CacheDecisionService with NullPointerException")
        void rejectsNullDecisionService() {
            NullPointerException ex = assertThrows(
                    NullPointerException.class,
                    () -> new CacheService(mockCacheRetrievalService, null, mockGeminiProvider, cacheMetrics)
            );
            assertEquals("cacheDecisionService must not be null", ex.getMessage());
        }

        @Test
        @DisplayName("Rejects null GeminiProvider with NullPointerException")
        void rejectsNullGeminiProvider() {
            NullPointerException ex = assertThrows(
                    NullPointerException.class,
                    () -> new CacheService(mockCacheRetrievalService, mockCacheDecisionService, null, cacheMetrics)
            );
            assertEquals("geminiProvider must not be null", ex.getMessage());
        }

        @Test
        @DisplayName("Rejects null CacheMetrics with NullPointerException")
        void rejectsNullCacheMetrics() {
            NullPointerException ex = assertThrows(
                    NullPointerException.class,
                    () -> new CacheService(mockCacheRetrievalService, mockCacheDecisionService, mockGeminiProvider, null)
            );
            assertEquals("cacheMetrics must not be null", ex.getMessage());
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
            verify(mockCacheRetrievalService, never()).findBestCandidateWithVector(any(), any());
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

            when(mockCacheRetrievalService.findBestCandidateWithVector(eq(prompt), eq(SearchFilter.publicOnly())))
                    .thenReturn(new CacheRetrievalResult(candidateOpt, sampleQueryVector));
            when(mockCacheDecisionService.evaluate(eq(candidateOpt), eq(SearchFilter.publicOnly())))
                    .thenReturn(hitDecision);

            CacheResponse response = cacheService.processPrompt(request);

            assertNotNull(response);
            assertEquals("Semantic caching stores embeddings to find similar queries.", response.answer());
            assertTrue(response.cacheHit());

            verify(mockCacheRetrievalService).findBestCandidateWithVector(eq(prompt), eq(SearchFilter.publicOnly()));
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

            when(mockCacheRetrievalService.findBestCandidateWithVector(eq(trimmedPrompt), eq(SearchFilter.publicOnly())))
                    .thenReturn(new CacheRetrievalResult(candidateOpt, sampleQueryVector));
            when(mockCacheDecisionService.evaluate(eq(candidateOpt), eq(SearchFilter.publicOnly())))
                    .thenReturn(hitDecision);

            CacheResponse response = cacheService.processPrompt(request);

            assertNotNull(response);
            assertTrue(response.cacheHit());
            verify(mockCacheRetrievalService).findBestCandidateWithVector(eq(trimmedPrompt), eq(SearchFilter.publicOnly()));
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

            when(mockCacheRetrievalService.findBestCandidateWithVector(eq(prompt), eq(SearchFilter.publicOnly())))
                    .thenReturn(new CacheRetrievalResult(emptyCandidate, sampleQueryVector));
            when(mockCacheDecisionService.evaluate(eq(emptyCandidate), eq(SearchFilter.publicOnly())))
                    .thenReturn(missDecision);
            when(mockGeminiProvider.generateAnswer(prompt))
                    .thenReturn("Quantum computing uses qubits for computation.");

            CacheResponse response = cacheService.processPrompt(request);

            assertNotNull(response);
            assertEquals("Quantum computing uses qubits for computation.", response.answer());
            assertFalse(response.cacheHit());

            verify(mockCacheRetrievalService).findBestCandidateWithVector(eq(prompt), eq(SearchFilter.publicOnly()));
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

            when(mockCacheRetrievalService.findBestCandidateWithVector(eq(prompt), eq(SearchFilter.publicOnly())))
                    .thenReturn(new CacheRetrievalResult(candidateOpt, sampleQueryVector));
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

            when(mockCacheRetrievalService.findBestCandidateWithVector(eq(prompt), eq(SearchFilter.publicOnly())))
                    .thenReturn(new CacheRetrievalResult(candidateOpt, sampleQueryVector));
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

            when(mockCacheRetrievalService.findBestCandidateWithVector(eq(prompt), any(SearchFilter.class)))
                    .thenReturn(new CacheRetrievalResult(candidateOpt, sampleQueryVector));
            when(mockCacheDecisionService.evaluate(eq(candidateOpt), any(SearchFilter.class)))
                    .thenReturn(hitDecision);

            cacheService.processPrompt(request);

            ArgumentCaptor<SearchFilter> retrievalFilterCaptor = ArgumentCaptor.forClass(SearchFilter.class);
            verify(mockCacheRetrievalService).findBestCandidateWithVector(eq(prompt), retrievalFilterCaptor.capture());
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
            when(mockCacheRetrievalService.findBestCandidateWithVector(eq(prompt), eq(SearchFilter.publicOnly())))
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
            when(mockCacheRetrievalService.findBestCandidateWithVector(eq(prompt), eq(SearchFilter.publicOnly())))
                    .thenReturn(new CacheRetrievalResult(candidateOpt, sampleQueryVector));
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

            when(mockCacheRetrievalService.findBestCandidateWithVector(eq(prompt), eq(SearchFilter.publicOnly())))
                    .thenReturn(new CacheRetrievalResult(candidateOpt, sampleQueryVector));
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
            when(mockCacheRetrievalService.findBestCandidateWithVector(eq(prompt), eq(SearchFilter.publicOnly())))
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
            when(mockCacheRetrievalService.findBestCandidateWithVector(eq(prompt), eq(SearchFilter.publicOnly())))
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

            when(mockCacheRetrievalService.findBestCandidateWithVector(eq(prompt), eq(SearchFilter.publicOnly())))
                    .thenReturn(new CacheRetrievalResult(emptyCandidate, sampleQueryVector));
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

            when(mockCacheRetrievalService.findBestCandidateWithVector(eq(prompt), eq(SearchFilter.publicOnly())))
                    .thenReturn(new CacheRetrievalResult(emptyCandidate, sampleQueryVector));
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
    @DisplayName("Write-Back Operations")
    class WriteBackTests {

        @Test
        @DisplayName("Cache miss persists newly generated answer to vector repository with exact query vector")
        void persistsAnswerOnCacheMiss() {
            String rawPrompt = "  What is distributed caching?  ";
            String trimmedPrompt = "What is distributed caching?";
            CacheRequest request = new CacheRequest(rawPrompt);
            Optional<SearchResult> emptyCandidate = Optional.empty();
            CacheLookupResult missDecision = CacheLookupResult.missNoCandidate();
            String generatedAnswer = "Distributed caching stores data across multiple nodes.";

            when(mockCacheRetrievalService.findBestCandidateWithVector(eq(trimmedPrompt), eq(SearchFilter.publicOnly())))
                    .thenReturn(new CacheRetrievalResult(emptyCandidate, sampleQueryVector));
            when(mockCacheDecisionService.evaluate(eq(emptyCandidate), eq(SearchFilter.publicOnly())))
                    .thenReturn(missDecision);
            when(mockGeminiProvider.generateAnswer(trimmedPrompt))
                    .thenReturn(generatedAnswer);

            CacheResponse response = cacheService.processPrompt(request);

            assertNotNull(response);
            assertEquals(generatedAnswer, response.answer());
            assertFalse(response.cacheHit());

            ArgumentCaptor<VectorRecord> recordCaptor = ArgumentCaptor.forClass(VectorRecord.class);
            verify(mockVectorRepository).upsert(recordCaptor.capture());

            VectorRecord persisted = recordCaptor.getValue();
            assertNotNull(persisted.id());
            assertEquals(sampleQueryVector, persisted.vector());
            assertEquals(trimmedPrompt, persisted.prompt());
            assertEquals(generatedAnswer, persisted.answer());
            assertEquals("gemini-3.8-flash", persisted.model());
            assertEquals("gemini-embedding-001", persisted.embeddingModel());
            assertNotNull(persisted.createdAt());
        }

        @Test
        @DisplayName("Cache hit does not invoke Gemini and does not persist to vector repository")
        void hitDoesNotUpsert() {
            String prompt = "What is semantic caching?";
            CacheRequest request = new CacheRequest(prompt);
            Optional<SearchResult> candidateOpt = Optional.of(sampleCandidate);
            CacheLookupResult hitDecision = CacheLookupResult.hit(sampleCandidate);

            when(mockCacheRetrievalService.findBestCandidateWithVector(eq(prompt), eq(SearchFilter.publicOnly())))
                    .thenReturn(new CacheRetrievalResult(candidateOpt, sampleQueryVector));
            when(mockCacheDecisionService.evaluate(eq(candidateOpt), eq(SearchFilter.publicOnly())))
                    .thenReturn(hitDecision);

            CacheResponse response = cacheService.processPrompt(request);

            assertTrue(response.cacheHit());
            verify(mockGeminiProvider, never()).generateAnswer(anyString());
            verify(mockVectorRepository, never()).upsert(any());
        }

        @Test
        @DisplayName("Qdrant write failure still returns generated answer, cacheHit remains false, and no request error metric recorded")
        void writeFailureStillReturnsAnswerWithoutErrorMetric() {
            String prompt = "Failing write prompt";
            CacheRequest request = new CacheRequest(prompt);
            Optional<SearchResult> emptyCandidate = Optional.empty();
            CacheLookupResult missDecision = CacheLookupResult.missNoCandidate();
            String generatedAnswer = "Successfully generated answer.";

            when(mockCacheRetrievalService.findBestCandidateWithVector(eq(prompt), eq(SearchFilter.publicOnly())))
                    .thenReturn(new CacheRetrievalResult(emptyCandidate, sampleQueryVector));
            when(mockCacheDecisionService.evaluate(eq(emptyCandidate), eq(SearchFilter.publicOnly())))
                    .thenReturn(missDecision);
            when(mockGeminiProvider.generateAnswer(prompt))
                    .thenReturn(generatedAnswer);
            org.mockito.Mockito.doThrow(new RuntimeException("Qdrant write timeout"))
                    .when(mockVectorRepository).upsert(any());

            CacheResponse response = cacheService.processPrompt(request);

            assertNotNull(response);
            assertEquals(generatedAnswer, response.answer());
            assertFalse(response.cacheHit());

            // Verify write was attempted
            verify(mockVectorRepository).upsert(any());

            // Verify that cache-request error metric was NOT recorded
            assertEquals(0.0, meterRegistry.find(CacheMetrics.METRIC_REQUESTS_TOTAL)
                    .tag(CacheMetrics.TAG_OUTCOME, "error").counter().count());

            // Verify successful miss outcome was recorded
            assertEquals(1.0, meterRegistry.find(CacheMetrics.METRIC_REQUESTS_TOTAL)
                    .tag(CacheMetrics.TAG_OUTCOME, "miss")
                    .tag(CacheMetrics.TAG_STATUS, "success").counter().count());
        }

        @Test
        @DisplayName("Gemini generation failure does not call Qdrant upsert and records error telemetry")
        void geminiFailureDoesNotUpsert() {
            String prompt = "Prompt failing generation";
            CacheRequest request = new CacheRequest(prompt);
            Optional<SearchResult> emptyCandidate = Optional.empty();
            CacheLookupResult missDecision = CacheLookupResult.missNoCandidate();

            when(mockCacheRetrievalService.findBestCandidateWithVector(eq(prompt), eq(SearchFilter.publicOnly())))
                    .thenReturn(new CacheRetrievalResult(emptyCandidate, sampleQueryVector));
            when(mockCacheDecisionService.evaluate(eq(emptyCandidate), eq(SearchFilter.publicOnly())))
                    .thenReturn(missDecision);
            when(mockGeminiProvider.generateAnswer(prompt))
                    .thenThrow(new GeminiApiException("Quota exceeded"));

            assertThrows(GeminiApiException.class, () -> cacheService.processPrompt(request));

            verify(mockVectorRepository, never()).upsert(any());
            assertEquals(1.0, meterRegistry.find(CacheMetrics.METRIC_REQUESTS_TOTAL)
                    .tag(CacheMetrics.TAG_OUTCOME, "error").counter().count());
        }

        @Test
        @DisplayName("Retrieval failure does not call Gemini generation or Qdrant upsert")
        void retrievalFailureDoesNotGenerateOrUpsert() {
            String prompt = "Prompt failing retrieval";
            CacheRequest request = new CacheRequest(prompt);

            when(mockCacheRetrievalService.findBestCandidateWithVector(eq(prompt), eq(SearchFilter.publicOnly())))
                    .thenThrow(new EmbeddingException("Embedding service offline"));

            assertThrows(EmbeddingException.class, () -> cacheService.processPrompt(request));

            verify(mockGeminiProvider, never()).generateAnswer(any());
            verify(mockVectorRepository, never()).upsert(any());
            assertEquals(1.0, meterRegistry.find(CacheMetrics.METRIC_REQUESTS_TOTAL)
                    .tag(CacheMetrics.TAG_OUTCOME, "error").counter().count());
        }
    }

    @Nested
    @DisplayName("Metrics Lifecycle and Telemetry Verification Tests")
    class MetricsLifecycleTests {

        @Test
        @DisplayName("Cache hit records similarity score, one hit request outcome, and one duration sample")
        void cacheHitRecordsExpectedMetrics() {
            String prompt = "What is semantic caching?";
            CacheRequest request = new CacheRequest(prompt);
            Optional<SearchResult> candidateOpt = Optional.of(sampleCandidate);
            CacheLookupResult hitDecision = CacheLookupResult.hit(sampleCandidate);

            when(mockCacheRetrievalService.findBestCandidateWithVector(eq(prompt), eq(SearchFilter.publicOnly())))
                    .thenReturn(new CacheRetrievalResult(candidateOpt, sampleQueryVector));
            when(mockCacheDecisionService.evaluate(eq(candidateOpt), eq(SearchFilter.publicOnly())))
                    .thenReturn(hitDecision);

            CacheResponse response = cacheService.processPrompt(request);

            assertNotNull(response);
            assertTrue(response.cacheHit());

            // 1. Overall request counter (outcome=hit, status=success) == 1.0
            Counter requestsTotal = meterRegistry.find(CacheMetrics.METRIC_REQUESTS_TOTAL)
                    .tag(CacheMetrics.TAG_OUTCOME, "hit")
                    .tag(CacheMetrics.TAG_STATUS, "success")
                    .counter();
            assertNotNull(requestsTotal);
            assertEquals(1.0, requestsTotal.count(), "Exactly one hit request must be recorded");

            // 2. Cache hit counter (visibility=PUBLIC) == 1.0
            Counter hitCounter = meterRegistry.find(CacheMetrics.METRIC_CACHE_HITS)
                    .tag(CacheMetrics.TAG_VISIBILITY, "PUBLIC")
                    .counter();
            assertNotNull(hitCounter);
            assertEquals(1.0, hitCounter.count(), "Exactly one public hit must be recorded");

            // 3. Request duration timer (outcome=hit) count == 1
            Timer durationTimer = meterRegistry.find(CacheMetrics.METRIC_REQUEST_DURATION)
                    .tag(CacheMetrics.TAG_OUTCOME, "hit")
                    .timer();
            assertNotNull(durationTimer);
            assertEquals(1, durationTimer.count(), "Exactly one duration timer sample must be recorded");

            // 4. Similarity score distribution summary count == 1, value == 0.94
            DistributionSummary scoreSummary = meterRegistry.find(CacheMetrics.METRIC_SIMILARITY_SCORE)
                    .tag(CacheMetrics.TAG_OUTCOME, "hit")
                    .summary();
            assertNotNull(scoreSummary);
            assertEquals(1, scoreSummary.count());
            assertEquals(0.94, scoreSummary.totalAmount(), 0.001);

            // 5. Zero miss or error metrics recorded
            for (CacheMetrics.MissReason reason : CacheMetrics.MissReason.values()) {
                assertEquals(0.0, meterRegistry.find(CacheMetrics.METRIC_CACHE_MISSES)
                        .tag(CacheMetrics.TAG_REASON, reason.tagValue()).counter().count());
            }
            assertEquals(0.0, meterRegistry.find(CacheMetrics.METRIC_REQUESTS_TOTAL)
                    .tag(CacheMetrics.TAG_OUTCOME, "error").counter().count());
        }

        @Test
        @DisplayName("Cache miss with below-threshold score records score, miss event, successful miss outcome, and one duration sample")
        void cacheMissBelowThresholdRecordsExpectedMetrics() {
            String prompt = "What is quantum computing?";
            CacheRequest request = new CacheRequest(prompt);
            SearchResult lowCandidate = new SearchResult(
                    UUID.randomUUID(), prompt, "Quantum answer", "gemini-3.8-flash",
                    Instant.now(), "gemini-embedding-001", 0.65f, null, "PUBLIC"
            );
            Optional<SearchResult> candidateOpt = Optional.of(lowCandidate);
            CacheLookupResult missDecision = CacheLookupResult.missBelowThreshold(lowCandidate);

            when(mockCacheRetrievalService.findBestCandidateWithVector(eq(prompt), eq(SearchFilter.publicOnly())))
                    .thenReturn(new CacheRetrievalResult(candidateOpt, sampleQueryVector));
            when(mockCacheDecisionService.evaluate(eq(candidateOpt), eq(SearchFilter.publicOnly())))
                    .thenReturn(missDecision);
            when(mockGeminiProvider.generateAnswer(prompt))
                    .thenReturn("Quantum computing uses qubits.");

            CacheResponse response = cacheService.processPrompt(request);

            assertNotNull(response);
            assertFalse(response.cacheHit());

            // 1. Candidate score is recorded under outcome=miss
            DistributionSummary scoreSummary = meterRegistry.find(CacheMetrics.METRIC_SIMILARITY_SCORE)
                    .tag(CacheMetrics.TAG_OUTCOME, "miss")
                    .summary();
            assertNotNull(scoreSummary);
            assertEquals(1, scoreSummary.count());
            assertEquals(0.65, scoreSummary.totalAmount(), 0.001);

            // 2. Miss event recorded with below_threshold reason
            Counter missCounter = meterRegistry.find(CacheMetrics.METRIC_CACHE_MISSES)
                    .tag(CacheMetrics.TAG_REASON, "below_threshold")
                    .counter();
            assertNotNull(missCounter);
            assertEquals(1.0, missCounter.count());

            // 3. Request outcome (outcome=miss, status=success) == 1.0
            Counter requestsTotal = meterRegistry.find(CacheMetrics.METRIC_REQUESTS_TOTAL)
                    .tag(CacheMetrics.TAG_OUTCOME, "miss")
                    .tag(CacheMetrics.TAG_STATUS, "success")
                    .counter();
            assertNotNull(requestsTotal);
            assertEquals(1.0, requestsTotal.count());

            // 4. Request duration timer recorded exactly once
            Timer timer = meterRegistry.find(CacheMetrics.METRIC_REQUEST_DURATION)
                    .tag(CacheMetrics.TAG_OUTCOME, "miss")
                    .timer();
            assertNotNull(timer);
            assertEquals(1, timer.count());

            // 5. Zero error metrics
            assertEquals(0.0, meterRegistry.find(CacheMetrics.METRIC_REQUESTS_TOTAL)
                    .tag(CacheMetrics.TAG_OUTCOME, "error").counter().count());
        }

        @Test
        @DisplayName("Cache miss with no candidate skips similarity score and records no_candidate miss event")
        void cacheMissNoCandidateSkipsSimilarityScore() {
            String prompt = "Unseen query";
            CacheRequest request = new CacheRequest(prompt);
            Optional<SearchResult> emptyCandidate = Optional.empty();
            CacheLookupResult missDecision = CacheLookupResult.missNoCandidate();

            when(mockCacheRetrievalService.findBestCandidateWithVector(eq(prompt), eq(SearchFilter.publicOnly())))
                    .thenReturn(new CacheRetrievalResult(emptyCandidate, sampleQueryVector));
            when(mockCacheDecisionService.evaluate(eq(emptyCandidate), eq(SearchFilter.publicOnly())))
                    .thenReturn(missDecision);
            when(mockGeminiProvider.generateAnswer(prompt))
                    .thenReturn("Generated text");

            CacheResponse response = cacheService.processPrompt(request);
            assertNotNull(response);

            // 1. Similarity score MUST NOT be recorded (candidate was empty)
            DistributionSummary scoreSummary = meterRegistry.find(CacheMetrics.METRIC_SIMILARITY_SCORE)
                    .tag(CacheMetrics.TAG_OUTCOME, "miss")
                    .summary();
            assertNotNull(scoreSummary);
            assertEquals(0, scoreSummary.count(), "No similarity score must be recorded when candidate is empty");

            // 2. Miss event recorded with no_candidate
            Counter missCounter = meterRegistry.find(CacheMetrics.METRIC_CACHE_MISSES)
                    .tag(CacheMetrics.TAG_REASON, "no_candidate")
                    .counter();
            assertNotNull(missCounter);
            assertEquals(1.0, missCounter.count());

            // 3. Request completion recorded
            Counter requestsTotal = meterRegistry.find(CacheMetrics.METRIC_REQUESTS_TOTAL)
                    .tag(CacheMetrics.TAG_OUTCOME, "miss")
                    .tag(CacheMetrics.TAG_STATUS, "success")
                    .counter();
            assertEquals(1.0, requestsTotal.count());

            Timer timer = meterRegistry.find(CacheMetrics.METRIC_REQUEST_DURATION)
                    .tag(CacheMetrics.TAG_OUTCOME, "miss")
                    .timer();
            assertEquals(1, timer.count());
        }

        @Test
        @DisplayName("Cache miss with privacy restriction records privacy_restriction miss event")
        void cacheMissPrivacyRestrictionRecordsExpectedMetrics() {
            String prompt = "Private query";
            CacheRequest request = new CacheRequest(prompt);
            SearchResult privateCandidate = new SearchResult(
                    UUID.randomUUID(), prompt, "Private answer", "gemini-3.8-flash",
                    Instant.now(), "gemini-embedding-001", 0.95f, "user-42", "PRIVATE"
            );
            Optional<SearchResult> candidateOpt = Optional.of(privateCandidate);
            CacheLookupResult privacyMiss = CacheLookupResult.missPrivacyRestriction(0.95f, "CANDIDATE_NOT_PUBLIC");

            when(mockCacheRetrievalService.findBestCandidateWithVector(eq(prompt), eq(SearchFilter.publicOnly())))
                    .thenReturn(new CacheRetrievalResult(candidateOpt, sampleQueryVector));
            when(mockCacheDecisionService.evaluate(eq(candidateOpt), eq(SearchFilter.publicOnly())))
                    .thenReturn(privacyMiss);
            when(mockGeminiProvider.generateAnswer(prompt))
                    .thenReturn("Public fallback");

            CacheResponse response = cacheService.processPrompt(request);
            assertNotNull(response);

            // Candidate existed so similarity score is recorded
            DistributionSummary scoreSummary = meterRegistry.find(CacheMetrics.METRIC_SIMILARITY_SCORE)
                    .tag(CacheMetrics.TAG_OUTCOME, "miss")
                    .summary();
            assertEquals(1, scoreSummary.count());

            // Privacy restriction miss counter incremented
            Counter missCounter = meterRegistry.find(CacheMetrics.METRIC_CACHE_MISSES)
                    .tag(CacheMetrics.TAG_REASON, "privacy_restriction")
                    .counter();
            assertEquals(1.0, missCounter.count());

            Counter requestsTotal = meterRegistry.find(CacheMetrics.METRIC_REQUESTS_TOTAL)
                    .tag(CacheMetrics.TAG_OUTCOME, "miss")
                    .tag(CacheMetrics.TAG_STATUS, "success")
                    .counter();
            assertEquals(1.0, requestsTotal.count());
        }

        @Test
        @DisplayName("Cache miss followed by Gemini failure preserves miss event and records error outcome exactly once")
        void cacheMissFollowedByGeminiFailureRecordsErrorAndPreservesMiss() {
            String prompt = "Query failing in Gemini";
            CacheRequest request = new CacheRequest(prompt);
            Optional<SearchResult> emptyCandidate = Optional.empty();
            CacheLookupResult missDecision = CacheLookupResult.missNoCandidate();

            when(mockCacheRetrievalService.findBestCandidateWithVector(eq(prompt), eq(SearchFilter.publicOnly())))
                    .thenReturn(new CacheRetrievalResult(emptyCandidate, sampleQueryVector));
            when(mockCacheDecisionService.evaluate(eq(emptyCandidate), eq(SearchFilter.publicOnly())))
                    .thenReturn(missDecision);
            when(mockGeminiProvider.generateAnswer(prompt))
                    .thenThrow(new GeminiApiException("Gemini API service down"));

            assertThrows(GeminiApiException.class, () -> cacheService.processPrompt(request));

            // 1. Miss event preserved
            Counter missCounter = meterRegistry.find(CacheMetrics.METRIC_CACHE_MISSES)
                    .tag(CacheMetrics.TAG_REASON, "no_candidate")
                    .counter();
            assertEquals(1.0, missCounter.count(), "Miss event must be counted even if Gemini fails");

            // 2. Request outcome recorded as error
            Counter errorRequests = meterRegistry.find(CacheMetrics.METRIC_REQUESTS_TOTAL)
                    .tag(CacheMetrics.TAG_OUTCOME, "error")
                    .tag(CacheMetrics.TAG_STATUS, "error")
                    .counter();
            assertEquals(1.0, errorRequests.count(), "Overall request outcome must be error");

            // 3. Exactly one error duration recorded
            Timer errorTimer = meterRegistry.find(CacheMetrics.METRIC_REQUEST_DURATION)
                    .tag(CacheMetrics.TAG_OUTCOME, "error")
                    .timer();
            assertEquals(1, errorTimer.count());

            // 4. Successful miss outcome and timer MUST NOT be recorded
            Counter missRequests = meterRegistry.find(CacheMetrics.METRIC_REQUESTS_TOTAL)
                    .tag(CacheMetrics.TAG_OUTCOME, "miss")
                    .counter();
            assertEquals(0.0, missRequests.count(), "No successful miss outcome must be recorded");

            Timer missTimer = meterRegistry.find(CacheMetrics.METRIC_REQUEST_DURATION)
                    .tag(CacheMetrics.TAG_OUTCOME, "miss")
                    .timer();
            assertEquals(0, missTimer.count(), "No miss duration must be recorded");
        }

        @Test
        @DisplayName("Retrieval failure before miss reason is established records only error outcome and no miss event")
        void retrievalFailureRecordsOnlyErrorOutcome() {
            String prompt = "Query failing in retrieval";
            CacheRequest request = new CacheRequest(prompt);

            when(mockCacheRetrievalService.findBestCandidateWithVector(eq(prompt), eq(SearchFilter.publicOnly())))
                    .thenThrow(new EmbeddingException("Embedding generation failed"));

            assertThrows(EmbeddingException.class, () -> cacheService.processPrompt(request));

            // 1. Error request outcome recorded
            Counter errorRequests = meterRegistry.find(CacheMetrics.METRIC_REQUESTS_TOTAL)
                    .tag(CacheMetrics.TAG_OUTCOME, "error")
                    .tag(CacheMetrics.TAG_STATUS, "error")
                    .counter();
            assertEquals(1.0, errorRequests.count());

            // 2. Error duration timer recorded
            Timer errorTimer = meterRegistry.find(CacheMetrics.METRIC_REQUEST_DURATION)
                    .tag(CacheMetrics.TAG_OUTCOME, "error")
                    .timer();
            assertEquals(1, errorTimer.count());

            // 3. ZERO miss counters incremented
            for (CacheMetrics.MissReason reason : CacheMetrics.MissReason.values()) {
                assertEquals(0.0, meterRegistry.find(CacheMetrics.METRIC_CACHE_MISSES)
                        .tag(CacheMetrics.TAG_REASON, reason.tagValue()).counter().count(),
                        "No miss event must be recorded for retrieval failure");
            }
        }

        @Test
        @DisplayName("Decision evaluation failure records only error outcome without duplicate metrics")
        void decisionFailureRecordsOnlyErrorOutcome() {
            String prompt = "Query failing in decision";
            CacheRequest request = new CacheRequest(prompt);
            Optional<SearchResult> candidateOpt = Optional.of(sampleCandidate);

            when(mockCacheRetrievalService.findBestCandidateWithVector(eq(prompt), eq(SearchFilter.publicOnly())))
                    .thenReturn(new CacheRetrievalResult(candidateOpt, sampleQueryVector));
            when(mockCacheDecisionService.evaluate(eq(candidateOpt), eq(SearchFilter.publicOnly())))
                    .thenThrow(new IllegalStateException("Decision evaluator broke"));

            assertThrows(IllegalStateException.class, () -> cacheService.processPrompt(request));

            // Error request recorded
            assertEquals(1.0, meterRegistry.find(CacheMetrics.METRIC_REQUESTS_TOTAL)
                    .tag(CacheMetrics.TAG_OUTCOME, "error")
                    .tag(CacheMetrics.TAG_STATUS, "error").counter().count());

            assertEquals(1, meterRegistry.find(CacheMetrics.METRIC_REQUEST_DURATION)
                    .tag(CacheMetrics.TAG_OUTCOME, "error").timer().count());

            // Zero miss events
            for (CacheMetrics.MissReason reason : CacheMetrics.MissReason.values()) {
                assertEquals(0.0, meterRegistry.find(CacheMetrics.METRIC_CACHE_MISSES)
                        .tag(CacheMetrics.TAG_REASON, reason.tagValue()).counter().count());
            }
        }
    }
}
