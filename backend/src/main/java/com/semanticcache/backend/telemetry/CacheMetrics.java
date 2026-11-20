package com.semanticcache.backend.telemetry;

import com.semanticcache.backend.dto.CacheMetricsResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collection;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Centralized telemetry and metrics abstraction for SemanticCache-LLM.
 * <p>
 * Enforces strictly bounded, low-cardinality tag keys and values to prevent memory leaks
 * and metrics explosions. Never records prompts, answers, credentials, user IDs, or exception messages.
 */
@Component
public class CacheMetrics {

    // Metric names
    public static final String METRIC_REQUESTS_TOTAL = "semanticcache.requests.total";
    public static final String METRIC_CACHE_HITS = "semanticcache.cache.hits.total";
    public static final String METRIC_CACHE_MISSES = "semanticcache.cache.misses.total";
    public static final String METRIC_REQUEST_DURATION = "semanticcache.request.duration";
    public static final String METRIC_SIMILARITY_SCORE = "semanticcache.similarity.score";

    public static final String METRIC_GEMINI_GENERATION_CALLS = "semanticcache.gemini.generation.calls.total";
    public static final String METRIC_GEMINI_GENERATION_DURATION = "semanticcache.gemini.generation.duration";
    public static final String METRIC_GEMINI_EMBEDDING_CALLS = "semanticcache.gemini.embedding.calls.total";
    public static final String METRIC_GEMINI_EMBEDDING_DURATION = "semanticcache.gemini.embedding.duration";

    public static final String METRIC_QDRANT_OPERATIONS = "semanticcache.qdrant.operations.total";
    public static final String METRIC_QDRANT_OPERATION_DURATION = "semanticcache.qdrant.operation.duration";

    // Tag keys
    public static final String TAG_OUTCOME = "outcome";
    public static final String TAG_STATUS = "status";
    public static final String TAG_VISIBILITY = "visibility";
    public static final String TAG_REASON = "reason";
    public static final String TAG_MODEL = "model";
    public static final String TAG_OPERATION = "operation";

    // Default visibility
    public static final String VISIBILITY_PUBLIC = "PUBLIC";
    public static final String VISIBILITY_UNKNOWN = "UNKNOWN";

    // Recognized models and bounded fallback
    public static final String MODEL_GEMINI_FLASH = "gemini-3.8-flash";
    public static final String MODEL_GEMINI_EMBEDDING = "gemini-embedding-001";
    public static final String MODEL_OTHER = "other";

    private static final Set<String> ALLOWED_MODELS = Set.of(
            MODEL_GEMINI_FLASH,
            MODEL_GEMINI_EMBEDDING
    );

    private final MeterRegistry meterRegistry;

    /**
     * Bounded tag values for request outcomes.
     */
    public enum CacheOutcome {
        HIT("hit"),
        MISS("miss"),
        ERROR("error");

        private final String tagValue;

        CacheOutcome(String tagValue) {
            this.tagValue = tagValue;
        }

        public String tagValue() {
            return tagValue;
        }
    }

    /**
     * Bounded tag values for cache miss causes.
     */
    public enum MissReason {
        NO_CANDIDATE("no_candidate"),
        BELOW_THRESHOLD("below_threshold"),
        PRIVACY_RESTRICTION("privacy_restriction");

        private final String tagValue;

        MissReason(String tagValue) {
            this.tagValue = tagValue;
        }

        public String tagValue() {
            return tagValue;
        }
    }

    /**
     * Bounded tag values for downstream operation statuses.
     */
    public enum OperationStatus {
        SUCCESS("success"),
        ERROR("error"),
        TIMEOUT("timeout");

        private final String tagValue;

        OperationStatus(String tagValue) {
            this.tagValue = tagValue;
        }

        public String tagValue() {
            return tagValue;
        }
    }

    /**
     * Bounded tag values for Qdrant operations.
     */
    public enum QdrantOperation {
        SEARCH("search"),
        RETRIEVE("retrieve"),
        UPSERT("upsert");

        private final String tagValue;

        QdrantOperation(String tagValue) {
            this.tagValue = tagValue;
        }

        public String tagValue() {
            return tagValue;
        }
    }

