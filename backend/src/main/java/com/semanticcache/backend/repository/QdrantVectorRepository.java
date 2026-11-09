package com.semanticcache.backend.repository;

import com.semanticcache.backend.config.QdrantConfig;
import com.semanticcache.backend.dto.SearchFilter;
import com.semanticcache.backend.dto.SearchResult;
import com.semanticcache.backend.dto.VectorRecord;
import com.semanticcache.backend.exception.QdrantOperationException;
import io.qdrant.client.ConditionFactory;
import io.qdrant.client.PointIdFactory;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.ValueFactory;
import io.qdrant.client.VectorsFactory;
import io.qdrant.client.WithPayloadSelectorFactory;
import io.qdrant.client.WithVectorsSelectorFactory;
import io.qdrant.client.grpc.Common;
import io.qdrant.client.grpc.JsonWithInt;
import io.qdrant.client.grpc.Points;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.google.common.util.concurrent.ListenableFuture;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Repository
public class QdrantVectorRepository implements VectorRepository {

    private static final Logger log = LoggerFactory.getLogger(QdrantVectorRepository.class);

    private static final String PAYLOAD_PROMPT = "prompt";
    private static final String PAYLOAD_ANSWER = "answer";
    private static final String PAYLOAD_MODEL = "model";
    private static final String PAYLOAD_CREATED_AT = "createdAt";
    private static final String PAYLOAD_EMBEDDING_MODEL = "embeddingModel";
    private static final String PAYLOAD_OWNER_ID = "ownerId";
    private static final String PAYLOAD_VISIBILITY = "visibility";
    private static final String VISIBILITY_PUBLIC = "PUBLIC";

    private final QdrantClient qdrantClient;
    private final QdrantConfig qdrantConfig;

    public QdrantVectorRepository(QdrantClient qdrantClient, QdrantConfig qdrantConfig) {
        this.qdrantClient = Objects.requireNonNull(qdrantClient, "qdrantClient must not be null");
        this.qdrantConfig = Objects.requireNonNull(qdrantConfig, "qdrantConfig must not be null");
    }

    @Override
    public void upsert(VectorRecord record) {
        Objects.requireNonNull(record, "record must not be null");

        int configuredDimension = qdrantConfig.getVectorSize();
        if (record.vector().size() != configuredDimension) {
            throw new IllegalArgumentException(
                    "Vector dimension [" + record.vector().size() + "] does not match configured dimension [" + configuredDimension + "]"
            );
        }

        String collectionName = qdrantConfig.getCollectionName();
        log.debug("Upserting vector record with ID [{}] into collection [{}]", record.id(), collectionName);

        ListenableFuture<Points.UpdateResult> future = null;
        try {
            Points.PointStruct pointStruct = Points.PointStruct.newBuilder()
                    .setId(PointIdFactory.id(record.id()))
                    .setVectors(VectorsFactory.vectors(record.vector()))
                    .putPayload(PAYLOAD_PROMPT, ValueFactory.value(record.prompt()))
                    .putPayload(PAYLOAD_ANSWER, ValueFactory.value(record.answer()))
                    .putPayload(PAYLOAD_MODEL, ValueFactory.value(record.model()))
                    .putPayload(PAYLOAD_CREATED_AT, ValueFactory.value(record.createdAt().toString()))
                    .putPayload(PAYLOAD_EMBEDDING_MODEL, ValueFactory.value(record.embeddingModel()))
                    .build();

            future = qdrantClient.upsertAsync(collectionName, List.of(pointStruct));
            future.get(qdrantConfig.getTimeoutSeconds(), TimeUnit.SECONDS);
            log.debug("Successfully upserted vector record with ID [{}] into collection [{}]", record.id(), collectionName);
        } catch (TimeoutException e) {
            cancelFuture(future);
            log.error("Timed out waiting for Qdrant upsert operation in collection [{}] after [{}] seconds",
                    collectionName, qdrantConfig.getTimeoutSeconds());
            throw new QdrantOperationException("Failed to upsert vector record in Qdrant due to operation timeout", e);
        } catch (InterruptedException e) {
            cancelFuture(future);
            Thread.currentThread().interrupt();
            log.error("Thread interrupted while upserting vector record with ID [{}] into collection [{}]",
                    record.id(), collectionName);
            throw new QdrantOperationException("Failed to upsert vector record due to thread interruption", e);
        } catch (ExecutionException e) {
            log.error("Failed to upsert vector record with ID [{}] into collection [{}]: exception class [{}]",
                    record.id(), collectionName, e.getClass().getName());
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new QdrantOperationException("Failed to upsert vector record in Qdrant", cause);
        } catch (Exception e) {
            log.error("Failed to upsert vector record with ID [{}] into collection [{}]: exception class [{}]",
                    record.id(), collectionName, e.getClass().getName());
            throw new QdrantOperationException("Failed to upsert vector record in Qdrant", e);
        }
    }

