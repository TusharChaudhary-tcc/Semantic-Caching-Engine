package com.semanticcache.backend.service;

import com.semanticcache.backend.config.CacheConfig;
import com.semanticcache.backend.dto.CacheLookupResult;
import com.semanticcache.backend.dto.SearchFilter;
import com.semanticcache.backend.dto.SearchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CacheDecisionServiceTest {

    private static final float CONFIGURED_THRESHOLD = 0.85f;

    @Mock
    private CacheConfig mockCacheConfig;

    private DefaultCacheDecisionService decisionService;

    @BeforeEach
    void setUp() {
        decisionService = new DefaultCacheDecisionService(mockCacheConfig);
    }

    private SearchResult createCandidate(float score, String ownerId, String visibility) {
        return new SearchResult(
                UUID.randomUUID(),
                "What is semantic caching?",
                "Semantic caching accelerates AI responses.",
                "gemini-3.8-flash",
                Instant.parse("2026-11-08T12:00:00Z"),
                "gemini-embedding-001",
                score,
                ownerId,
                visibility
        );
    }

    @Nested
    @DisplayName("Null Argument and Defensive Validation")
    class InputValidationTests {

        @Test
        @DisplayName("Rejects null candidate Optional with NullPointerException")
        void rejectsNullCandidate() {
            NullPointerException ex = assertThrows(
                    NullPointerException.class,
                    () -> decisionService.evaluate(null, SearchFilter.publicOnly())
            );
            assertEquals("candidate must not be null", ex.getMessage());
        }

        @Test
        @DisplayName("Rejects null SearchFilter with NullPointerException")
        void rejectsNullFilter() {
            NullPointerException ex = assertThrows(
                    NullPointerException.class,
                    () -> decisionService.evaluate(Optional.empty(), null)
            );
            assertEquals("filter must not be null", ex.getMessage());
        }

        @Test
        @DisplayName("Returns missNoCandidate when candidate Optional is empty")
        void returnsMissWhenOptionalEmpty() {
            CacheLookupResult result = decisionService.evaluate(Optional.empty(), SearchFilter.publicOnly());

            assertNotNull(result);
            assertFalse(result.isHit());
            assertTrue(result.matchedResult().isEmpty());
            assertEquals(0.0f, result.score(), 1e-6);
            assertEquals("NO_CANDIDATE_FOUND", result.reason());
        }
    }

    @Nested
    @DisplayName("Anonymous Caller Privacy and Decision Tests")
    class AnonymousFilterTests {

        @Test
        @DisplayName("Public candidate with score above threshold is a cache HIT")
        void publicCandidateAboveThresholdIsHit() {
            when(mockCacheConfig.getSimilarityThreshold()).thenReturn(CONFIGURED_THRESHOLD);
            SearchResult candidate = createCandidate(0.92f, null, "PUBLIC");

            CacheLookupResult result = decisionService.evaluate(Optional.of(candidate), SearchFilter.publicOnly());

            assertTrue(result.isHit());
            assertTrue(result.matchedResult().isPresent());
            assertEquals(candidate, result.matchedResult().get());
            assertEquals(0.92f, result.score(), 1e-6);
            assertEquals("MATCH_EXCEEDS_SIMILARITY_THRESHOLD", result.reason());
        }

        @Test
        @DisplayName("Public candidate with score exactly equal to threshold is a cache HIT")
        void publicCandidateEqualToThresholdIsHit() {
            when(mockCacheConfig.getSimilarityThreshold()).thenReturn(CONFIGURED_THRESHOLD);
            SearchResult candidate = createCandidate(0.85f, null, "PUBLIC");

            CacheLookupResult result = decisionService.evaluate(Optional.of(candidate), SearchFilter.publicOnly());

            assertTrue(result.isHit());
            assertEquals(0.85f, result.score(), 1e-6);
            assertEquals("MATCH_EXCEEDS_SIMILARITY_THRESHOLD", result.reason());
        }

        @Test
        @DisplayName("Public candidate with score below threshold is a cache MISS")
        void publicCandidateBelowThresholdIsMiss() {
            when(mockCacheConfig.getSimilarityThreshold()).thenReturn(CONFIGURED_THRESHOLD);
            SearchResult candidate = createCandidate(0.81f, null, "PUBLIC");

            CacheLookupResult result = decisionService.evaluate(Optional.of(candidate), SearchFilter.publicOnly());

            assertFalse(result.isHit());
            assertTrue(result.matchedResult().isPresent());
            assertEquals(0.81f, result.score(), 1e-6);
            assertEquals("SCORE_BELOW_SIMILARITY_THRESHOLD", result.reason());
        }

        @Test
        @DisplayName("Private candidate evaluated with anonymous filter is a privacy MISS, even with high score")
        void privateCandidateWithAnonymousFilterIsPrivacyMiss() {
            // High score (0.99) must be rejected prior to threshold check
            SearchResult candidate = createCandidate(0.99f, "user-secret", "PRIVATE");

            CacheLookupResult result = decisionService.evaluate(Optional.of(candidate), SearchFilter.publicOnly());

            assertFalse(result.isHit());
            assertTrue(result.matchedResult().isEmpty());
            assertEquals(0.99f, result.score(), 1e-6);
            assertEquals("CANDIDATE_NOT_PUBLIC", result.reason());
        }

        @Test
        @DisplayName("Legacy candidate (ownerId=null, visibility=PRIVATE) with anonymous filter is a privacy MISS")
        void legacyPrivateCandidateWithAnonymousFilterIsPrivacyMiss() {
            SearchResult candidate = createCandidate(0.95f, null, "PRIVATE");

            CacheLookupResult result = decisionService.evaluate(Optional.of(candidate), SearchFilter.publicOnly());

            assertFalse(result.isHit());
            assertTrue(result.matchedResult().isEmpty());
            assertEquals("CANDIDATE_NOT_PUBLIC", result.reason());
        }
    }

    @Nested
    @DisplayName("Authenticated Caller Ownership and Decision Tests")
    class AuthenticatedFilterTests {

        @Test
        @DisplayName("Own private candidate with score above threshold is a cache HIT")
        void ownPrivateCandidateAboveThresholdIsHit() {
            when(mockCacheConfig.getSimilarityThreshold()).thenReturn(CONFIGURED_THRESHOLD);
            SearchResult candidate = createCandidate(0.89f, "user-alpha", "PRIVATE");
            SearchFilter authFilter = SearchFilter.forOwner("user-alpha");

            CacheLookupResult result = decisionService.evaluate(Optional.of(candidate), authFilter);

            assertTrue(result.isHit());
            assertTrue(result.matchedResult().isPresent());
            assertEquals("MATCH_EXCEEDS_SIMILARITY_THRESHOLD", result.reason());
        }

        @Test
        @DisplayName("Own private candidate with score below threshold is a cache MISS")
        void ownPrivateCandidateBelowThresholdIsMiss() {
            when(mockCacheConfig.getSimilarityThreshold()).thenReturn(CONFIGURED_THRESHOLD);
            SearchResult candidate = createCandidate(0.79f, "user-alpha", "PRIVATE");
            SearchFilter authFilter = SearchFilter.forOwner("user-alpha");

            CacheLookupResult result = decisionService.evaluate(Optional.of(candidate), authFilter);

            assertFalse(result.isHit());
            assertEquals("SCORE_BELOW_SIMILARITY_THRESHOLD", result.reason());
        }

        @Test
        @DisplayName("Different owner's private candidate with authenticated filter is an ownership MISS, even with high score")
        void differentOwnerPrivateCandidateIsOwnershipMiss() {
            SearchResult candidate = createCandidate(0.98f, "user-beta", "PRIVATE");
            SearchFilter authFilter = SearchFilter.forOwner("user-alpha");

            CacheLookupResult result = decisionService.evaluate(Optional.of(candidate), authFilter);

            assertFalse(result.isHit());
            assertTrue(result.matchedResult().isEmpty());
            assertEquals(0.98f, result.score(), 1e-6);
            assertEquals("OWNERSHIP_MISMATCH", result.reason());
        }

        @Test
        @DisplayName("Public candidate evaluated with authenticated filter is a cache HIT when above threshold")
        void publicCandidateWithAuthenticatedFilterIsHit() {
            when(mockCacheConfig.getSimilarityThreshold()).thenReturn(CONFIGURED_THRESHOLD);
            SearchResult candidate = createCandidate(0.88f, "another-user", "PUBLIC");
            SearchFilter authFilter = SearchFilter.forOwner("user-alpha");

            CacheLookupResult result = decisionService.evaluate(Optional.of(candidate), authFilter);

            assertTrue(result.isHit());
            assertEquals("MATCH_EXCEEDS_SIMILARITY_THRESHOLD", result.reason());
        }

        @Test
        @DisplayName("Legacy candidate (ownerId=null, visibility=PRIVATE) with authenticated filter is an ownership MISS")
        void legacyCandidateWithAuthenticatedFilterIsOwnershipMiss() {
            SearchResult candidate = createCandidate(0.93f, null, "PRIVATE");
            SearchFilter authFilter = SearchFilter.forOwner("user-alpha");

            CacheLookupResult result = decisionService.evaluate(Optional.of(candidate), authFilter);

            assertFalse(result.isHit());
            assertEquals("OWNERSHIP_MISMATCH", result.reason());
        }
    }
}
