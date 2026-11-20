package com.semanticcache.backend.telemetry;

import com.semanticcache.backend.dto.CacheMetricsResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CacheMetricsTest {

    private SimpleMeterRegistry registry;
    private CacheMetrics cacheMetrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        cacheMetrics = new CacheMetrics(registry);
    }

    @Test
    @DisplayName("Constructor throws NullPointerException when MeterRegistry is null")
    void constructorThrowsOnNullRegistry() {
        NullPointerException ex = assertThrows(NullPointerException.class, () -> new CacheMetrics(null));
        assertEquals("meterRegistry must not be null", ex.getMessage());
    }

    @Test
    @DisplayName("Core meters are pre-initialized with zero counts upon instantiation")
    void coreMetersArePreInitialized() {
        Counter hitRequests = registry.find(CacheMetrics.METRIC_REQUESTS_TOTAL)
                .tag(CacheMetrics.TAG_OUTCOME, "hit")
                .tag(CacheMetrics.TAG_STATUS, "success")
                .counter();
        assertNotNull(hitRequests);
        assertEquals(0.0, hitRequests.count());

        Counter missRequests = registry.find(CacheMetrics.METRIC_REQUESTS_TOTAL)
                .tag(CacheMetrics.TAG_OUTCOME, "miss")
                .tag(CacheMetrics.TAG_STATUS, "success")
                .counter();
        assertNotNull(missRequests);
        assertEquals(0.0, missRequests.count());

        Counter hits = registry.find(CacheMetrics.METRIC_CACHE_HITS)
                .tag(CacheMetrics.TAG_VISIBILITY, "PUBLIC")
                .counter();
        assertNotNull(hits);
        assertEquals(0.0, hits.count());

        for (CacheMetrics.MissReason reason : CacheMetrics.MissReason.values()) {
            Counter missCounter = registry.find(CacheMetrics.METRIC_CACHE_MISSES)
                    .tag(CacheMetrics.TAG_REASON, reason.tagValue())
                    .counter();
            assertNotNull(missCounter, "Miss counter for reason " + reason + " must be pre-registered");
            assertEquals(0.0, missCounter.count());
        }

        Timer hitTimer = registry.find(CacheMetrics.METRIC_REQUEST_DURATION)
                .tag(CacheMetrics.TAG_OUTCOME, "hit")
                .timer();
        assertNotNull(hitTimer);
        assertEquals(0, hitTimer.count());
    }

    @Test
    @DisplayName("recordCacheHit increments requests.total, cache.hits.total, and records duration timer")
    void recordCacheHitRecordsExpectedMeters() {
        cacheMetrics.recordCacheHit(Duration.ofMillis(25));

        Counter requests = registry.find(CacheMetrics.METRIC_REQUESTS_TOTAL)
                .tag(CacheMetrics.TAG_OUTCOME, "hit")
                .tag(CacheMetrics.TAG_STATUS, "success")
                .counter();
        assertNotNull(requests);
        assertEquals(1.0, requests.count());

        Counter hits = registry.find(CacheMetrics.METRIC_CACHE_HITS)
                .tag(CacheMetrics.TAG_VISIBILITY, "PUBLIC")
                .counter();
        assertNotNull(hits);
        assertEquals(1.0, hits.count());

        Timer timer = registry.find(CacheMetrics.METRIC_REQUEST_DURATION)
                .tag(CacheMetrics.TAG_OUTCOME, "hit")
                .timer();
        assertNotNull(timer);
        assertEquals(1, timer.count());
        assertEquals(25.0, timer.totalTime(TimeUnit.MILLISECONDS), 0.001);
    }

    @Test
    @DisplayName("recordCacheHit with custom visibility normalizes valid values and sanitizes unknown values")
    void recordCacheHitSanitizesVisibilityTag() {
        cacheMetrics.recordCacheHit("PRIVATE", Duration.ofMillis(10));
        cacheMetrics.recordCacheHit("unknown_custom_scope", Duration.ofMillis(15));
        cacheMetrics.recordCacheHit((String) null, Duration.ofMillis(20));

        Counter privateHits = registry.find(CacheMetrics.METRIC_CACHE_HITS)
                .tag(CacheMetrics.TAG_VISIBILITY, "PRIVATE")
                .counter();
        assertNotNull(privateHits);
        assertEquals(1.0, privateHits.count());

        Counter unknownHits = registry.find(CacheMetrics.METRIC_CACHE_HITS)
                .tag(CacheMetrics.TAG_VISIBILITY, "UNKNOWN")
                .counter();
        assertNotNull(unknownHits);
        assertEquals(2.0, unknownHits.count());
    }

    @Test
    @DisplayName("recordCacheMiss increments requests.total, cache.misses.total with bounded reason, and records timer")
    void recordCacheMissRecordsExpectedMeters() {
        cacheMetrics.recordCacheMiss(CacheMetrics.MissReason.BELOW_THRESHOLD, Duration.ofMillis(30));
        cacheMetrics.recordCacheMiss(CacheMetrics.MissReason.NO_CANDIDATE, Duration.ofMillis(40));
        cacheMetrics.recordCacheMiss(CacheMetrics.MissReason.PRIVACY_RESTRICTION, Duration.ofMillis(50));

        Counter requests = registry.find(CacheMetrics.METRIC_REQUESTS_TOTAL)
                .tag(CacheMetrics.TAG_OUTCOME, "miss")
                .tag(CacheMetrics.TAG_STATUS, "success")
                .counter();
        assertNotNull(requests);
        assertEquals(3.0, requests.count());

        Counter belowThreshold = registry.find(CacheMetrics.METRIC_CACHE_MISSES)
                .tag(CacheMetrics.TAG_REASON, "below_threshold")
                .counter();
        assertNotNull(belowThreshold);
        assertEquals(1.0, belowThreshold.count());

        Counter noCandidate = registry.find(CacheMetrics.METRIC_CACHE_MISSES)
                .tag(CacheMetrics.TAG_REASON, "no_candidate")
                .counter();
        assertNotNull(noCandidate);
        assertEquals(1.0, noCandidate.count());

        Counter privacyRestriction = registry.find(CacheMetrics.METRIC_CACHE_MISSES)
                .tag(CacheMetrics.TAG_REASON, "privacy_restriction")
                .counter();
        assertNotNull(privacyRestriction);
        assertEquals(1.0, privacyRestriction.count());

        Timer timer = registry.find(CacheMetrics.METRIC_REQUEST_DURATION)
                .tag(CacheMetrics.TAG_OUTCOME, "miss")
                .timer();
        assertNotNull(timer);
        assertEquals(3, timer.count());
        assertEquals(120.0, timer.totalTime(TimeUnit.MILLISECONDS), 0.001);
    }

    @Test
    @DisplayName("Independent recordCacheMiss increments only cache.misses.total without affecting requests.total or request.duration")
    void independentRecordCacheMissIncrementsOnlyMissCounter() {
        cacheMetrics.recordCacheMiss(CacheMetrics.MissReason.BELOW_THRESHOLD);

        Counter missCounter = registry.find(CacheMetrics.METRIC_CACHE_MISSES)
                .tag(CacheMetrics.TAG_REASON, "below_threshold")
                .counter();
        assertNotNull(missCounter);
        assertEquals(1.0, missCounter.count());

        Counter requestsTotal = registry.find(CacheMetrics.METRIC_REQUESTS_TOTAL)
                .tag(CacheMetrics.TAG_OUTCOME, "miss")
                .counter();
        assertNotNull(requestsTotal);
        assertEquals(0.0, requestsTotal.count(), "requests.total must not be incremented by independent cache miss");

        Timer timer = registry.find(CacheMetrics.METRIC_REQUEST_DURATION)
                .tag(CacheMetrics.TAG_OUTCOME, "miss")
                .timer();
        assertNotNull(timer);
        assertEquals(0, timer.count(), "request.duration must not be recorded by independent cache miss");
    }

    @Test
    @DisplayName("recordCacheMissCompleted records requests.total and duration without incrementing cache-miss counters")
    void recordCacheMissCompletedRecordsRequestMetersWithoutMissCounter() {
        cacheMetrics.recordCacheMissCompleted(Duration.ofMillis(45));

        Counter requestsTotal = registry.find(CacheMetrics.METRIC_REQUESTS_TOTAL)
                .tag(CacheMetrics.TAG_OUTCOME, "miss")
                .tag(CacheMetrics.TAG_STATUS, "success")
                .counter();
        assertNotNull(requestsTotal);
        assertEquals(1.0, requestsTotal.count());

        Timer timer = registry.find(CacheMetrics.METRIC_REQUEST_DURATION)
                .tag(CacheMetrics.TAG_OUTCOME, "miss")
                .timer();
        assertNotNull(timer);
        assertEquals(1, timer.count());
        assertEquals(45.0, timer.totalTime(TimeUnit.MILLISECONDS), 0.001);

        for (CacheMetrics.MissReason reason : CacheMetrics.MissReason.values()) {
            Counter missCounter = registry.find(CacheMetrics.METRIC_CACHE_MISSES)
                    .tag(CacheMetrics.TAG_REASON, reason.tagValue())
                    .counter();
            assertNotNull(missCounter);
            assertEquals(0.0, missCounter.count(), "Cache-miss counters must not be modified by recordCacheMissCompleted");
        }
    }

    @Test
    @DisplayName("Independent miss followed by completion produces exactly one miss event and one completed request")
    void decoupledMissFollowedByCompletionLifecycle() {
        cacheMetrics.recordCacheMiss(CacheMetrics.MissReason.NO_CANDIDATE);
        cacheMetrics.recordCacheMissCompleted(Duration.ofMillis(60));

        Counter missCounter = registry.find(CacheMetrics.METRIC_CACHE_MISSES)
                .tag(CacheMetrics.TAG_REASON, "no_candidate")
                .counter();
        assertNotNull(missCounter);
        assertEquals(1.0, missCounter.count());

        Counter requestsTotal = registry.find(CacheMetrics.METRIC_REQUESTS_TOTAL)
                .tag(CacheMetrics.TAG_OUTCOME, "miss")
                .tag(CacheMetrics.TAG_STATUS, "success")
                .counter();
        assertNotNull(requestsTotal);
        assertEquals(1.0, requestsTotal.count());

        Timer timer = registry.find(CacheMetrics.METRIC_REQUEST_DURATION)
                .tag(CacheMetrics.TAG_OUTCOME, "miss")
                .timer();
        assertNotNull(timer);
        assertEquals(1, timer.count());
        assertEquals(60.0, timer.totalTime(TimeUnit.MILLISECONDS), 0.001);
    }

    @Test
    @DisplayName("Independent miss followed by error preserves miss event while recording request as error exactly once")
    void decoupledMissFollowedByErrorLifecycle() {
        cacheMetrics.recordCacheMiss(CacheMetrics.MissReason.PRIVACY_RESTRICTION);
        cacheMetrics.recordCacheError(Duration.ofMillis(35));

        // Miss event was preserved
        Counter missCounter = registry.find(CacheMetrics.METRIC_CACHE_MISSES)
                .tag(CacheMetrics.TAG_REASON, "privacy_restriction")
                .counter();
        assertNotNull(missCounter);
        assertEquals(1.0, missCounter.count());

        // Error request recorded
        Counter errorRequests = registry.find(CacheMetrics.METRIC_REQUESTS_TOTAL)
                .tag(CacheMetrics.TAG_OUTCOME, "error")
                .tag(CacheMetrics.TAG_STATUS, "error")
                .counter();
        assertNotNull(errorRequests);
        assertEquals(1.0, errorRequests.count());

        Timer errorTimer = registry.find(CacheMetrics.METRIC_REQUEST_DURATION)
                .tag(CacheMetrics.TAG_OUTCOME, "error")
                .timer();
        assertNotNull(errorTimer);
        assertEquals(1, errorTimer.count());
        assertEquals(35.0, errorTimer.totalTime(TimeUnit.MILLISECONDS), 0.001);

        // No miss request or miss duration recorded
        Counter missRequests = registry.find(CacheMetrics.METRIC_REQUESTS_TOTAL)
                .tag(CacheMetrics.TAG_OUTCOME, "miss")
                .counter();
        assertNotNull(missRequests);
        assertEquals(0.0, missRequests.count());

        Timer missTimer = registry.find(CacheMetrics.METRIC_REQUEST_DURATION)
                .tag(CacheMetrics.TAG_OUTCOME, "miss")
                .timer();
        assertNotNull(missTimer);
        assertEquals(0, missTimer.count());
    }

    @Test
    @DisplayName("recordCacheError increments requests.total and records duration timer with error tags")
    void recordCacheErrorRecordsExpectedMeters() {
        cacheMetrics.recordCacheError(Duration.ofMillis(15));

        Counter requests = registry.find(CacheMetrics.METRIC_REQUESTS_TOTAL)
                .tag(CacheMetrics.TAG_OUTCOME, "error")
                .tag(CacheMetrics.TAG_STATUS, "error")
                .counter();
        assertNotNull(requests);
        assertEquals(1.0, requests.count());

        Timer timer = registry.find(CacheMetrics.METRIC_REQUEST_DURATION)
                .tag(CacheMetrics.TAG_OUTCOME, "error")
                .timer();
        assertNotNull(timer);
        assertEquals(1, timer.count());
        assertEquals(15.0, timer.totalTime(TimeUnit.MILLISECONDS), 0.001);
    }

    @Test
    @DisplayName("recordSimilarityScore records finite values in 0.0 to 1.0 range into DistributionSummary")
    void recordSimilarityScoreRecordsDistribution() {
        cacheMetrics.recordSimilarityScore(0.92, CacheMetrics.CacheOutcome.HIT);
        cacheMetrics.recordSimilarityScore(1.0, CacheMetrics.CacheOutcome.HIT);
        cacheMetrics.recordSimilarityScore(0.78, CacheMetrics.CacheOutcome.MISS);
        cacheMetrics.recordSimilarityScore(0.0, CacheMetrics.CacheOutcome.MISS);

        // Defensive boundary cases that should be ignored
        cacheMetrics.recordSimilarityScore(Double.NaN, CacheMetrics.CacheOutcome.MISS);
        cacheMetrics.recordSimilarityScore(Double.POSITIVE_INFINITY, CacheMetrics.CacheOutcome.MISS);
        cacheMetrics.recordSimilarityScore(-0.1, CacheMetrics.CacheOutcome.MISS);
        cacheMetrics.recordSimilarityScore(1.1, CacheMetrics.CacheOutcome.HIT);

        DistributionSummary hitSummary = registry.find(CacheMetrics.METRIC_SIMILARITY_SCORE)
                .tag(CacheMetrics.TAG_OUTCOME, "hit")
                .summary();
        assertNotNull(hitSummary);
        assertEquals(2, hitSummary.count());
        assertEquals(1.92, hitSummary.totalAmount(), 0.001);
        assertEquals(1.0, hitSummary.max(), 0.001);

        DistributionSummary missSummary = registry.find(CacheMetrics.METRIC_SIMILARITY_SCORE)
                .tag(CacheMetrics.TAG_OUTCOME, "miss")
                .summary();
        assertNotNull(missSummary);
        assertEquals(2, missSummary.count());
        assertEquals(0.78, missSummary.totalAmount(), 0.001);
        assertEquals(0.78, missSummary.max(), 0.001);
    }

    @Test
    @DisplayName("startTimer and stopRequestTimer sample elapsed execution duration")
    void timerSampleRecordsDuration() throws InterruptedException {
        Timer.Sample sample = cacheMetrics.startTimer();
        assertNotNull(sample);

        Thread.sleep(10);
        cacheMetrics.stopRequestTimer(sample, CacheMetrics.CacheOutcome.HIT);

        Timer timer = registry.find(CacheMetrics.METRIC_REQUEST_DURATION)
                .tag(CacheMetrics.TAG_OUTCOME, "hit")
                .timer();
        assertNotNull(timer);
        assertEquals(1, timer.count());
        assertTrue(timer.totalTime(TimeUnit.MILLISECONDS) >= 5.0);
    }

    @Test
    @DisplayName("recordGeminiGeneration records call counter and duration timer")
    void recordGeminiGenerationRecordsExpectedMeters() {
        cacheMetrics.recordGeminiGeneration("gemini-3.8-flash", CacheMetrics.OperationStatus.SUCCESS, Duration.ofMillis(350));
        cacheMetrics.recordGeminiGeneration("gemini-3.8-flash", CacheMetrics.OperationStatus.ERROR, Duration.ofMillis(100));

        Counter successCalls = registry.find(CacheMetrics.METRIC_GEMINI_GENERATION_CALLS)
                .tag(CacheMetrics.TAG_MODEL, "gemini-3.8-flash")
                .tag(CacheMetrics.TAG_STATUS, "success")
                .counter();
        assertNotNull(successCalls);
        assertEquals(1.0, successCalls.count());

        Counter errorCalls = registry.find(CacheMetrics.METRIC_GEMINI_GENERATION_CALLS)
                .tag(CacheMetrics.TAG_MODEL, "gemini-3.8-flash")
                .tag(CacheMetrics.TAG_STATUS, "error")
                .counter();
        assertNotNull(errorCalls);
        assertEquals(1.0, errorCalls.count());

        Timer successTimer = registry.find(CacheMetrics.METRIC_GEMINI_GENERATION_DURATION)
                .tag(CacheMetrics.TAG_MODEL, "gemini-3.8-flash")
                .tag(CacheMetrics.TAG_STATUS, "success")
                .timer();
        assertNotNull(successTimer);
        assertEquals(1, successTimer.count());
        assertEquals(350.0, successTimer.totalTime(TimeUnit.MILLISECONDS), 0.001);
    }

    @Test
    @DisplayName("recordGeminiEmbedding records call counter and duration timer")
    void recordGeminiEmbeddingRecordsExpectedMeters() {
        cacheMetrics.recordGeminiEmbedding("gemini-embedding-001", CacheMetrics.OperationStatus.SUCCESS, Duration.ofMillis(80));

        Counter calls = registry.find(CacheMetrics.METRIC_GEMINI_EMBEDDING_CALLS)
                .tag(CacheMetrics.TAG_MODEL, "gemini-embedding-001")
                .tag(CacheMetrics.TAG_STATUS, "success")
                .counter();
        assertNotNull(calls);
        assertEquals(1.0, calls.count());

        Timer timer = registry.find(CacheMetrics.METRIC_GEMINI_EMBEDDING_DURATION)
                .tag(CacheMetrics.TAG_MODEL, "gemini-embedding-001")
                .tag(CacheMetrics.TAG_STATUS, "success")
                .timer();
        assertNotNull(timer);
        assertEquals(1, timer.count());
        assertEquals(80.0, timer.totalTime(TimeUnit.MILLISECONDS), 0.001);
    }

    @Test
    @DisplayName("Model tag normalization enforces allowlist and collapses unknown, blank, and arbitrary values to other")
    void modelTagNormalizationEnforcesAllowlistAndCollapsesToOther() {
        // Case and surrounding whitespace normalization for recognized models
        cacheMetrics.recordGeminiGeneration("  GEMINI-3.8-FLASH  ", CacheMetrics.OperationStatus.SUCCESS, Duration.ofMillis(100));
        cacheMetrics.recordGeminiEmbedding("  Gemini-Embedding-001  ", CacheMetrics.OperationStatus.SUCCESS, Duration.ofMillis(50));

        Counter flashCalls = registry.find(CacheMetrics.METRIC_GEMINI_GENERATION_CALLS)
                .tag(CacheMetrics.TAG_MODEL, "gemini-3.8-flash")
                .counter();
        assertNotNull(flashCalls);
        assertEquals(1.0, flashCalls.count());

        Counter embeddingCalls = registry.find(CacheMetrics.METRIC_GEMINI_EMBEDDING_CALLS)
                .tag(CacheMetrics.TAG_MODEL, "gemini-embedding-001")
                .counter();
        assertNotNull(embeddingCalls);
        assertEquals(1.0, embeddingCalls.count());

        // Null, blank, and unrecognized arbitrary models all collapse to "other"
        cacheMetrics.recordGeminiGeneration(null, CacheMetrics.OperationStatus.SUCCESS, Duration.ofMillis(10));
        cacheMetrics.recordGeminiGeneration("   ", CacheMetrics.OperationStatus.SUCCESS, Duration.ofMillis(10));
        cacheMetrics.recordGeminiGeneration("gpt-4o", CacheMetrics.OperationStatus.SUCCESS, Duration.ofMillis(10));
        cacheMetrics.recordGeminiGeneration("claude-3-opus", CacheMetrics.OperationStatus.SUCCESS, Duration.ofMillis(10));
        cacheMetrics.recordGeminiGeneration("arbitrary-dynamic-model-identifier-12345", CacheMetrics.OperationStatus.SUCCESS, Duration.ofMillis(10));

        Counter otherCalls = registry.find(CacheMetrics.METRIC_GEMINI_GENERATION_CALLS)
                .tag(CacheMetrics.TAG_MODEL, "other")
                .tag(CacheMetrics.TAG_STATUS, "success")
                .counter();
        assertNotNull(otherCalls);
        assertEquals(5.0, otherCalls.count(), "All 5 arbitrary/blank/null models must collapse to the single 'other' tag");

        // Verify no arbitrary model tags were created in the registry
        for (Meter meter : registry.getMeters()) {
            if (meter.getId().getName().equals(CacheMetrics.METRIC_GEMINI_GENERATION_CALLS)) {
                String modelTag = meter.getId().getTag(CacheMetrics.TAG_MODEL);
                assertTrue(Set.of("gemini-3.8-flash", "other").contains(modelTag),
                        "Unexpected model tag found: " + modelTag);
            }
        }
    }

    @Test
    @DisplayName("recordGeminiEmbedding enforces model allowlist and collapses unknown inputs to other")
    void recordGeminiEmbeddingEnforcesAllowlistAndCollapsesToOther() {
        // Recognized model records under canonical tag
        cacheMetrics.recordGeminiEmbedding("gemini-embedding-001", CacheMetrics.OperationStatus.SUCCESS, Duration.ofMillis(75));

        Counter canonicalCalls = registry.find(CacheMetrics.METRIC_GEMINI_EMBEDDING_CALLS)
                .tag(CacheMetrics.TAG_MODEL, "gemini-embedding-001")
                .tag(CacheMetrics.TAG_STATUS, "success")
                .counter();
        assertNotNull(canonicalCalls);
        assertEquals(1.0, canonicalCalls.count());

        Timer canonicalTimer = registry.find(CacheMetrics.METRIC_GEMINI_EMBEDDING_DURATION)
                .tag(CacheMetrics.TAG_MODEL, "gemini-embedding-001")
                .tag(CacheMetrics.TAG_STATUS, "success")
                .timer();
        assertNotNull(canonicalTimer);
        assertEquals(1, canonicalTimer.count());
        assertEquals(75.0, canonicalTimer.totalTime(TimeUnit.MILLISECONDS), 0.001);

        // Null, blank, and arbitrary models map to 'other'
        cacheMetrics.recordGeminiEmbedding(null, CacheMetrics.OperationStatus.SUCCESS, Duration.ofMillis(20));
        cacheMetrics.recordGeminiEmbedding("   ", CacheMetrics.OperationStatus.SUCCESS, Duration.ofMillis(20));
        cacheMetrics.recordGeminiEmbedding("gpt-4o", CacheMetrics.OperationStatus.SUCCESS, Duration.ofMillis(20));
        cacheMetrics.recordGeminiEmbedding("claude-3-opus", CacheMetrics.OperationStatus.SUCCESS, Duration.ofMillis(20));

        Counter otherCalls = registry.find(CacheMetrics.METRIC_GEMINI_EMBEDDING_CALLS)
                .tag(CacheMetrics.TAG_MODEL, "other")
                .tag(CacheMetrics.TAG_STATUS, "success")
                .counter();
        assertNotNull(otherCalls);
        assertEquals(4.0, otherCalls.count(), "All 4 null/blank/arbitrary embedding models must collapse to 'other'");

        Timer otherTimer = registry.find(CacheMetrics.METRIC_GEMINI_EMBEDDING_DURATION)
                .tag(CacheMetrics.TAG_MODEL, "other")
                .tag(CacheMetrics.TAG_STATUS, "success")
                .timer();
        assertNotNull(otherTimer);
        assertEquals(4, otherTimer.count());
        assertEquals(80.0, otherTimer.totalTime(TimeUnit.MILLISECONDS), 0.001);

        // Verify no arbitrary model tags were created in the registry for embedding metrics
        for (Meter meter : registry.getMeters()) {
            if (meter.getId().getName().startsWith("semanticcache.gemini.embedding")) {
                String modelTag = meter.getId().getTag(CacheMetrics.TAG_MODEL);
                assertTrue(Set.of("gemini-embedding-001", "other").contains(modelTag),
                        "Unexpected embedding model tag found: " + modelTag);
            }
        }
    }

    @Test
    @DisplayName("recordQdrantOperation records operation counter and duration timer with bounded tags")
    void recordQdrantOperationRecordsExpectedMeters() {
        cacheMetrics.recordQdrantOperation(CacheMetrics.QdrantOperation.SEARCH, CacheMetrics.OperationStatus.SUCCESS, Duration.ofMillis(12));
        cacheMetrics.recordQdrantOperation(CacheMetrics.QdrantOperation.SEARCH, CacheMetrics.OperationStatus.TIMEOUT, Duration.ofSeconds(10));
        cacheMetrics.recordQdrantOperation(CacheMetrics.QdrantOperation.UPSERT, CacheMetrics.OperationStatus.SUCCESS, Duration.ofMillis(20));

        Counter searchSuccess = registry.find(CacheMetrics.METRIC_QDRANT_OPERATIONS)
                .tag(CacheMetrics.TAG_OPERATION, "search")
                .tag(CacheMetrics.TAG_STATUS, "success")
                .counter();
        assertNotNull(searchSuccess);
        assertEquals(1.0, searchSuccess.count());

        Counter searchTimeout = registry.find(CacheMetrics.METRIC_QDRANT_OPERATIONS)
                .tag(CacheMetrics.TAG_OPERATION, "search")
                .tag(CacheMetrics.TAG_STATUS, "timeout")
                .counter();
        assertNotNull(searchTimeout);
        assertEquals(1.0, searchTimeout.count());

        Timer upsertTimer = registry.find(CacheMetrics.METRIC_QDRANT_OPERATION_DURATION)
                .tag(CacheMetrics.TAG_OPERATION, "upsert")
                .tag(CacheMetrics.TAG_STATUS, "success")
                .timer();
        assertNotNull(upsertTimer);
        assertEquals(1, upsertTimer.count());
        assertEquals(20.0, upsertTimer.totalTime(TimeUnit.MILLISECONDS), 0.001);
    }

    @Test
    @DisplayName("Null arguments across recording methods throw NullPointerException")
    void nullArgumentsThrowNullPointerException() {
        assertThrows(NullPointerException.class, () -> cacheMetrics.recordCacheHit(null));
        assertThrows(NullPointerException.class, () -> cacheMetrics.recordCacheHit("PUBLIC", null));
        assertThrows(NullPointerException.class, () -> cacheMetrics.recordCacheMiss(null, Duration.ofMillis(10)));
        assertThrows(NullPointerException.class, () -> cacheMetrics.recordCacheMiss(CacheMetrics.MissReason.NO_CANDIDATE, null));
        assertThrows(NullPointerException.class, () -> cacheMetrics.recordCacheMiss((CacheMetrics.MissReason) null));
        assertThrows(NullPointerException.class, () -> cacheMetrics.recordCacheMissCompleted(null));
        assertThrows(NullPointerException.class, () -> cacheMetrics.recordCacheError(null));
        assertThrows(NullPointerException.class, () -> cacheMetrics.recordSimilarityScore(0.5, null));
        assertThrows(NullPointerException.class, () -> cacheMetrics.stopRequestTimer(null, CacheMetrics.CacheOutcome.HIT));
        assertThrows(NullPointerException.class, () -> cacheMetrics.stopRequestTimer(cacheMetrics.startTimer(), null));
        assertThrows(NullPointerException.class, () -> cacheMetrics.recordGeminiGeneration("model", null, Duration.ofMillis(10)));
        assertThrows(NullPointerException.class, () -> cacheMetrics.recordGeminiGeneration("model", CacheMetrics.OperationStatus.SUCCESS, null));
        assertThrows(NullPointerException.class, () -> cacheMetrics.recordGeminiEmbedding("model", null, Duration.ofMillis(10)));
        assertThrows(NullPointerException.class, () -> cacheMetrics.recordGeminiEmbedding("model", CacheMetrics.OperationStatus.SUCCESS, null));
        assertThrows(NullPointerException.class, () -> cacheMetrics.recordQdrantOperation(null, CacheMetrics.OperationStatus.SUCCESS, Duration.ofMillis(10)));
        assertThrows(NullPointerException.class, () -> cacheMetrics.recordQdrantOperation(CacheMetrics.QdrantOperation.SEARCH, null, Duration.ofMillis(10)));
        assertThrows(NullPointerException.class, () -> cacheMetrics.recordQdrantOperation(CacheMetrics.QdrantOperation.SEARCH, CacheMetrics.OperationStatus.SUCCESS, null));
    }

    @Test
    @DisplayName("Privacy verification: zero sensitive metric names, tag keys, or tag values exist")
    void verifyPrivacyNoSensitiveNamesOrTags() {
        // Exercise all metrics to populate registry
        cacheMetrics.recordCacheHit(Duration.ofMillis(10));
        cacheMetrics.recordCacheMiss(CacheMetrics.MissReason.NO_CANDIDATE, Duration.ofMillis(10));
        cacheMetrics.recordCacheError(Duration.ofMillis(10));
        cacheMetrics.recordSimilarityScore(0.9, CacheMetrics.CacheOutcome.HIT);
        cacheMetrics.recordGeminiGeneration("gemini-3.8-flash", CacheMetrics.OperationStatus.SUCCESS, Duration.ofMillis(100));
        cacheMetrics.recordGeminiEmbedding("gemini-embedding-001", CacheMetrics.OperationStatus.SUCCESS, Duration.ofMillis(50));
        cacheMetrics.recordQdrantOperation(CacheMetrics.QdrantOperation.SEARCH, CacheMetrics.OperationStatus.SUCCESS, Duration.ofMillis(10));

        Set<String> forbiddenTerms = Set.of(
                "prompt", "query", "answer", "text", "content", "user", "owner",
                "key", "api_key", "secret", "token", "password", "credential",
                "exception", "error_message", "stack_trace"
        );

        for (Meter meter : registry.getMeters()) {
            String meterName = meter.getId().getName().toLowerCase();
            for (String forbidden : forbiddenTerms) {
                assertFalse(meterName.contains(forbidden),
                        "Meter name [" + meterName + "] contains sensitive term: " + forbidden);
            }

            for (Tag tag : meter.getId().getTags()) {
                String tagKey = tag.getKey().toLowerCase();
                String tagValue = tag.getValue().toLowerCase();

                for (String forbidden : forbiddenTerms) {
                    assertFalse(tagKey.contains(forbidden),
                            "Tag key [" + tagKey + "] in meter [" + meterName + "] contains sensitive term: " + forbidden);
                    assertFalse(tagValue.contains(forbidden),
                            "Tag value [" + tagValue + "] in meter [" + meterName + "] contains sensitive term: " + forbidden);
                }
            }
        }
    }

    @Test
    @DisplayName("getSnapshot returns zeroed snapshot on fresh registry")
    void getSnapshotReturnsZeroedSnapshotOnFreshRegistry() {
        CacheMetricsResponse snapshot = cacheMetrics.getSnapshot();

        assertEquals(0L, snapshot.totalRequests());
        assertEquals(0L, snapshot.cacheHits());
        assertEquals(0L, snapshot.cacheMisses());
        assertEquals(0L, snapshot.requestErrors());
        assertEquals(0.0, snapshot.hitRate());
        assertEquals(0L, snapshot.geminiGenerationCalls());
        assertEquals(0L, snapshot.geminiEmbeddingCalls());
        assertEquals(0L, snapshot.qdrantOperations());
    }

    @Test
    @DisplayName("getSnapshot aggregates hits, misses, errors, and accurately computes hitRate")
    void getSnapshotAggregatesHitsMissesErrorsAndHitRate() {
        cacheMetrics.recordCacheHit(Duration.ofMillis(10));
        cacheMetrics.recordCacheHit(Duration.ofMillis(20));
        cacheMetrics.recordCacheHit("PRIVATE", Duration.ofMillis(15));
        cacheMetrics.recordCacheMiss(CacheMetrics.MissReason.NO_CANDIDATE, Duration.ofMillis(50));
        cacheMetrics.recordCacheError(Duration.ofMillis(5));

        CacheMetricsResponse snapshot = cacheMetrics.getSnapshot();

        assertEquals(5L, snapshot.totalRequests());
        assertEquals(3L, snapshot.cacheHits());
        assertEquals(1L, snapshot.cacheMisses());
        assertEquals(1L, snapshot.requestErrors());
        // evaluatedRequests = 3 + 1 = 4. hitRate = 3 / 4 = 0.75
        assertEquals(0.75, snapshot.hitRate(), 0.0001);
    }

    @Test
    @DisplayName("getSnapshot returns 0.0 hitRate when only errors occur")
    void getSnapshotReturnsZeroHitRateWhenOnlyErrorsOccur() {
        cacheMetrics.recordCacheError(Duration.ofMillis(10));
        cacheMetrics.recordCacheError(Duration.ofMillis(12));

        CacheMetricsResponse snapshot = cacheMetrics.getSnapshot();

        assertEquals(2L, snapshot.totalRequests());
        assertEquals(0L, snapshot.cacheHits());
        assertEquals(0L, snapshot.cacheMisses());
        assertEquals(2L, snapshot.requestErrors());
        assertEquals(0.0, snapshot.hitRate());
    }

    @Test
    @DisplayName("getSnapshot aggregates downstream Gemini and Qdrant counters correctly")
    void getSnapshotAggregatesDownstreamCounters() {
        cacheMetrics.recordGeminiGeneration("gemini-3.8-flash", CacheMetrics.OperationStatus.SUCCESS, Duration.ofMillis(200));
        cacheMetrics.recordGeminiGeneration("gemini-3.8-flash", CacheMetrics.OperationStatus.ERROR, Duration.ofMillis(100));

        cacheMetrics.recordGeminiEmbedding("gemini-embedding-001", CacheMetrics.OperationStatus.SUCCESS, Duration.ofMillis(50));
        cacheMetrics.recordGeminiEmbedding("gemini-embedding-001", CacheMetrics.OperationStatus.SUCCESS, Duration.ofMillis(40));
        cacheMetrics.recordGeminiEmbedding("gemini-embedding-001", CacheMetrics.OperationStatus.TIMEOUT, Duration.ofMillis(500));

        cacheMetrics.recordQdrantOperation(CacheMetrics.QdrantOperation.SEARCH, CacheMetrics.OperationStatus.SUCCESS, Duration.ofMillis(15));
        cacheMetrics.recordQdrantOperation(CacheMetrics.QdrantOperation.UPSERT, CacheMetrics.OperationStatus.SUCCESS, Duration.ofMillis(25));
        cacheMetrics.recordQdrantOperation(CacheMetrics.QdrantOperation.RETRIEVE, CacheMetrics.OperationStatus.ERROR, Duration.ofMillis(30));
        cacheMetrics.recordQdrantOperation(CacheMetrics.QdrantOperation.SEARCH, CacheMetrics.OperationStatus.TIMEOUT, Duration.ofMillis(100));

        CacheMetricsResponse snapshot = cacheMetrics.getSnapshot();

        assertEquals(2L, snapshot.geminiGenerationCalls());
        assertEquals(3L, snapshot.geminiEmbeddingCalls());
        assertEquals(4L, snapshot.qdrantOperations());
    }
}