    @Override
    public Optional<VectorRecord> findById(UUID id) {
        Objects.requireNonNull(id, "id must not be null");

        String collectionName = qdrantConfig.getCollectionName();
        log.debug("Retrieving vector record with ID [{}] from collection [{}]", id, collectionName);

        ListenableFuture<List<Points.RetrievedPoint>> future = null;
        try {
            future = qdrantClient.retrieveAsync(
                    collectionName,
                    PointIdFactory.id(id),
                    true,
                    true,
                    null
            );
            List<Points.RetrievedPoint> points = future.get(qdrantConfig.getTimeoutSeconds(), TimeUnit.SECONDS);

            if (points == null || points.isEmpty()) {
                log.debug("No vector record found for ID [{}] in collection [{}]", id, collectionName);
                return Optional.empty();
            }

            Points.RetrievedPoint point = points.get(0);
            return Optional.of(mapToVectorRecord(id, point));
        } catch (QdrantOperationException e) {
            throw e;
        } catch (TimeoutException e) {
            cancelFuture(future);
            log.error("Timed out waiting for Qdrant retrieve operation for ID [{}] in collection [{}] after [{}] seconds",
                    id, collectionName, qdrantConfig.getTimeoutSeconds());
            throw new QdrantOperationException("Failed to retrieve vector record from Qdrant due to operation timeout", e);
        } catch (InterruptedException e) {
            cancelFuture(future);
            Thread.currentThread().interrupt();
            log.error("Thread interrupted while retrieving vector record with ID [{}] from collection [{}]",
                    id, collectionName);
            throw new QdrantOperationException("Failed to retrieve vector record due to thread interruption", e);
        } catch (ExecutionException e) {
            log.error("Failed to retrieve vector record with ID [{}] from collection [{}]: exception class [{}]",
                    id, collectionName, e.getClass().getName());
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new QdrantOperationException("Failed to retrieve vector record from Qdrant", cause);
        } catch (Exception e) {
            log.error("Failed to retrieve vector record with ID [{}] from collection [{}]: exception class [{}]",
                    id, collectionName, e.getClass().getName());
            throw new QdrantOperationException("Failed to retrieve vector record from Qdrant", e);
        }
    }

    /**
     * Unrestricted low-level vector search. Retained solely for administrative, diagnostic, and internal testing.
     * Application retrieval paths MUST NOT invoke this method; use {@link #search(List, int, SearchFilter)} instead.
     */
    @Override
    public List<SearchResult> search(List<Float> queryVector, int limit) {
        log.debug("Executing unrestricted low-level search with limit [{}]", limit);
        return executeSearchInternal(queryVector, limit, null);
    }

    @Override
    public List<SearchResult> search(List<Float> queryVector, int limit, SearchFilter filter) {
        Objects.requireNonNull(filter, "filter must not be null");
        Common.Filter qdrantFilter = buildQdrantFilter(filter);
        return executeSearchInternal(queryVector, limit, qdrantFilter);
    }

    private List<SearchResult> executeSearchInternal(List<Float> queryVector, int limit, Common.Filter qdrantFilter) {
        if (queryVector == null || queryVector.isEmpty()) {
            throw new IllegalArgumentException("queryVector must not be null or empty");
        }
        for (Float val : queryVector) {
            if (val == null) {
                throw new IllegalArgumentException("queryVector elements must not be null");
            }
            if (!Float.isFinite(val)) {
                throw new IllegalArgumentException("queryVector elements must be finite numerical values: " + val);
            }
        }

        int configuredDimension = qdrantConfig.getVectorSize();
        if (queryVector.size() != configuredDimension) {
            throw new IllegalArgumentException(
                    "Query vector dimension [" + queryVector.size() + "] does not match configured dimension [" + configuredDimension + "]"
            );
        }

        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be greater than zero. Provided: " + limit);
        }

        String collectionName = qdrantConfig.getCollectionName();
        log.debug("Searching collection [{}] for top [{}] similar records (filter: [{}])",
                collectionName, limit, qdrantFilter != null ? "applied" : "unrestricted");

