package com.semanticcache.backend.service;

import com.google.genai.Client;
import com.google.genai.types.ContentEmbedding;
import com.google.genai.types.EmbedContentConfig;
import com.google.genai.types.EmbedContentResponse;
import com.semanticcache.backend.exception.EmbeddingEmptyResponseException;
import com.semanticcache.backend.exception.EmbeddingException;
import com.semanticcache.backend.exception.GeminiMissingCredentialsException;
import com.semanticcache.backend.provider.GeminiClientProvider;
import com.semanticcache.backend.telemetry.CacheMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeoutException;

@Service
public class GeminiEmbeddingService implements EmbeddingProvider {

    private static final Logger log = LoggerFactory.getLogger(GeminiEmbeddingService.class);

    private final GeminiClientProvider clientProvider;
    private final String model;
    private final int dimension;
    private final String taskType;
    private final CacheMetrics cacheMetrics;

    @Autowired
    public GeminiEmbeddingService(
            GeminiClientProvider clientProvider,
            @Value("${gemini.embedding-model:gemini-embedding-001}") String model,
            @Value("${gemini.embedding-dimension:768}") int dimension,
            @Value("${gemini.embedding-task-type:SEMANTIC_SIMILARITY}") String taskType,
            CacheMetrics cacheMetrics
    ) {
        this.clientProvider = Objects.requireNonNull(clientProvider, "clientProvider must not be null");
        this.cacheMetrics = Objects.requireNonNull(cacheMetrics, "cacheMetrics must not be null");

        if (model == null || model.trim().isEmpty()) {
            throw new IllegalArgumentException("gemini.embedding-model must not be null or blank");
        }
        if (dimension <= 0) {
            throw new IllegalArgumentException("gemini.embedding-dimension must be greater than zero. Configured value: " + dimension);
        }
        if (taskType == null || taskType.trim().isEmpty()) {
            throw new IllegalArgumentException("gemini.embedding-task-type must not be null or blank");
        }

        this.model = model.trim();
        this.dimension = dimension;
        this.taskType = taskType.trim();
    }

    @Override
    public List<Float> generateEmbedding(String text) {
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("Input text for embedding generation must not be null or blank.");
        }

        log.info("Requesting embedding from Gemini API using model: [{}], dimension: [{}], taskType: [{}]",
                model, dimension, taskType);

        long startNanos = System.nanoTime();
        try {
            Client client = clientProvider.getClient();

            EmbedContentConfig config = EmbedContentConfig.builder()
                    .taskType(taskType)
                    .outputDimensionality(dimension)
                    .build();

            EmbedContentResponse response = client.models.embedContent(model, text.trim(), config);

            if (response == null || response.embeddings().isEmpty() || response.embeddings().get().isEmpty()) {
                throw new EmbeddingEmptyResponseException("Received null or empty embeddings from Gemini Embedding API.");
            }

            ContentEmbedding contentEmbedding = response.embeddings().get().get(0);
            if (contentEmbedding == null || contentEmbedding.values().isEmpty()) {
                throw new EmbeddingEmptyResponseException("Gemini Embedding API returned missing vector values.");
            }

            List<Float> values = contentEmbedding.values().get();
            if (values == null || values.isEmpty()) {
                throw new EmbeddingEmptyResponseException("Gemini Embedding API returned an empty embedding vector.");
            }

            validateVector(values);

            List<Float> result = List.copyOf(values);
            Duration duration = Duration.ofNanos(System.nanoTime() - startNanos);
            cacheMetrics.recordGeminiEmbedding(model, CacheMetrics.OperationStatus.SUCCESS, duration);
            return result;
        } catch (GeminiMissingCredentialsException | EmbeddingException e) {
            Duration duration = Duration.ofNanos(System.nanoTime() - startNanos);
            CacheMetrics.OperationStatus status = isTimeout(e)
                    ? CacheMetrics.OperationStatus.TIMEOUT
                    : CacheMetrics.OperationStatus.ERROR;
            cacheMetrics.recordGeminiEmbedding(model, status, duration);
            if (hasInterruptedException(e)) {
                Thread.currentThread().interrupt();
            }
            throw e;
        } catch (Exception e) {
            Duration duration = Duration.ofNanos(System.nanoTime() - startNanos);
            CacheMetrics.OperationStatus status = isTimeout(e)
                    ? CacheMetrics.OperationStatus.TIMEOUT
                    : CacheMetrics.OperationStatus.ERROR;
            cacheMetrics.recordGeminiEmbedding(model, status, duration);

            if (hasInterruptedException(e)) {
                Thread.currentThread().interrupt();
            }

            log.error("Gemini embedding call failed for model [{}]: exception class [{}]", model, e.getClass().getName());
            throw new EmbeddingException("Failed to generate text embedding from the AI service", e);
        }
    }

    private void validateVector(List<Float> vector) {
        if (vector.size() != dimension) {
            throw new EmbeddingException(
                    "Generated embedding dimension [" + vector.size() + "] does not match expected dimension [" + dimension + "]."
            );
        }

        for (Float val : vector) {
            if (val == null) {
                throw new EmbeddingException("Generated embedding vector contains null elements.");
            }
            if (!Float.isFinite(val)) {
                throw new EmbeddingException("Generated embedding vector contains non-finite numerical value: " + val);
            }
        }
    }

    public String getModel() {
        return model;
    }

    public int getDimension() {
        return dimension;
    }

    public String getTaskType() {
        return taskType;
    }

    private boolean isTimeout(Throwable throwable) {
        Throwable current = throwable;
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        while (current != null && visited.add(current)) {
            if (current instanceof TimeoutException
                    || current instanceof SocketTimeoutException
                    || current instanceof HttpTimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean hasInterruptedException(Throwable throwable) {
        Throwable current = throwable;
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        while (current != null && visited.add(current)) {
            if (current instanceof InterruptedException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