    public CacheMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
        initializeMeters();
    }

    private void initializeMeters() {
        // Pre-initialize counters and summaries to guarantee meter availability on startup
        meterRegistry.counter(METRIC_REQUESTS_TOTAL,
                Tags.of(TAG_OUTCOME, CacheOutcome.HIT.tagValue(), TAG_STATUS, OperationStatus.SUCCESS.tagValue()));
        meterRegistry.counter(METRIC_REQUESTS_TOTAL,
                Tags.of(TAG_OUTCOME, CacheOutcome.MISS.tagValue(), TAG_STATUS, OperationStatus.SUCCESS.tagValue()));
        meterRegistry.counter(METRIC_REQUESTS_TOTAL,
                Tags.of(TAG_OUTCOME, CacheOutcome.ERROR.tagValue(), TAG_STATUS, OperationStatus.ERROR.tagValue()));

        meterRegistry.counter(METRIC_CACHE_HITS, Tags.of(TAG_VISIBILITY, VISIBILITY_PUBLIC));

        for (MissReason reason : MissReason.values()) {
            meterRegistry.counter(METRIC_CACHE_MISSES, Tags.of(TAG_REASON, reason.tagValue()));
        }

        meterRegistry.timer(METRIC_REQUEST_DURATION, Tags.of(TAG_OUTCOME, CacheOutcome.HIT.tagValue()));
        meterRegistry.timer(METRIC_REQUEST_DURATION, Tags.of(TAG_OUTCOME, CacheOutcome.MISS.tagValue()));
        meterRegistry.timer(METRIC_REQUEST_DURATION, Tags.of(TAG_OUTCOME, CacheOutcome.ERROR.tagValue()));

        DistributionSummary.builder(METRIC_SIMILARITY_SCORE)
                .description("Distribution of semantic cache similarity scores")
                .maximumExpectedValue(1.0)
                .tags(Tags.of(TAG_OUTCOME, CacheOutcome.HIT.tagValue()))
                .register(meterRegistry);

        DistributionSummary.builder(METRIC_SIMILARITY_SCORE)
                .description("Distribution of semantic cache similarity scores")
                .maximumExpectedValue(1.0)
                .tags(Tags.of(TAG_OUTCOME, CacheOutcome.MISS.tagValue()))
                .register(meterRegistry);
    }

    // ==========================================
    // Core Semantic Cache Metrics
    // ==========================================

    /**
     * Records a semantic cache hit with default public visibility.
     *
     * @param duration total elapsed request duration
     */
    public void recordCacheHit(Duration duration) {
        recordCacheHit(VISIBILITY_PUBLIC, duration);
    }

    /**
     * Records a semantic cache hit with specified visibility scope.
     *
     * @param visibility visibility scope (bounded to PUBLIC or sanitized to UNKNOWN)
     * @param duration   total elapsed request duration
     */
    public void recordCacheHit(String visibility, Duration duration) {
        Objects.requireNonNull(duration, "duration must not be null");
        String sanitizedVisibility = sanitizeVisibility(visibility);

        meterRegistry.counter(METRIC_REQUESTS_TOTAL,
                Tags.of(TAG_OUTCOME, CacheOutcome.HIT.tagValue(), TAG_STATUS, OperationStatus.SUCCESS.tagValue())).increment();

        meterRegistry.counter(METRIC_CACHE_HITS,
                Tags.of(TAG_VISIBILITY, sanitizedVisibility)).increment();

        meterRegistry.timer(METRIC_REQUEST_DURATION,
                Tags.of(TAG_OUTCOME, CacheOutcome.HIT.tagValue())).record(duration);
    }

    /**
     * Records a semantic cache miss with its specific operational cause and duration.
     *
     * @param reason   the bounded reason for the miss
     * @param duration total elapsed request duration
     */
    public void recordCacheMiss(MissReason reason, Duration duration) {
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(duration, "duration must not be null");

        recordCacheMiss(reason);
        recordCacheMissCompleted(duration);
    }

    /**
     * Records an independent semantic cache miss event with its cause, without concluding
     * or recording the overall request outcome or duration.
     *
     * @param reason the bounded reason for the miss
     */
    public void recordCacheMiss(MissReason reason) {
        Objects.requireNonNull(reason, "reason must not be null");

        meterRegistry.counter(METRIC_CACHE_MISSES,
                Tags.of(TAG_REASON, reason.tagValue())).increment();
    }

    /**
     * Records the successful overall request completion following a cache miss with its elapsed duration.
     * Does not increment the cache-miss counter.
     *
     * @param duration total elapsed request duration
     */
    public void recordCacheMissCompleted(Duration duration) {
        Objects.requireNonNull(duration, "duration must not be null");

        meterRegistry.counter(METRIC_REQUESTS_TOTAL,
                Tags.of(TAG_OUTCOME, CacheOutcome.MISS.tagValue(), TAG_STATUS, OperationStatus.SUCCESS.tagValue())).increment();

        meterRegistry.timer(METRIC_REQUEST_DURATION,
                Tags.of(TAG_OUTCOME, CacheOutcome.MISS.tagValue())).record(duration);
    }

    /**
     * Records a failed cache request due to an unhandled exception or upstream failure.
     *
     * @param duration total elapsed duration before failure
     */
    public void recordCacheError(Duration duration) {
        Objects.requireNonNull(duration, "duration must not be null");

        meterRegistry.counter(METRIC_REQUESTS_TOTAL,
                Tags.of(TAG_OUTCOME, CacheOutcome.ERROR.tagValue(), TAG_STATUS, OperationStatus.ERROR.tagValue())).increment();

        meterRegistry.timer(METRIC_REQUEST_DURATION,
                Tags.of(TAG_OUTCOME, CacheOutcome.ERROR.tagValue())).record(duration);
    }

    /**
     * Records a candidate similarity score into the distribution summary.
     * Scores must be finite numbers between 0.0 and 1.0.
     *
     * @param score   the computed similarity score
     * @param outcome whether the evaluation resulted in a hit or miss
     */
    public void recordSimilarityScore(double score, CacheOutcome outcome) {
        Objects.requireNonNull(outcome, "outcome must not be null");
        if (Double.isFinite(score) && score >= 0.0 && score <= 1.0) {
            meterRegistry.summary(METRIC_SIMILARITY_SCORE,
                    Tags.of(TAG_OUTCOME, outcome.tagValue())).record(score);
        }
    }

    /**
     * Starts a timer sample for measuring asynchronous or multi-step execution.
     *
     * @return Timer.Sample instance
     */
    public Timer.Sample startTimer() {
        return Timer.start(meterRegistry);
    }

    /**
     * Stops a timer sample and records the duration under the request timer metric.
     *
     * @param sample  the active sample to stop
     * @param outcome the final outcome of the request
     */
    public void stopRequestTimer(Timer.Sample sample, CacheOutcome outcome) {
        Objects.requireNonNull(sample, "sample must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");
        sample.stop(meterRegistry.timer(METRIC_REQUEST_DURATION, Tags.of(TAG_OUTCOME, outcome.tagValue())));
    }

    // ==========================================
    // Downstream Gemini Generation Metrics
    // ==========================================

    /**
     * Records a Gemini text generation call outcome and duration.
     *
     * @param model    the model name (e.g. "gemini-3.8-flash")
     * @param status   operation status (SUCCESS or ERROR)
     * @param duration elapsed time of the generation call
     */
    public void recordGeminiGeneration(String model, OperationStatus status, Duration duration) {
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(duration, "duration must not be null");
        String sanitizedModel = sanitizeModelName(model);

        meterRegistry.counter(METRIC_GEMINI_GENERATION_CALLS,
                Tags.of(TAG_MODEL, sanitizedModel, TAG_STATUS, status.tagValue())).increment();

        meterRegistry.timer(METRIC_GEMINI_GENERATION_DURATION,
                Tags.of(TAG_MODEL, sanitizedModel, TAG_STATUS, status.tagValue())).record(duration);
    }

    // ==========================================
    // Downstream Gemini Embedding Metrics
    // ==========================================

    /**
     * Records a Gemini embedding generation call outcome and duration.
     *
     * @param model    the embedding model name (e.g. "gemini-embedding-001")
     * @param status   operation status (SUCCESS or ERROR)
     * @param duration elapsed time of the embedding call
     */
    public void recordGeminiEmbedding(String model, OperationStatus status, Duration duration) {
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(duration, "duration must not be null");
        String sanitizedModel = sanitizeModelName(model);

        meterRegistry.counter(METRIC_GEMINI_EMBEDDING_CALLS,
                Tags.of(TAG_MODEL, sanitizedModel, TAG_STATUS, status.tagValue())).increment();

        meterRegistry.timer(METRIC_GEMINI_EMBEDDING_DURATION,
                Tags.of(TAG_MODEL, sanitizedModel, TAG_STATUS, status.tagValue())).record(duration);
    }

    // ==========================================
    // Downstream Qdrant Vector DB Metrics
    // ==========================================

    /**
     * Records a Qdrant operation outcome and latency.
     *
     * @param operation the type of Qdrant operation (SEARCH, RETRIEVE, UPSERT)
     * @param status    the operation outcome status (SUCCESS, ERROR, TIMEOUT)
     * @param duration  elapsed time of the Qdrant operation
     */
    public void recordQdrantOperation(QdrantOperation operation, OperationStatus status, Duration duration) {
        Objects.requireNonNull(operation, "operation must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(duration, "duration must not be null");

        meterRegistry.counter(METRIC_QDRANT_OPERATIONS,
                Tags.of(TAG_OPERATION, operation.tagValue(), TAG_STATUS, status.tagValue())).increment();

        meterRegistry.timer(METRIC_QDRANT_OPERATION_DURATION,
                Tags.of(TAG_OPERATION, operation.tagValue(), TAG_STATUS, status.tagValue())).record(duration);
    }

    /**
     * Maximum integer representable in an IEEE 754 double without loss of precision (2^53 - 1).
     */
    private static final long MAX_EXACT_DOUBLE_INTEGER = (1L << 53) - 1;

    public MeterRegistry getMeterRegistry() {
        return meterRegistry;
    }

    /**
     * Builds an aggregated snapshot of high-level cache telemetry.
     *
     * @return an immutable {@link CacheMetricsResponse}
     */
    public CacheMetricsResponse getSnapshot() {
        long totalRequests = sumCounters(METRIC_REQUESTS_TOTAL);
        long cacheHits = sumCounters(METRIC_CACHE_HITS);
        long cacheMisses = sumCounters(METRIC_CACHE_MISSES);
        long requestErrors = sumCounters(METRIC_REQUESTS_TOTAL, TAG_OUTCOME, CacheOutcome.ERROR.tagValue());

        double hitRate = 0.0;
        long evaluatedRequests;
        try {
            evaluatedRequests = Math.addExact(cacheHits, cacheMisses);
        } catch (ArithmeticException e) {
            throw new IllegalStateException("Overflow detected while calculating evaluated cache requests", e);
        }

        if (evaluatedRequests > 0) {
            hitRate = (double) cacheHits / (double) evaluatedRequests;
        }

        long geminiGeneration = sumCounters(METRIC_GEMINI_GENERATION_CALLS);
        long geminiEmbedding = sumCounters(METRIC_GEMINI_EMBEDDING_CALLS);
        long qdrantOps = sumCounters(METRIC_QDRANT_OPERATIONS);

        return new CacheMetricsResponse(
                totalRequests,
                cacheHits,
                cacheMisses,
                requestErrors,
                hitRate,
                geminiGeneration,
                geminiEmbedding,
                qdrantOps
        );
    }

    private long sumCounters(String metricName) {
        Collection<Counter> counters = meterRegistry.find(metricName).counters();
        if (counters == null || counters.isEmpty()) {
            return 0L;
        }
        long sum = 0L;
        for (Counter counter : counters) {
            long count = toSafeCount(counter, metricName);
            try {
                sum = Math.addExact(sum, count);
            } catch (ArithmeticException e) {
                throw new IllegalStateException("Overflow detected while aggregating metric [" + metricName + "]", e);
            }
        }
        return sum;
    }

    private long sumCounters(String metricName, String tagKey, String tagValue) {
        Collection<Counter> counters = meterRegistry.find(metricName).tag(tagKey, tagValue).counters();
        if (counters == null || counters.isEmpty()) {
            return 0L;
        }
        long sum = 0L;
        for (Counter counter : counters) {
            long count = toSafeCount(counter, metricName);
            try {
                sum = Math.addExact(sum, count);
            } catch (ArithmeticException e) {
                throw new IllegalStateException("Overflow detected while aggregating metric [" + metricName + "]", e);
            }
        }
        return sum;
    }

    private long toSafeCount(Counter counter, String metricName) {
        if (counter == null) {
            return 0L;
        }
        double rawCount = counter.count();
        if (Double.isNaN(rawCount)) {
            throw new IllegalStateException("Counter value for metric [" + metricName + "] is NaN");
        }
        if (Double.isInfinite(rawCount)) {
            throw new IllegalStateException("Counter value for metric [" + metricName + "] is infinite: " + rawCount);
        }
        if (rawCount < 0.0) {
            throw new IllegalStateException("Counter value for metric [" + metricName + "] is negative: " + rawCount);
        }
        if (rawCount > MAX_EXACT_DOUBLE_INTEGER) {
            throw new IllegalStateException("Counter value for metric [" + metricName + "] exceeds maximum exact integer precision (2^53 - 1): " + rawCount);
        }
        if (Math.floor(rawCount) != rawCount) {
            throw new IllegalStateException("Counter value for metric [" + metricName + "] contains a fractional component: " + rawCount);
        }
        return (long) rawCount;
    }

    private String sanitizeModelName(String model) {
        if (model == null || model.trim().isEmpty()) {
            return MODEL_OTHER;
        }
        String normalized = model.trim().toLowerCase(Locale.ROOT);
        return ALLOWED_MODELS.contains(normalized) ? normalized : MODEL_OTHER;
    }

    private String sanitizeVisibility(String visibility) {
        if (visibility == null || visibility.trim().isEmpty()) {
            return VISIBILITY_UNKNOWN;
        }
        String upper = visibility.trim().toUpperCase(Locale.ROOT);
        if (VISIBILITY_PUBLIC.equals(upper) || "PRIVATE".equals(upper)) {
            return upper;
        }
        return VISIBILITY_UNKNOWN;
    }
}