        ListenableFuture<List<Points.ScoredPoint>> future = null;
        try {
            Points.SearchPoints.Builder searchPointsBuilder = Points.SearchPoints.newBuilder()
                    .setCollectionName(collectionName)
                    .addAllVector(queryVector)
                    .setLimit(limit)
                    .setWithPayload(WithPayloadSelectorFactory.enable(true))
                    .setWithVectors(WithVectorsSelectorFactory.enable(false));

            if (qdrantFilter != null) {
                searchPointsBuilder.setFilter(qdrantFilter);
            }

            future = qdrantClient.searchAsync(searchPointsBuilder.build());
            List<Points.ScoredPoint> scoredPoints = future.get(qdrantConfig.getTimeoutSeconds(), TimeUnit.SECONDS);

            if (scoredPoints == null || scoredPoints.isEmpty()) {
                log.debug("No similar records found in collection [{}]", collectionName);
                return Collections.emptyList();
            }

            List<SearchResult> results = new ArrayList<>(scoredPoints.size());
            for (Points.ScoredPoint scoredPoint : scoredPoints) {
                results.add(mapToSearchResult(scoredPoint));
            }

            log.debug("Found [{}] matching records in collection [{}]", results.size(), collectionName);
            return Collections.unmodifiableList(results);
        } catch (QdrantOperationException e) {
            throw e;
        } catch (TimeoutException e) {
            cancelFuture(future);
            log.error("Timed out waiting for Qdrant search operation in collection [{}] after [{}] seconds",
                    collectionName, qdrantConfig.getTimeoutSeconds());
            throw new QdrantOperationException("Failed to search vector records in Qdrant due to operation timeout", e);
        } catch (InterruptedException e) {
            cancelFuture(future);
            Thread.currentThread().interrupt();
            log.error("Thread interrupted while searching collection [{}]", collectionName);
            throw new QdrantOperationException("Failed to search vector records due to thread interruption", e);
        } catch (ExecutionException e) {
            log.error("Failed to search vector records in collection [{}]: exception class [{}]",
                    collectionName, e.getClass().getName());
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new QdrantOperationException("Failed to search vector records in Qdrant", cause);
        } catch (Exception e) {
            log.error("Failed to search vector records in collection [{}]: exception class [{}]",
                    collectionName, e.getClass().getName());
            throw new QdrantOperationException("Failed to search vector records in Qdrant", e);
        }
    }

    private void cancelFuture(ListenableFuture<?> future) {
        if (future != null) {
            try {
                future.cancel(true);
            } catch (Exception e) {
                log.warn("Failed to cancel Qdrant future: exception class [{}]", e.getClass().getName());
            }
        }
    }

    Common.Filter buildQdrantFilter(SearchFilter filter) {
        Objects.requireNonNull(filter, "filter must not be null");

        if (filter.isPublicOnly()) {
            return Common.Filter.newBuilder()
                    .addMust(ConditionFactory.matchKeyword(PAYLOAD_VISIBILITY, VISIBILITY_PUBLIC))
                    .build();
        }

        if (filter.hasOwner()) {
            return Common.Filter.newBuilder()
                    .addShould(ConditionFactory.matchKeyword(PAYLOAD_OWNER_ID, filter.ownerId()))
                    .addShould(ConditionFactory.matchKeyword(PAYLOAD_VISIBILITY, VISIBILITY_PUBLIC))
                    .build();
        }

        throw new IllegalArgumentException("Unsupported SearchFilter state: " + filter);
    }

    private SearchResult mapToSearchResult(Points.ScoredPoint point) {
        if (point == null) {
            throw new QdrantOperationException("Retrieved scored point is null");
        }

        if (!point.hasId() || !point.getId().hasUuid()) {
            throw new QdrantOperationException("Retrieved scored point is missing a valid UUID point ID");
        }

        UUID pointId;
        try {
            pointId = UUID.fromString(point.getId().getUuid());
        } catch (IllegalArgumentException e) {
            throw new QdrantOperationException("Retrieved scored point has malformed UUID", e);
        }

        Map<String, JsonWithInt.Value> payload = point.getPayloadMap();

        String prompt = extractStringField(payload, PAYLOAD_PROMPT);
        String answer = extractStringField(payload, PAYLOAD_ANSWER);
        String model = extractStringField(payload, PAYLOAD_MODEL);
        Instant createdAt = extractCreatedAt(payload);
        String embeddingModel = extractStringField(payload, PAYLOAD_EMBEDDING_MODEL);
        float score = point.getScore();

        String ownerId = extractOptionalStringField(payload, PAYLOAD_OWNER_ID);
        String visibility = extractOptionalStringField(payload, PAYLOAD_VISIBILITY);

        try {
            return new SearchResult(
                    pointId,
                    prompt,
                    answer,
                    model,
                    createdAt,
                    embeddingModel,
                    score,
                    ownerId,
                    visibility
            );
        } catch (IllegalArgumentException e) {
            throw new QdrantOperationException("Retrieved scored point contains invalid search result data: " + e.getMessage(), e);
        }
    }

    private VectorRecord mapToVectorRecord(UUID expectedId, Points.RetrievedPoint point) {
        if (point == null) {
            throw new QdrantOperationException("Retrieved point is null");
        }

        if (!point.hasId() || !point.getId().hasUuid()) {
            throw new QdrantOperationException("Retrieved point is missing a valid UUID point ID");
        }

        UUID pointId;
        try {
            pointId = UUID.fromString(point.getId().getUuid());
        } catch (IllegalArgumentException e) {
            throw new QdrantOperationException("Retrieved point has malformed UUID: [" + point.getId().getUuid() + "]", e);
        }

        if (!expectedId.equals(pointId)) {
            throw new QdrantOperationException(
                    "Retrieved point ID [" + pointId + "] does not match requested ID [" + expectedId + "]"
            );
        }

        List<Float> vector = extractVector(point);
        Map<String, JsonWithInt.Value> payload = point.getPayloadMap();

        String prompt = extractStringField(payload, PAYLOAD_PROMPT);
        String answer = extractStringField(payload, PAYLOAD_ANSWER);
        String model = extractStringField(payload, PAYLOAD_MODEL);
        Instant createdAt = extractCreatedAt(payload);
        String embeddingModel = extractStringField(payload, PAYLOAD_EMBEDDING_MODEL);

        try {
            return new VectorRecord(
                    pointId,
                    vector,
                    prompt,
                    answer,
                    model,
                    createdAt,
                    embeddingModel
            );
        } catch (IllegalArgumentException e) {
            throw new QdrantOperationException("Retrieved point contains invalid vector record data: " + e.getMessage(), e);
        }
    }

    private List<Float> extractVector(Points.RetrievedPoint point) {
        if (!point.hasVectors()) {
            throw new QdrantOperationException("Retrieved point is missing vectors");
        }

        Points.VectorsOutput vectorsOutput = point.getVectors();
        if (!vectorsOutput.hasVector()) {
            throw new QdrantOperationException("Retrieved point does not contain a single vector output");
        }

        Points.VectorOutput vectorOutput = vectorsOutput.getVector();
        List<Float> data = null;
        if (vectorOutput.hasDense()) {
            data = vectorOutput.getDense().getDataList();
        }

        if (data == null || data.isEmpty()) {
            throw new QdrantOperationException("Retrieved point has empty vector data");
        }

        int configuredDimension = qdrantConfig.getVectorSize();
        if (data.size() != configuredDimension) {
            throw new QdrantOperationException(
                    "Retrieved point vector dimension [" + data.size() + "] does not match configured dimension [" + configuredDimension + "]"
            );
        }

        return data;
    }

    private String extractStringField(Map<String, JsonWithInt.Value> payload, String fieldName) {
        JsonWithInt.Value val = payload.get(fieldName);
        if (val == null || !val.hasStringValue() || val.getStringValue().isBlank()) {
            throw new QdrantOperationException("Missing or invalid payload field [" + fieldName + "]");
        }
        return val.getStringValue();
    }

    private String extractOptionalStringField(Map<String, JsonWithInt.Value> payload, String fieldName) {
        JsonWithInt.Value val = payload.get(fieldName);
        if (val != null && val.hasStringValue() && !val.getStringValue().isBlank()) {
            return val.getStringValue().trim();
        }
        return null;
    }

    private Instant extractCreatedAt(Map<String, JsonWithInt.Value> payload) {
        String createdAtStr = extractStringField(payload, PAYLOAD_CREATED_AT);
        try {
            return Instant.parse(createdAtStr);
        } catch (DateTimeParseException e) {
            throw new QdrantOperationException("Invalid createdAt timestamp in stored Qdrant payload", e);
        }
    }
}
