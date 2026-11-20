package com.semanticcache.backend.repository;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.semanticcache.backend.config.QdrantConfig;
import com.semanticcache.backend.dto.SearchFilter;
import com.semanticcache.backend.dto.SearchResult;
import com.semanticcache.backend.dto.VectorRecord;
import com.semanticcache.backend.exception.QdrantOperationException;
import com.semanticcache.backend.telemetry.CacheMetrics;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.StatusRuntimeException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.qdrant.client.PointIdFactory;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.ValueFactory;
import io.qdrant.client.grpc.Common;
import io.qdrant.client.grpc.JsonWithInt;
import io.qdrant.client.grpc.Points;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Constructor;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QdrantVectorRepositoryTest {

    private static final String COLLECTION_NAME = "semantic_cache";
    private static final int VECTOR_SIZE = 768;

    @Mock
    private QdrantClient mockClient;

    private SimpleMeterRegistry meterRegistry;
    private CacheMetrics cacheMetrics;
    private QdrantConfig qdrantConfig;
    private QdrantVectorRepository repository;

    private UUID sampleId;
    private List<Float> sampleVector;
    private Instant sampleCreatedAt;
    private VectorRecord sampleRecord;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        cacheMetrics = new CacheMetrics(meterRegistry);
        qdrantConfig = new QdrantConfig("localhost", 6334, false, "", COLLECTION_NAME, VECTOR_SIZE);
        repository = new QdrantVectorRepository(mockClient, qdrantConfig, cacheMetrics);

        sampleId = UUID.randomUUID();
        sampleVector = new ArrayList<>(Collections.nCopies(VECTOR_SIZE, 0.05f));
        sampleCreatedAt = Instant.parse("2026-11-07T20:00:00Z");

        sampleRecord = new VectorRecord(
                sampleId,
                sampleVector,
                "What is semantic caching?",
                "Semantic caching stores embeddings to find similar queries.",
                "gemini-3.8-flash",
                sampleCreatedAt,
                "gemini-embedding-001"
        );
    }

    private Points.RetrievedPoint buildRetrievedPoint(UUID id, List<Float> vector, Map<String, String> payloadMap) {
        Points.RetrievedPoint.Builder builder = Points.RetrievedPoint.newBuilder();

        if (id != null) {
            builder.setId(PointIdFactory.id(id));
        }

        if (vector != null) {
            Points.DenseVector denseVector = Points.DenseVector.newBuilder()
                    .addAllData(vector)
                    .build();
            Points.VectorOutput vectorOutput = Points.VectorOutput.newBuilder()
                    .setDense(denseVector)
                    .build();
            Points.VectorsOutput vectorsOutput = Points.VectorsOutput.newBuilder()
                    .setVector(vectorOutput)
                    .build();
            builder.setVectors(vectorsOutput);
        }

        if (payloadMap != null) {
            payloadMap.forEach((k, v) -> builder.putPayload(k, ValueFactory.value(v)));
        }

        return builder.build();
    }

    private Points.ScoredPoint buildScoredPoint(UUID id, float score, Map<String, String> payloadMap) {
        Points.ScoredPoint.Builder builder = Points.ScoredPoint.newBuilder();

        if (id != null) {
            builder.setId(PointIdFactory.id(id));
        }

        builder.setScore(score);

        if (payloadMap != null) {
            payloadMap.forEach((k, v) -> builder.putPayload(k, ValueFactory.value(v)));
        }

        return builder.build();
    }

    private Map<String, String> buildSamplePayloadMap() {
        Map<String, String> map = new HashMap<>();
        map.put("prompt", "What is semantic caching?");
        map.put("answer", "Semantic caching stores embeddings to find similar queries.");
        map.put("model", "gemini-3.8-flash");
        map.put("createdAt", "2026-11-07T20:00:00Z");
        map.put("embeddingModel", "gemini-embedding-001");
        return map;
    }

    @Nested
    @DisplayName("Upsert Operations")
    class UpsertTests {

        @Test
        @DisplayName("Successfully upserts VectorRecord with converted protobuf types and payload")
        void successfulUpsert() {
            when(mockClient.upsertAsync(eq(COLLECTION_NAME), any()))
                    .thenReturn(Futures.immediateFuture(Points.UpdateResult.getDefaultInstance()));

            repository.upsert(sampleRecord);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<Points.PointStruct>> captor = ArgumentCaptor.forClass(List.class);
            verify(mockClient).upsertAsync(eq(COLLECTION_NAME), captor.capture());

            List<Points.PointStruct> points = captor.getValue();
            assertEquals(1, points.size());

            Points.PointStruct point = points.get(0);
            assertEquals(sampleId.toString(), point.getId().getUuid());

            // Vector check
            Points.Vector vector = point.getVectors().getVector();
            assertTrue(vector.hasDense());
            assertEquals(VECTOR_SIZE, vector.getDense().getDataList().size());
            assertEquals(0.05f, vector.getDense().getDataList().get(0), 1e-6);

            // Payload checks
            Map<String, JsonWithInt.Value> payload = point.getPayloadMap();
            assertEquals("What is semantic caching?", payload.get("prompt").getStringValue());
            assertEquals("Semantic caching stores embeddings to find similar queries.", payload.get("answer").getStringValue());
            assertEquals("gemini-3.8-flash", payload.get("model").getStringValue());
            assertEquals("2026-11-07T20:00:00Z", payload.get("createdAt").getStringValue());
            assertEquals("gemini-embedding-001", payload.get("embeddingModel").getStringValue());
            assertEquals("PUBLIC", payload.get("visibility").getStringValue());
        }

        @Test
        @DisplayName("Rejects null VectorRecord with NullPointerException")
        void rejectsNullRecord() {
            assertThrows(NullPointerException.class, () -> repository.upsert(null));
            verify(mockClient, never()).upsertAsync(anyString(), any());
        }

        @Test
        @DisplayName("Rejects record when vector dimension does not match configured dimension before calling Qdrant")
        void rejectsVectorDimensionMismatch() {
            QdrantConfig mismatchConfig = new QdrantConfig("localhost", 6334, false, "", COLLECTION_NAME, 512);
            QdrantVectorRepository mismatchRepo = new QdrantVectorRepository(mockClient, mismatchConfig, cacheMetrics);

            IllegalArgumentException ex = assertThrows(
                    IllegalArgumentException.class,
                    () -> mismatchRepo.upsert(sampleRecord)
            );

            assertTrue(ex.getMessage().contains("Vector dimension [768]"));
            assertTrue(ex.getMessage().contains("configured dimension [512]"));
            verify(mockClient, never()).upsertAsync(anyString(), any());
        }

        @Test
        @DisplayName("Wraps Qdrant SDK execution failure in QdrantOperationException")
        void wrapsSdkExecutionFailure() {
            RuntimeException rootCause = new RuntimeException("Qdrant gRPC transport error");
            when(mockClient.upsertAsync(eq(COLLECTION_NAME), any()))
                    .thenReturn(Futures.immediateFailedFuture(rootCause));

            QdrantOperationException ex = assertThrows(
                    QdrantOperationException.class,
                    () -> repository.upsert(sampleRecord)
            );

            assertEquals("Failed to upsert vector record in Qdrant", ex.getMessage());
            assertSame(rootCause, ex.getCause());
        }

        @Test
        @DisplayName("Thread interruption restores interrupted status, cancels future, and throws QdrantOperationException")
        void handlesThreadInterruption() throws Exception {
            @SuppressWarnings("unchecked")
            ListenableFuture<Points.UpdateResult> mockFuture = mock(ListenableFuture.class);
            when(mockFuture.get(anyLong(), any(TimeUnit.class))).thenThrow(new InterruptedException("Interrupted"));
            when(mockClient.upsertAsync(eq(COLLECTION_NAME), any())).thenReturn(mockFuture);

            Thread.interrupted();

            try {
                QdrantOperationException ex = assertThrows(
                        QdrantOperationException.class,
                        () -> repository.upsert(sampleRecord)
                );

                verify(mockFuture).cancel(true);
                assertTrue(Thread.currentThread().isInterrupted(), "Interrupted status must be restored");
                assertEquals("Failed to upsert vector record due to thread interruption", ex.getMessage());
            } finally {
                Thread.interrupted();
            }
        }

        @Test
        @DisplayName("Timeout during upsert cancels future and throws QdrantOperationException")
        void handlesUpsertTimeout() throws Exception {
            @SuppressWarnings("unchecked")
            ListenableFuture<Points.UpdateResult> mockFuture = mock(ListenableFuture.class);
            when(mockFuture.get(anyLong(), any(TimeUnit.class))).thenThrow(new TimeoutException("Upsert timed out"));
            when(mockClient.upsertAsync(eq(COLLECTION_NAME), any())).thenReturn(mockFuture);

            QdrantOperationException ex = assertThrows(
                    QdrantOperationException.class,
                    () -> repository.upsert(sampleRecord)
            );

            verify(mockFuture).cancel(true);
            assertEquals("Failed to upsert vector record in Qdrant due to operation timeout", ex.getMessage());
            assertNotNull(ex.getCause());
            assertTrue(ex.getCause() instanceof TimeoutException);
        }

        @Test
        @DisplayName("Cancellation failure during upsert timeout does not swallow TimeoutException")
        void handlesUpsertTimeoutCancellationFailure() throws Exception {
            @SuppressWarnings("unchecked")
            ListenableFuture<Points.UpdateResult> mockFuture = mock(ListenableFuture.class);
            when(mockFuture.get(anyLong(), any(TimeUnit.class))).thenThrow(new TimeoutException("Upsert timed out"));
            when(mockFuture.cancel(true)).thenThrow(new RuntimeException("Cancellation failed"));
            when(mockClient.upsertAsync(eq(COLLECTION_NAME), any())).thenReturn(mockFuture);

            QdrantOperationException ex = assertThrows(
                    QdrantOperationException.class,
                    () -> repository.upsert(sampleRecord)
            );

            verify(mockFuture).cancel(true);
            assertEquals("Failed to upsert vector record in Qdrant due to operation timeout", ex.getMessage());
            assertTrue(ex.getCause() instanceof TimeoutException);
        }
    }

    @Nested
    @DisplayName("FindById Operations")
    class FindByIdTests {

        @Test
        @DisplayName("Successfully retrieves and reconstructs VectorRecord by UUID")
        void successfulFindById() {
            Points.RetrievedPoint point = buildRetrievedPoint(sampleId, sampleVector, buildSamplePayloadMap());
            when(mockClient.retrieveAsync(eq(COLLECTION_NAME), any(Common.PointId.class), anyBoolean(), anyBoolean(), isNull()))
                    .thenReturn(Futures.immediateFuture(List.of(point)));

            Optional<VectorRecord> result = repository.findById(sampleId);

            assertTrue(result.isPresent());
            VectorRecord retrieved = result.get();
            assertEquals(sampleId, retrieved.id());
            assertEquals(sampleVector, retrieved.vector());
            assertEquals("What is semantic caching?", retrieved.prompt());
            assertEquals("Semantic caching stores embeddings to find similar queries.", retrieved.answer());
            assertEquals("gemini-3.8-flash", retrieved.model());
            assertEquals(sampleCreatedAt, retrieved.createdAt());
            assertEquals("gemini-embedding-001", retrieved.embeddingModel());
        }

        @Test
        @DisplayName("Returns Optional.empty() when point ID does not exist in Qdrant")
        void returnsEmptyWhenNotFound() {
            when(mockClient.retrieveAsync(eq(COLLECTION_NAME), any(Common.PointId.class), anyBoolean(), anyBoolean(), isNull()))
                    .thenReturn(Futures.immediateFuture(Collections.emptyList()));

            Optional<VectorRecord> result = repository.findById(sampleId);

            assertFalse(result.isPresent());
        }

        @Test
        @DisplayName("Rejects null UUID with NullPointerException")
        void rejectsNullId() {
            assertThrows(NullPointerException.class, () -> repository.findById(null));
        }

        @Test
        @DisplayName("Wraps Qdrant SDK execution failure during retrieval in QdrantOperationException")
        void wrapsSdkRetrievalFailure() {
            RuntimeException rootCause = new RuntimeException("Qdrant retrieval error");
            when(mockClient.retrieveAsync(eq(COLLECTION_NAME), any(Common.PointId.class), anyBoolean(), anyBoolean(), isNull()))
                    .thenReturn(Futures.immediateFailedFuture(rootCause));

            QdrantOperationException ex = assertThrows(
                    QdrantOperationException.class,
                    () -> repository.findById(sampleId)
            );

            assertEquals("Failed to retrieve vector record from Qdrant", ex.getMessage());
            assertSame(rootCause, ex.getCause());
        }

        @Test
        @DisplayName("Thread interruption during retrieval restores interrupted status, cancels future, and throws QdrantOperationException")
        void handlesRetrievalInterruption() throws Exception {
            @SuppressWarnings("unchecked")
            ListenableFuture<List<Points.RetrievedPoint>> mockFuture = mock(ListenableFuture.class);
            when(mockFuture.get(anyLong(), any(TimeUnit.class))).thenThrow(new InterruptedException("Interrupted"));
            when(mockClient.retrieveAsync(eq(COLLECTION_NAME), any(Common.PointId.class), anyBoolean(), anyBoolean(), isNull()))
                    .thenReturn(mockFuture);

            Thread.interrupted();

            try {
                QdrantOperationException ex = assertThrows(
                        QdrantOperationException.class,
                        () -> repository.findById(sampleId)
                );

                verify(mockFuture).cancel(true);
                assertTrue(Thread.currentThread().isInterrupted(), "Interrupted status must be restored");
                assertEquals("Failed to retrieve vector record due to thread interruption", ex.getMessage());
            } finally {
                Thread.interrupted();
            }
        }

        @Test
        @DisplayName("Timeout during retrieval cancels future and throws QdrantOperationException")
        void handlesFindByIdTimeout() throws Exception {
            @SuppressWarnings("unchecked")
            ListenableFuture<List<Points.RetrievedPoint>> mockFuture = mock(ListenableFuture.class);
            when(mockFuture.get(anyLong(), any(TimeUnit.class))).thenThrow(new TimeoutException("Retrieve timed out"));
            when(mockClient.retrieveAsync(eq(COLLECTION_NAME), any(Common.PointId.class), anyBoolean(), anyBoolean(), isNull()))
                    .thenReturn(mockFuture);

            QdrantOperationException ex = assertThrows(
                    QdrantOperationException.class,
                    () -> repository.findById(sampleId)
            );

            verify(mockFuture).cancel(true);
            assertEquals("Failed to retrieve vector record from Qdrant due to operation timeout", ex.getMessage());
            assertNotNull(ex.getCause());
            assertTrue(ex.getCause() instanceof TimeoutException);
        }

        @Test
        @DisplayName("Cancellation failure during retrieval timeout does not swallow TimeoutException")
        void handlesFindByIdTimeoutCancellationFailure() throws Exception {
            @SuppressWarnings("unchecked")
            ListenableFuture<List<Points.RetrievedPoint>> mockFuture = mock(ListenableFuture.class);
            when(mockFuture.get(anyLong(), any(TimeUnit.class))).thenThrow(new TimeoutException("Retrieve timed out"));
            when(mockFuture.cancel(true)).thenThrow(new RuntimeException("Cancellation failed"));
            when(mockClient.retrieveAsync(eq(COLLECTION_NAME), any(Common.PointId.class), anyBoolean(), anyBoolean(), isNull()))
                    .thenReturn(mockFuture);

            QdrantOperationException ex = assertThrows(
                    QdrantOperationException.class,
                    () -> repository.findById(sampleId)
            );

            verify(mockFuture).cancel(true);
            assertEquals("Failed to retrieve vector record from Qdrant due to operation timeout", ex.getMessage());
            assertTrue(ex.getCause() instanceof TimeoutException);
        }
    }

    @Nested
    @DisplayName("Similarity Search Operations")
    class SearchTests {

        @Test
        @DisplayName("Successfully searches collection and returns ranked SearchResult records")
        void successfulSearch() {
            UUID id1 = UUID.randomUUID();
            UUID id2 = UUID.randomUUID();

            Map<String, String> payload1 = buildSamplePayloadMap();
            payload1.put("prompt", "Prompt 1");
            Points.ScoredPoint point1 = buildScoredPoint(id1, 0.95f, payload1);

            Map<String, String> payload2 = buildSamplePayloadMap();
            payload2.put("prompt", "Prompt 2");
            Points.ScoredPoint point2 = buildScoredPoint(id2, 0.82f, payload2);

            when(mockClient.searchAsync(any(Points.SearchPoints.class)))
                    .thenReturn(Futures.immediateFuture(List.of(point1, point2)));

            List<SearchResult> results = repository.search(sampleVector, 5);

            ArgumentCaptor<Points.SearchPoints> captor = ArgumentCaptor.forClass(Points.SearchPoints.class);
            verify(mockClient).searchAsync(captor.capture());

            Points.SearchPoints searchPoints = captor.getValue();
            assertEquals(COLLECTION_NAME, searchPoints.getCollectionName());
            assertEquals(5, searchPoints.getLimit());
            assertEquals(VECTOR_SIZE, searchPoints.getVectorCount());
            assertTrue(searchPoints.getWithPayload().getEnable());
            assertFalse(searchPoints.getWithVectors().getEnable());

            assertEquals(2, results.size());
            assertEquals(id1, results.get(0).id());
            assertEquals(0.95f, results.get(0).score(), 1e-6);
            assertEquals("Prompt 1", results.get(0).prompt());

            assertEquals(id2, results.get(1).id());
            assertEquals(0.82f, results.get(1).score(), 1e-6);
            assertEquals("Prompt 2", results.get(1).prompt());
        }

        @Test
        @DisplayName("Returns empty list when Qdrant returns no search results")
        void returnsEmptyWhenNoMatches() {
            when(mockClient.searchAsync(any(Points.SearchPoints.class)))
                    .thenReturn(Futures.immediateFuture(Collections.emptyList()));

            List<SearchResult> results = repository.search(sampleVector, 5);

            assertNotNull(results);
            assertTrue(results.isEmpty());
        }

        @Test
        @DisplayName("Rejects null query vector before calling Qdrant")
        void rejectsNullQueryVector() {
            IllegalArgumentException ex = assertThrows(
                    IllegalArgumentException.class,
                    () -> repository.search(null, 5)
            );
            assertTrue(ex.getMessage().contains("queryVector must not be null or empty"));
            verify(mockClient, never()).searchAsync(any(Points.SearchPoints.class));
        }

        @Test
        @DisplayName("Rejects empty query vector before calling Qdrant")
        void rejectsEmptyQueryVector() {
            IllegalArgumentException ex = assertThrows(
                    IllegalArgumentException.class,
                    () -> repository.search(Collections.emptyList(), 5)
            );
            assertTrue(ex.getMessage().contains("queryVector must not be null or empty"));
            verify(mockClient, never()).searchAsync(any(Points.SearchPoints.class));
        }

        @Test
        @DisplayName("Rejects query vector with null element before calling Qdrant")
        void rejectsNullVectorElement() {
            List<Float> vectorWithNull = new ArrayList<>(sampleVector);
            vectorWithNull.set(10, null);

            IllegalArgumentException ex = assertThrows(
                    IllegalArgumentException.class,
                    () -> repository.search(vectorWithNull, 5)
            );
            assertTrue(ex.getMessage().contains("queryVector elements must not be null"));
            verify(mockClient, never()).searchAsync(any(Points.SearchPoints.class));
        }

        @Test
        @DisplayName("Rejects query vector with non-finite elements (NaN, Inf) before calling Qdrant")
        void rejectsNonFiniteVectorElements() {
            List<Float> vectorWithNaN = new ArrayList<>(sampleVector);
            vectorWithNaN.set(5, Float.NaN);
            assertThrows(IllegalArgumentException.class, () -> repository.search(vectorWithNaN, 5));

            List<Float> vectorWithPosInf = new ArrayList<>(sampleVector);
            vectorWithPosInf.set(5, Float.POSITIVE_INFINITY);
            assertThrows(IllegalArgumentException.class, () -> repository.search(vectorWithPosInf, 5));

            List<Float> vectorWithNegInf = new ArrayList<>(sampleVector);
            vectorWithNegInf.set(5, Float.NEGATIVE_INFINITY);
            assertThrows(IllegalArgumentException.class, () -> repository.search(vectorWithNegInf, 5));

            verify(mockClient, never()).searchAsync(any(Points.SearchPoints.class));
        }

        @Test
        @DisplayName("Rejects query vector dimension mismatch before calling Qdrant")
        void rejectsQueryVectorDimensionMismatch() {
            List<Float> shortVector = new ArrayList<>(Collections.nCopies(512, 0.05f));

            IllegalArgumentException ex = assertThrows(
                    IllegalArgumentException.class,
                    () -> repository.search(shortVector, 5)
            );
            assertTrue(ex.getMessage().contains("Query vector dimension [512]"));
            assertTrue(ex.getMessage().contains("configured dimension [768]"));
            verify(mockClient, never()).searchAsync(any(Points.SearchPoints.class));
        }

        @Test
        @DisplayName("Rejects zero and negative limits before calling Qdrant")
        void rejectsInvalidLimits() {
            assertThrows(IllegalArgumentException.class, () -> repository.search(sampleVector, 0));
            assertThrows(IllegalArgumentException.class, () -> repository.search(sampleVector, -5));
            verify(mockClient, never()).searchAsync(any(Points.SearchPoints.class));
        }

        @Test
        @DisplayName("Wraps Qdrant SDK search execution failure in QdrantOperationException")
        void wrapsSdkSearchFailure() {
            RuntimeException rootCause = new RuntimeException("Qdrant search error");
            when(mockClient.searchAsync(any(Points.SearchPoints.class)))
                    .thenReturn(Futures.immediateFailedFuture(rootCause));

            QdrantOperationException ex = assertThrows(
                    QdrantOperationException.class,
                    () -> repository.search(sampleVector, 5)
            );
            assertEquals("Failed to search vector records in Qdrant", ex.getMessage());
            assertSame(rootCause, ex.getCause());
        }

        @Test
        @DisplayName("Thread interruption during search restores interrupted status, cancels future, and throws QdrantOperationException")
        void handlesSearchInterruption() throws Exception {
            @SuppressWarnings("unchecked")
            ListenableFuture<List<Points.ScoredPoint>> mockFuture = mock(ListenableFuture.class);
            when(mockFuture.get(anyLong(), any(TimeUnit.class))).thenThrow(new InterruptedException("Search interrupted"));
            when(mockClient.searchAsync(any(Points.SearchPoints.class))).thenReturn(mockFuture);

            Thread.interrupted();

            try {
                QdrantOperationException ex = assertThrows(
                        QdrantOperationException.class,
                        () -> repository.search(sampleVector, 5)
                );

                verify(mockFuture).cancel(true);
                assertTrue(Thread.currentThread().isInterrupted(), "Interrupted status must be restored");
                assertEquals("Failed to search vector records due to thread interruption", ex.getMessage());
            } finally {
                Thread.interrupted();
            }
        }

        @Test
        @DisplayName("Timeout during search cancels future and throws QdrantOperationException")
        void handlesSearchTimeout() throws Exception {
            @SuppressWarnings("unchecked")
            ListenableFuture<List<Points.ScoredPoint>> mockFuture = mock(ListenableFuture.class);
            when(mockFuture.get(anyLong(), any(TimeUnit.class))).thenThrow(new TimeoutException("Search timed out"));
            when(mockClient.searchAsync(any(Points.SearchPoints.class))).thenReturn(mockFuture);

            QdrantOperationException ex = assertThrows(
                    QdrantOperationException.class,
                    () -> repository.search(sampleVector, 5)
            );

            verify(mockFuture).cancel(true);
            assertEquals("Failed to search vector records in Qdrant due to operation timeout", ex.getMessage());
            assertNotNull(ex.getCause());
            assertTrue(ex.getCause() instanceof TimeoutException);
        }

        @Test
        @DisplayName("Cancellation failure during search timeout does not swallow TimeoutException")
        void handlesSearchTimeoutCancellationFailure() throws Exception {
            @SuppressWarnings("unchecked")
            ListenableFuture<List<Points.ScoredPoint>> mockFuture = mock(ListenableFuture.class);
            when(mockFuture.get(anyLong(), any(TimeUnit.class))).thenThrow(new TimeoutException("Search timed out"));
            when(mockFuture.cancel(true)).thenThrow(new RuntimeException("Cancellation failed"));
            when(mockClient.searchAsync(any(Points.SearchPoints.class))).thenReturn(mockFuture);

            QdrantOperationException ex = assertThrows(
                    QdrantOperationException.class,
                    () -> repository.search(sampleVector, 5)
            );

            verify(mockFuture).cancel(true);
            assertEquals("Failed to search vector records in Qdrant due to operation timeout", ex.getMessage());
            assertTrue(ex.getCause() instanceof TimeoutException);
        }

        @Test
        @DisplayName("Throws QdrantOperationException when scored point is missing ID")
        void throwsOnScoredPointMissingId() {
            Points.ScoredPoint point = buildScoredPoint(null, 0.9f, buildSamplePayloadMap());
            when(mockClient.searchAsync(any(Points.SearchPoints.class)))
                    .thenReturn(Futures.immediateFuture(List.of(point)));

            QdrantOperationException ex = assertThrows(
                    QdrantOperationException.class,
                    () -> repository.search(sampleVector, 5)
            );
            assertTrue(ex.getMessage().contains("missing a valid UUID point ID"));
        }

        @Test
        @DisplayName("Throws QdrantOperationException when scored point is missing mandatory payload field")
        void throwsOnScoredPointMissingPayloadField() {
            Map<String, String> payload = buildSamplePayloadMap();
            payload.remove("answer");
            Points.ScoredPoint point = buildScoredPoint(UUID.randomUUID(), 0.9f, payload);
            when(mockClient.searchAsync(any(Points.SearchPoints.class)))
                    .thenReturn(Futures.immediateFuture(List.of(point)));

            QdrantOperationException ex = assertThrows(
                    QdrantOperationException.class,
                    () -> repository.search(sampleVector, 5)
            );
            assertTrue(ex.getMessage().contains("Missing or invalid payload field [answer]"));
        }

        @Test
        @DisplayName("Throws QdrantOperationException when scored point has malformed createdAt timestamp")
        void throwsOnScoredPointMalformedTimestamp() {
            Map<String, String> payload = buildSamplePayloadMap();
            payload.put("createdAt", "invalid-ts");
            Points.ScoredPoint point = buildScoredPoint(UUID.randomUUID(), 0.9f, payload);
            when(mockClient.searchAsync(any(Points.SearchPoints.class)))
                    .thenReturn(Futures.immediateFuture(List.of(point)));

            QdrantOperationException ex = assertThrows(
                    QdrantOperationException.class,
                    () -> repository.search(sampleVector, 5)
            );
            assertEquals("Invalid createdAt timestamp in stored Qdrant payload", ex.getMessage());
        }
    }

    @Nested
    @DisplayName("Malformed Payload and Vector Handling")
    class MalformedDataTests {

        @Test
        @DisplayName("Throws QdrantOperationException when retrieved point is missing point ID")
        void throwsOnMissingPointId() {
            Points.RetrievedPoint point = buildRetrievedPoint(null, sampleVector, buildSamplePayloadMap());
            when(mockClient.retrieveAsync(eq(COLLECTION_NAME), any(Common.PointId.class), anyBoolean(), anyBoolean(), isNull()))
                    .thenReturn(Futures.immediateFuture(List.of(point)));

            QdrantOperationException ex = assertThrows(
                    QdrantOperationException.class,
                    () -> repository.findById(sampleId)
            );

            assertTrue(ex.getMessage().contains("missing a valid UUID point ID"));
        }

        @Test
        @DisplayName("Throws QdrantOperationException when retrieved point ID does not match requested UUID")
        void throwsOnMismatchedId() {
            UUID differentId = UUID.randomUUID();
            Points.RetrievedPoint point = buildRetrievedPoint(differentId, sampleVector, buildSamplePayloadMap());
            when(mockClient.retrieveAsync(eq(COLLECTION_NAME), any(Common.PointId.class), anyBoolean(), anyBoolean(), isNull()))
                    .thenReturn(Futures.immediateFuture(List.of(point)));

            QdrantOperationException ex = assertThrows(
                    QdrantOperationException.class,
                    () -> repository.findById(sampleId)
            );

            assertTrue(ex.getMessage().contains("does not match requested ID"));
        }

        @Test
        @DisplayName("Throws QdrantOperationException when retrieved point is missing vectors")
        void throwsOnMissingVectors() {
            Points.RetrievedPoint point = buildRetrievedPoint(sampleId, null, buildSamplePayloadMap());
            when(mockClient.retrieveAsync(eq(COLLECTION_NAME), any(Common.PointId.class), anyBoolean(), anyBoolean(), isNull()))
                    .thenReturn(Futures.immediateFuture(List.of(point)));

            QdrantOperationException ex = assertThrows(
                    QdrantOperationException.class,
                    () -> repository.findById(sampleId)
            );

            assertTrue(ex.getMessage().contains("missing vectors"));
        }

        @Test
        @DisplayName("Throws QdrantOperationException when retrieved vector dimension does not match configured dimension")
        void throwsOnVectorDimensionMismatch() {
            List<Float> shortVector = new ArrayList<>(Collections.nCopies(512, 0.1f));
            Points.RetrievedPoint point = buildRetrievedPoint(sampleId, shortVector, buildSamplePayloadMap());
            when(mockClient.retrieveAsync(eq(COLLECTION_NAME), any(Common.PointId.class), anyBoolean(), anyBoolean(), isNull()))
                    .thenReturn(Futures.immediateFuture(List.of(point)));

            QdrantOperationException ex = assertThrows(
                    QdrantOperationException.class,
                    () -> repository.findById(sampleId)
            );

            assertTrue(ex.getMessage().contains("vector dimension [512]"));
            assertTrue(ex.getMessage().contains("configured dimension [768]"));
        }

        @Test
        @DisplayName("Throws QdrantOperationException when required payload field is missing")
        void throwsOnMissingPayloadField() {
            Map<String, String> payload = buildSamplePayloadMap();
            payload.remove("prompt");

            Points.RetrievedPoint point = buildRetrievedPoint(sampleId, sampleVector, payload);
            when(mockClient.retrieveAsync(eq(COLLECTION_NAME), any(Common.PointId.class), anyBoolean(), anyBoolean(), isNull()))
                    .thenReturn(Futures.immediateFuture(List.of(point)));

            QdrantOperationException ex = assertThrows(
                    QdrantOperationException.class,
                    () -> repository.findById(sampleId)
            );

            assertTrue(ex.getMessage().contains("Missing or invalid payload field [prompt]"));
        }

        @Test
        @DisplayName("Throws QdrantOperationException when payload field has blank string value")
        void throwsOnBlankPayloadField() {
            Map<String, String> payload = buildSamplePayloadMap();
            payload.put("answer", "   ");

            Points.RetrievedPoint point = buildRetrievedPoint(sampleId, sampleVector, payload);
            when(mockClient.retrieveAsync(eq(COLLECTION_NAME), any(Common.PointId.class), anyBoolean(), anyBoolean(), isNull()))
                    .thenReturn(Futures.immediateFuture(List.of(point)));

            QdrantOperationException ex = assertThrows(
                    QdrantOperationException.class,
                    () -> repository.findById(sampleId)
            );

            assertTrue(ex.getMessage().contains("Missing or invalid payload field [answer]"));
        }

        @Test
        @DisplayName("Throws sanitized QdrantOperationException when createdAt timestamp is invalid ISO-8601 string")
        void throwsOnMalformedCreatedAtTimestamp() {
            String malformedTimestamp = "not-a-valid-timestamp-value";
            Map<String, String> payload = buildSamplePayloadMap();
            payload.put("createdAt", malformedTimestamp);

            Points.RetrievedPoint point = buildRetrievedPoint(sampleId, sampleVector, payload);
            when(mockClient.retrieveAsync(eq(COLLECTION_NAME), any(Common.PointId.class), anyBoolean(), anyBoolean(), isNull()))
                    .thenReturn(Futures.immediateFuture(List.of(point)));

            QdrantOperationException ex = assertThrows(
                    QdrantOperationException.class,
                    () -> repository.findById(sampleId)
            );

            assertEquals("Invalid createdAt timestamp in stored Qdrant payload", ex.getMessage());
            assertFalse(ex.getMessage().contains(malformedTimestamp),
                    "Exception message must not expose the raw stored timestamp value");
            assertNotNull(ex.getCause(), "Original parsing exception must remain available as the cause");
            assertTrue(ex.getCause() instanceof java.time.format.DateTimeParseException,
                    "Cause must be a DateTimeParseException");
        }
    }

    @Nested
    @DisplayName("Filtered Similarity Search Tests")
    class FilteredTests {

        @Test
        @DisplayName("Search with SearchFilter.publicOnly() sets Qdrant filter for visibility == PUBLIC")
        void searchWithPublicOnlyFilter() {
            when(mockClient.searchAsync(any(Points.SearchPoints.class)))
                    .thenReturn(Futures.immediateFuture(Collections.emptyList()));

            repository.search(sampleVector, 5, SearchFilter.publicOnly());

            ArgumentCaptor<Points.SearchPoints> captor = ArgumentCaptor.forClass(Points.SearchPoints.class);
            verify(mockClient).searchAsync(captor.capture());

            Points.SearchPoints searchPoints = captor.getValue();
            assertTrue(searchPoints.hasFilter());
            Common.Filter filter = searchPoints.getFilter();
            assertEquals(1, filter.getMustCount());
            Common.Condition cond = filter.getMust(0);
            assertEquals("visibility", cond.getField().getKey());
            assertEquals("PUBLIC", cond.getField().getMatch().getKeyword());
        }

        @Test
        @DisplayName("Search with SearchFilter.forOwner() sets Qdrant filter for ownerId == verifiedId OR visibility == PUBLIC")
        void searchWithOwnerFilter() {
            when(mockClient.searchAsync(any(Points.SearchPoints.class)))
                    .thenReturn(Futures.immediateFuture(Collections.emptyList()));

            repository.search(sampleVector, 5, SearchFilter.forOwner("userA"));

            ArgumentCaptor<Points.SearchPoints> captor = ArgumentCaptor.forClass(Points.SearchPoints.class);
            verify(mockClient).searchAsync(captor.capture());

            Points.SearchPoints searchPoints = captor.getValue();
            assertTrue(searchPoints.hasFilter());
            Common.Filter filter = searchPoints.getFilter();
            assertEquals(2, filter.getShouldCount());

            Common.Condition cond1 = filter.getShould(0);
            assertEquals("ownerId", cond1.getField().getKey());
            assertEquals("userA", cond1.getField().getMatch().getKeyword());

            Common.Condition cond2 = filter.getShould(1);
            assertEquals("visibility", cond2.getField().getKey());
            assertEquals("PUBLIC", cond2.getField().getMatch().getKeyword());
        }

        @Test
        @DisplayName("Unfiltered search overload does not set Qdrant filter")
        void searchUnfilteredOverload() {
            when(mockClient.searchAsync(any(Points.SearchPoints.class)))
                    .thenReturn(Futures.immediateFuture(Collections.emptyList()));

            repository.search(sampleVector, 5);

            ArgumentCaptor<Points.SearchPoints> captor = ArgumentCaptor.forClass(Points.SearchPoints.class);
            verify(mockClient).searchAsync(captor.capture());

            Points.SearchPoints searchPoints = captor.getValue();
            assertFalse(searchPoints.hasFilter());
        }

        @Test
        @DisplayName("Successfully maps ownerId and visibility from payload to SearchResult")
        void mapsOwnerAndVisibilityPayload() {
            UUID id = UUID.randomUUID();
            Map<String, String> payload = buildSamplePayloadMap();
            payload.put("ownerId", "owner-42");
            payload.put("visibility", "PUBLIC");
            Points.ScoredPoint point = buildScoredPoint(id, 0.91f, payload);

            when(mockClient.searchAsync(any(Points.SearchPoints.class)))
                    .thenReturn(Futures.immediateFuture(List.of(point)));

            List<SearchResult> results = repository.search(sampleVector, 5, SearchFilter.publicOnly());

            assertEquals(1, results.size());
            SearchResult result = results.get(0);
            assertEquals("owner-42", result.ownerId());
            assertEquals("PUBLIC", result.visibility());
        }

        @Test
        @DisplayName("Legacy payload without ownerId or visibility defaults to null owner and PRIVATE visibility")
        void mapsLegacyPayloadSafely() {
            UUID id = UUID.randomUUID();
            Map<String, String> payload = buildSamplePayloadMap();
            // No ownerId or visibility set
            Points.ScoredPoint point = buildScoredPoint(id, 0.88f, payload);

            when(mockClient.searchAsync(any(Points.SearchPoints.class)))
                    .thenReturn(Futures.immediateFuture(List.of(point)));

            List<SearchResult> results = repository.search(sampleVector, 5);

            assertEquals(1, results.size());
            SearchResult result = results.get(0);
            org.junit.jupiter.api.Assertions.assertNull(result.ownerId());
            assertEquals("PRIVATE", result.visibility());
        }

        @Test
        @DisplayName("Search with null filter throws NullPointerException before calling Qdrant")
        void searchWithNullFilterThrowsNullPointerException() {
            NullPointerException ex = assertThrows(
                    NullPointerException.class,
                    () -> repository.search(sampleVector, 5, null)
            );
            assertTrue(ex.getMessage().contains("filter must not be null"));
            verify(mockClient, never()).searchAsync(any(Points.SearchPoints.class));
        }

        @Test
        @DisplayName("buildQdrantFilter with null throws NullPointerException")
        void buildQdrantFilterWithNullThrowsNullPointerException() {
            NullPointerException ex = assertThrows(
                    NullPointerException.class,
                    () -> repository.buildQdrantFilter(null)
            );
            assertTrue(ex.getMessage().contains("filter must not be null"));
        }

        @Test
        @DisplayName("Search with unsupported filter state throws IllegalArgumentException and never calls Qdrant")
        void searchWithUnsupportedFilterThrowsIllegalArgumentException() throws Exception {
            Constructor<SearchFilter> constructor = SearchFilter.class.getDeclaredConstructor(String.class, boolean.class);
            constructor.setAccessible(true);
            SearchFilter invalidFilter = constructor.newInstance(null, false);

            IllegalArgumentException ex = assertThrows(
                    IllegalArgumentException.class,
                    () -> repository.search(sampleVector, 5, invalidFilter)
            );
            assertTrue(ex.getMessage().contains("Unsupported SearchFilter state"));
            verify(mockClient, never()).searchAsync(any(Points.SearchPoints.class));
        }

        @Test
        @DisplayName("buildQdrantFilter with unsupported filter state throws IllegalArgumentException")
        void buildQdrantFilterWithUnsupportedFilterThrowsIllegalArgumentException() throws Exception {
            Constructor<SearchFilter> constructor = SearchFilter.class.getDeclaredConstructor(String.class, boolean.class);
            constructor.setAccessible(true);
            SearchFilter invalidFilter = constructor.newInstance(null, false);

            IllegalArgumentException ex = assertThrows(
                    IllegalArgumentException.class,
                    () -> repository.buildQdrantFilter(invalidFilter)
            );
            assertTrue(ex.getMessage().contains("Unsupported SearchFilter state"));
        }
    }

    @Nested
    @DisplayName("Constructor and Validation Tests")
    class ConstructorValidationTests {

        @Test
        @DisplayName("Rejects null QdrantClient with NullPointerException")
        void rejectsNullQdrantClient() {
            NullPointerException ex = assertThrows(
                    NullPointerException.class,
                    () -> new QdrantVectorRepository(null, qdrantConfig, cacheMetrics)
            );
            assertEquals("qdrantClient must not be null", ex.getMessage());
        }

        @Test
        @DisplayName("Rejects null QdrantConfig with NullPointerException")
        void rejectsNullQdrantConfig() {
            NullPointerException ex = assertThrows(
                    NullPointerException.class,
                    () -> new QdrantVectorRepository(mockClient, null, cacheMetrics)
            );
            assertEquals("qdrantConfig must not be null", ex.getMessage());
        }

        @Test
        @DisplayName("Rejects null CacheMetrics with NullPointerException")
        void rejectsNullCacheMetrics() {
            NullPointerException ex = assertThrows(
                    NullPointerException.class,
                    () -> new QdrantVectorRepository(mockClient, qdrantConfig, null)
            );
            assertEquals("cacheMetrics must not be null", ex.getMessage());
        }
    }

    @Nested
    @DisplayName("Metrics and Telemetry Verification Tests")
    class TelemetryTests {

        // ==========================================
        // UPSERT Telemetry Tests
        // ==========================================

        @Test
        @DisplayName("Upsert success records exactly one UPSERT SUCCESS counter and timer")
        void upsertSuccessRecordsExpectedMetrics() throws Exception {
            Points.UpdateResult updateResult = Points.UpdateResult.newBuilder()
                    .setStatus(Points.UpdateStatus.Completed)
                    .build();
            when(mockClient.upsertAsync(eq(COLLECTION_NAME), any()))
                    .thenReturn(Futures.immediateFuture(updateResult));

            repository.upsert(sampleRecord);

            Counter successCounter = meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATIONS)
                    .tag(CacheMetrics.TAG_OPERATION, "upsert")
                    .tag(CacheMetrics.TAG_STATUS, "success")
                    .counter();
            assertNotNull(successCounter);
            assertEquals(1.0, successCounter.count());

            Timer successTimer = meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATION_DURATION)
                    .tag(CacheMetrics.TAG_OPERATION, "upsert")
                    .tag(CacheMetrics.TAG_STATUS, "success")
                    .timer();
            assertNotNull(successTimer);
            assertEquals(1, successTimer.count());

            assertNull(meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATIONS).tag(CacheMetrics.TAG_OPERATION, "upsert").tag(CacheMetrics.TAG_STATUS, "timeout").counter());
            assertNull(meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATIONS).tag(CacheMetrics.TAG_OPERATION, "upsert").tag(CacheMetrics.TAG_STATUS, "error").counter());
            assertNull(meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATION_DURATION).tag(CacheMetrics.TAG_OPERATION, "upsert").tag(CacheMetrics.TAG_STATUS, "timeout").timer());
            assertNull(meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATION_DURATION).tag(CacheMetrics.TAG_OPERATION, "upsert").tag(CacheMetrics.TAG_STATUS, "error").timer());
        }

        @Test
        @DisplayName("Upsert timeout records exactly one UPSERT TIMEOUT counter and timer and cancels future")
        void upsertTimeoutRecordsTimeoutMetric() throws Exception {
            @SuppressWarnings("unchecked")
            ListenableFuture<Points.UpdateResult> mockFuture = mock(ListenableFuture.class);
            when(mockFuture.get(anyLong(), any(TimeUnit.class))).thenThrow(new TimeoutException("Upsert timed out"));
            when(mockClient.upsertAsync(eq(COLLECTION_NAME), any())).thenReturn(mockFuture);

            assertThrows(QdrantOperationException.class, () -> repository.upsert(sampleRecord));

            verify(mockFuture).cancel(true);

            Counter timeoutCounter = meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATIONS)
                    .tag(CacheMetrics.TAG_OPERATION, "upsert")
                    .tag(CacheMetrics.TAG_STATUS, "timeout")
                    .counter();
            assertNotNull(timeoutCounter);
            assertEquals(1.0, timeoutCounter.count());

            Timer timeoutTimer = meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATION_DURATION)
                    .tag(CacheMetrics.TAG_OPERATION, "upsert")
                    .tag(CacheMetrics.TAG_STATUS, "timeout")
                    .timer();
            assertNotNull(timeoutTimer);
            assertEquals(1, timeoutTimer.count());

            assertNull(meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATIONS).tag(CacheMetrics.TAG_OPERATION, "upsert").tag(CacheMetrics.TAG_STATUS, "success").counter());
            assertNull(meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATIONS).tag(CacheMetrics.TAG_OPERATION, "upsert").tag(CacheMetrics.TAG_STATUS, "error").counter());
            assertNull(meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATION_DURATION).tag(CacheMetrics.TAG_OPERATION, "upsert").tag(CacheMetrics.TAG_STATUS, "success").timer());
            assertNull(meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATION_DURATION).tag(CacheMetrics.TAG_OPERATION, "upsert").tag(CacheMetrics.TAG_STATUS, "error").timer());
        }

        @Test
        @DisplayName("Upsert interruption records exactly one UPSERT ERROR counter and timer, cancels future and restores interrupt flag")
        void upsertInterruptionRecordsErrorMetricAndRestoresInterrupt() throws Exception {
            @SuppressWarnings("unchecked")
            ListenableFuture<Points.UpdateResult> mockFuture = mock(ListenableFuture.class);
            when(mockFuture.get(anyLong(), any(TimeUnit.class))).thenThrow(new InterruptedException("Upsert interrupted"));
            when(mockClient.upsertAsync(eq(COLLECTION_NAME), any())).thenReturn(mockFuture);

            try {
                assertThrows(QdrantOperationException.class, () -> repository.upsert(sampleRecord));
                assertTrue(Thread.currentThread().isInterrupted(), "Thread interrupt flag must be restored");
            } finally {
                Thread.interrupted();
            }

            verify(mockFuture).cancel(true);

            Counter errorCounter = meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATIONS)
                    .tag(CacheMetrics.TAG_OPERATION, "upsert")
                    .tag(CacheMetrics.TAG_STATUS, "error")
                    .counter();
            assertNotNull(errorCounter);
            assertEquals(1.0, errorCounter.count());

            Timer errorTimer = meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATION_DURATION)
                    .tag(CacheMetrics.TAG_OPERATION, "upsert")
                    .tag(CacheMetrics.TAG_STATUS, "error")
                    .timer();
            assertNotNull(errorTimer);
            assertEquals(1, errorTimer.count());

            assertNull(meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATIONS).tag(CacheMetrics.TAG_OPERATION, "upsert").tag(CacheMetrics.TAG_STATUS, "success").counter());
            assertNull(meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATIONS).tag(CacheMetrics.TAG_OPERATION, "upsert").tag(CacheMetrics.TAG_STATUS, "timeout").counter());
            assertNull(meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATION_DURATION).tag(CacheMetrics.TAG_OPERATION, "upsert").tag(CacheMetrics.TAG_STATUS, "success").timer());
            assertNull(meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATION_DURATION).tag(CacheMetrics.TAG_OPERATION, "upsert").tag(CacheMetrics.TAG_STATUS, "timeout").timer());
        }

        @Test
        @DisplayName("Upsert ExecutionException with gRPC DEADLINE_EXCEEDED records exactly one UPSERT TIMEOUT counter and timer")
        void upsertDeadlineExceededRecordsTimeoutMetric() throws Exception {
            @SuppressWarnings("unchecked")
            ListenableFuture<Points.UpdateResult> mockFuture = mock(ListenableFuture.class);
            when(mockFuture.get(anyLong(), any(TimeUnit.class)))
                    .thenThrow(new ExecutionException(new StatusRuntimeException(Status.DEADLINE_EXCEEDED)));
            when(mockClient.upsertAsync(eq(COLLECTION_NAME), any())).thenReturn(mockFuture);

            assertThrows(QdrantOperationException.class, () -> repository.upsert(sampleRecord));

            Counter timeoutCounter = meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATIONS)
                    .tag(CacheMetrics.TAG_OPERATION, "upsert")
                    .tag(CacheMetrics.TAG_STATUS, "timeout")
                    .counter();
            assertNotNull(timeoutCounter);
            assertEquals(1.0, timeoutCounter.count());

            Timer timeoutTimer = meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATION_DURATION)
                    .tag(CacheMetrics.TAG_OPERATION, "upsert")
                    .tag(CacheMetrics.TAG_STATUS, "timeout")
                    .timer();
            assertNotNull(timeoutTimer);
            assertEquals(1, timeoutTimer.count());

            assertNull(meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATIONS).tag(CacheMetrics.TAG_OPERATION, "upsert").tag(CacheMetrics.TAG_STATUS, "success").counter());
            assertNull(meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATIONS).tag(CacheMetrics.TAG_OPERATION, "upsert").tag(CacheMetrics.TAG_STATUS, "error").counter());
            assertNull(meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATION_DURATION).tag(CacheMetrics.TAG_OPERATION, "upsert").tag(CacheMetrics.TAG_STATUS, "success").timer());
            assertNull(meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATION_DURATION).tag(CacheMetrics.TAG_OPERATION, "upsert").tag(CacheMetrics.TAG_STATUS, "error").timer());
        }

        @Test
        @DisplayName("Upsert ExecutionException with generic cause records exactly one UPSERT ERROR counter and timer")
        void upsertGenericExecutionExceptionRecordsErrorMetric() throws Exception {
            @SuppressWarnings("unchecked")
            ListenableFuture<Points.UpdateResult> mockFuture = mock(ListenableFuture.class);
            when(mockFuture.get(anyLong(), any(TimeUnit.class)))
                    .thenThrow(new ExecutionException(new RuntimeException("gRPC transport failure")));
            when(mockClient.upsertAsync(eq(COLLECTION_NAME), any())).thenReturn(mockFuture);

            assertThrows(QdrantOperationException.class, () -> repository.upsert(sampleRecord));

            Counter errorCounter = meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATIONS)
                    .tag(CacheMetrics.TAG_OPERATION, "upsert")
                    .tag(CacheMetrics.TAG_STATUS, "error")
                    .counter();
            assertNotNull(errorCounter);
            assertEquals(1.0, errorCounter.count());

            Timer errorTimer = meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATION_DURATION)
                    .tag(CacheMetrics.TAG_OPERATION, "upsert")
                    .tag(CacheMetrics.TAG_STATUS, "error")
                    .timer();
            assertNotNull(errorTimer);
            assertEquals(1, errorTimer.count());

            assertNull(meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATIONS).tag(CacheMetrics.TAG_OPERATION, "upsert").tag(CacheMetrics.TAG_STATUS, "success").counter());
            assertNull(meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATIONS).tag(CacheMetrics.TAG_OPERATION, "upsert").tag(CacheMetrics.TAG_STATUS, "timeout").counter());
            assertNull(meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATION_DURATION).tag(CacheMetrics.TAG_OPERATION, "upsert").tag(CacheMetrics.TAG_STATUS, "success").timer());
            assertNull(meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATION_DURATION).tag(CacheMetrics.TAG_OPERATION, "upsert").tag(CacheMetrics.TAG_STATUS, "timeout").timer());
        }

        @Test
        @DisplayName("Upsert validation failure records zero metrics")
        void upsertValidationFailureRecordsZeroMetrics() {
            assertThrows(NullPointerException.class, () -> repository.upsert(null));

            VectorRecord badRecord = new VectorRecord(
                    sampleId,
                    new ArrayList<>(Collections.nCopies(256, 0.1f)),
                    "p", "a", "m", Instant.now(), "em"
            );
            assertThrows(IllegalArgumentException.class, () -> repository.upsert(badRecord));

            assertNull(meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATIONS).tag(CacheMetrics.TAG_OPERATION, "upsert").counter());
            assertNull(meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATION_DURATION).tag(CacheMetrics.TAG_OPERATION, "upsert").timer());
        }

        // ==========================================
        // RETRIEVE (findById) Telemetry Tests
        // ==========================================

        @Test
        @DisplayName("FindById success with matching point records exactly one RETRIEVE SUCCESS counter and timer")
        void findByIdSuccessWithPointRecordsMetric() throws Exception {
            Map<String, String> payload = Map.of(
                    "prompt", "What is cache?",
                    "answer", "Cache is fast storage.",
                    "model", "gemini-3.8-flash",
                    "createdAt", "2026-11-07T20:00:00Z",
                    "embeddingModel", "gemini-embedding-001"
            );
            Points.RetrievedPoint point = buildRetrievedPoint(sampleId, sampleVector, payload);
            when(mockClient.retrieveAsync(eq(COLLECTION_NAME), any(Common.PointId.class), eq(true), eq(true), isNull(Points.ReadConsistency.class)))
                    .thenReturn(Futures.immediateFuture(List.of(point)));

            Optional<VectorRecord> result = repository.findById(sampleId);
            assertTrue(result.isPresent());

            Counter successCounter = meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATIONS)
                    .tag(CacheMetrics.TAG_OPERATION, "retrieve")
                    .tag(CacheMetrics.TAG_STATUS, "success")
                    .counter();
            assertNotNull(successCounter);
            assertEquals(1.0, successCounter.count());

            Timer successTimer = meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATION_DURATION)
                    .tag(CacheMetrics.TAG_OPERATION, "retrieve")
                    .tag(CacheMetrics.TAG_STATUS, "success")
                    .timer();
            assertNotNull(successTimer);
            assertEquals(1, successTimer.count());

            assertNull(meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATIONS).tag(CacheMetrics.TAG_OPERATION, "retrieve").tag(CacheMetrics.TAG_STATUS, "timeout").counter());
            assertNull(meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATIONS).tag(CacheMetrics.TAG_OPERATION, "retrieve").tag(CacheMetrics.TAG_STATUS, "error").counter());
            assertNull(meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATION_DURATION).tag(CacheMetrics.TAG_OPERATION, "retrieve").tag(CacheMetrics.TAG_STATUS, "timeout").timer());
            assertNull(meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATION_DURATION).tag(CacheMetrics.TAG_OPERATION, "retrieve").tag(CacheMetrics.TAG_STATUS, "error").timer());
        }

        @Test
        @DisplayName("FindById success with empty list records exactly one RETRIEVE SUCCESS counter and timer")
        void findByIdSuccessEmptyRecordsMetric() throws Exception {
            when(mockClient.retrieveAsync(eq(COLLECTION_NAME), any(Common.PointId.class), eq(true), eq(true), isNull(Points.ReadConsistency.class)))
                    .thenReturn(Futures.immediateFuture(Collections.emptyList()));

            Optional<VectorRecord> result = repository.findById(sampleId);
            assertTrue(result.isEmpty());

            Counter successCounter = meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATIONS)
                    .tag(CacheMetrics.TAG_OPERATION, "retrieve")
                    .tag(CacheMetrics.TAG_STATUS, "success")
                    .counter();
            assertNotNull(successCounter);
            assertEquals(1.0, successCounter.count());

            Timer successTimer = meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATION_DURATION)
                    .tag(CacheMetrics.TAG_OPERATION, "retrieve")
                    .tag(CacheMetrics.TAG_STATUS, "success")
                    .timer();
            assertNotNull(successTimer);
            assertEquals(1, successTimer.count());

            assertNull(meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATIONS).tag(CacheMetrics.TAG_OPERATION, "retrieve").tag(CacheMetrics.TAG_STATUS, "timeout").counter());
            assertNull(meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATIONS).tag(CacheMetrics.TAG_OPERATION, "retrieve").tag(CacheMetrics.TAG_STATUS, "error").counter());
            assertNull(meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATION_DURATION).tag(CacheMetrics.TAG_OPERATION, "retrieve").tag(CacheMetrics.TAG_STATUS, "timeout").timer());
            assertNull(meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATION_DURATION).tag(CacheMetrics.TAG_OPERATION, "retrieve").tag(CacheMetrics.TAG_STATUS, "error").timer());
        }

        @Test
        @DisplayName("FindById timeout records exactly one RETRIEVE TIMEOUT counter and timer and cancels future")
        void findByIdTimeoutRecordsTimeoutMetric() throws Exception {
            @SuppressWarnings("unchecked")
            ListenableFuture<List<Points.RetrievedPoint>> mockFuture = mock(ListenableFuture.class);
            when(mockFuture.get(anyLong(), any(TimeUnit.class))).thenThrow(new TimeoutException("Retrieve timed out"));
            when(mockClient.retrieveAsync(eq(COLLECTION_NAME), any(Common.PointId.class), eq(true), eq(true), isNull(Points.ReadConsistency.class)))
                    .thenReturn(mockFuture);

            assertThrows(QdrantOperationException.class, () -> repository.findById(sampleId));

            verify(mockFuture).cancel(true);

            Counter timeoutCounter = meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATIONS)
                    .tag(CacheMetrics.TAG_OPERATION, "retrieve")
                    .tag(CacheMetrics.TAG_STATUS, "timeout")
                    .counter();
            assertNotNull(timeoutCounter);
            assertEquals(1.0, timeoutCounter.count());

            Timer timeoutTimer = meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATION_DURATION)
                    .tag(CacheMetrics.TAG_OPERATION, "retrieve")
                    .tag(CacheMetrics.TAG_STATUS, "timeout")
                    .timer();
            assertNotNull(timeoutTimer);
            assertEquals(1, timeoutTimer.count());

            assertNull(meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATIONS).tag(CacheMetrics.TAG_OPERATION, "retrieve").tag(CacheMetrics.TAG_STATUS, "success").counter());
            assertNull(meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATIONS).tag(CacheMetrics.TAG_OPERATION, "retrieve").tag(CacheMetrics.TAG_STATUS, "error").counter());
            assertNull(meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATION_DURATION).tag(CacheMetrics.TAG_OPERATION, "retrieve").tag(CacheMetrics.TAG_STATUS, "success").timer());
            assertNull(meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATION_DURATION).tag(CacheMetrics.TAG_OPERATION, "retrieve").tag(CacheMetrics.TAG_STATUS, "error").timer());
        }

        @Test
        @DisplayName("FindById interruption records exactly one RETRIEVE ERROR counter and timer and restores interrupt flag")
        void findByIdInterruptionRecordsErrorMetricAndRestoresInterrupt() throws Exception {
            @SuppressWarnings("unchecked")
            ListenableFuture<List<Points.RetrievedPoint>> mockFuture = mock(ListenableFuture.class);
            when(mockFuture.get(anyLong(), any(TimeUnit.class))).thenThrow(new InterruptedException("Retrieve interrupted"));
            when(mockClient.retrieveAsync(eq(COLLECTION_NAME), any(Common.PointId.class), eq(true), eq(true), isNull(Points.ReadConsistency.class)))
                    .thenReturn(mockFuture);

            try {
                assertThrows(QdrantOperationException.class, () -> repository.findById(sampleId));
                assertTrue(Thread.currentThread().isInterrupted(), "Thread interrupt flag must be restored");
            } finally {
                Thread.interrupted();
            }

            verify(mockFuture).cancel(true);

            Counter errorCounter = meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATIONS)
                    .tag(CacheMetrics.TAG_OPERATION, "retrieve")
                    .tag(CacheMetrics.TAG_STATUS, "error")
                    .counter();
            assertNotNull(errorCounter);
            assertEquals(1.0, errorCounter.count());

            Timer errorTimer = meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATION_DURATION)
                    .tag(CacheMetrics.TAG_OPERATION, "retrieve")
                    .tag(CacheMetrics.TAG_STATUS, "error")
                    .timer();
            assertNotNull(errorTimer);
            assertEquals(1, errorTimer.count());

            assertNull(meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATIONS).tag(CacheMetrics.TAG_OPERATION, "retrieve").tag(CacheMetrics.TAG_STATUS, "success").counter());
            assertNull(meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATIONS).tag(CacheMetrics.TAG_OPERATION, "retrieve").tag(CacheMetrics.TAG_STATUS, "timeout").counter());
            assertNull(meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATION_DURATION).tag(CacheMetrics.TAG_OPERATION, "retrieve").tag(CacheMetrics.TAG_STATUS, "success").timer());
            assertNull(meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATION_DURATION).tag(CacheMetrics.TAG_OPERATION, "retrieve").tag(CacheMetrics.TAG_STATUS, "timeout").timer());
        }

        @Test
        @DisplayName("FindById receiving generic ExecutionException throws QdrantOperationException and records exactly one RETRIEVE ERROR counter and timer")
        void findByIdGenericExecutionExceptionRecordsErrorMetric() throws Exception {
            @SuppressWarnings("unchecked")
            ListenableFuture<List<Points.RetrievedPoint>> mockFuture = mock(ListenableFuture.class);
            when(mockFuture.get(anyLong(), any(TimeUnit.class)))
                    .thenThrow(new ExecutionException(new RuntimeException("gRPC retrieve failure")));
            when(mockClient.retrieveAsync(eq(COLLECTION_NAME), any(Common.PointId.class), eq(true), eq(true), isNull(Points.ReadConsistency.class)))
                    .thenReturn(mockFuture);

            QdrantOperationException ex = assertThrows(
                    QdrantOperationException.class,
                    () -> repository.findById(sampleId)
            );
            assertEquals("Failed to retrieve vector record from Qdrant", ex.getMessage());

            Counter errorCounter = meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATIONS)
                    .tag(CacheMetrics.TAG_OPERATION, "retrieve")
                    .tag(CacheMetrics.TAG_STATUS, "error")
                    .counter();
            assertNotNull(errorCounter);
            assertEquals(1.0, errorCounter.count());

            Timer errorTimer = meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATION_DURATION)
                    .tag(CacheMetrics.TAG_OPERATION, "retrieve")
                    .tag(CacheMetrics.TAG_STATUS, "error")
                    .timer();
            assertNotNull(errorTimer);
            assertEquals(1, errorTimer.count());

            assertNull(meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATIONS).tag(CacheMetrics.TAG_OPERATION, "retrieve").tag(CacheMetrics.TAG_STATUS, "success").counter());
            assertNull(meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATIONS).tag(CacheMetrics.TAG_OPERATION, "retrieve").tag(CacheMetrics.TAG_STATUS, "timeout").counter());
            assertNull(meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATION_DURATION).tag(CacheMetrics.TAG_OPERATION, "retrieve").tag(CacheMetrics.TAG_STATUS, "success").timer());
            assertNull(meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATION_DURATION).tag(CacheMetrics.TAG_OPERATION, "retrieve").tag(CacheMetrics.TAG_STATUS, "timeout").timer());
        }

        @Test
        @DisplayName("FindById mapping failure records exactly one RETRIEVE ERROR counter and timer")
        void findByIdMappingFailureRecordsErrorMetric() throws Exception {
            Points.RetrievedPoint invalidPoint = Points.RetrievedPoint.newBuilder()
                    .setId(PointIdFactory.id(sampleId))
                    .build();
            when(mockClient.retrieveAsync(eq(COLLECTION_NAME), any(Common.PointId.class), eq(true), eq(true), isNull(Points.ReadConsistency.class)))
                    .thenReturn(Futures.immediateFuture(List.of(invalidPoint)));

            assertThrows(QdrantOperationException.class, () -> repository.findById(sampleId));

            Counter errorCounter = meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATIONS)
                    .tag(CacheMetrics.TAG_OPERATION, "retrieve")
                    .tag(CacheMetrics.TAG_STATUS, "error")
                    .counter();
            assertNotNull(errorCounter);
            assertEquals(1.0, errorCounter.count());

            Timer errorTimer = meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATION_DURATION)
                    .tag(CacheMetrics.TAG_OPERATION, "retrieve")
                    .tag(CacheMetrics.TAG_STATUS, "error")
                    .timer();
            assertNotNull(errorTimer);
            assertEquals(1, errorTimer.count());

            assertNull(meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATIONS).tag(CacheMetrics.TAG_OPERATION, "retrieve").tag(CacheMetrics.TAG_STATUS, "success").counter());
            assertNull(meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATIONS).tag(CacheMetrics.TAG_OPERATION, "retrieve").tag(CacheMetrics.TAG_STATUS, "timeout").counter());
        }

        @Test
        @DisplayName("FindById null ID records zero metrics")
        void findByIdNullIdRecordsZeroMetrics() {
            assertThrows(NullPointerException.class, () -> repository.findById(null));
            assertNull(meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATIONS).tag(CacheMetrics.TAG_OPERATION, "retrieve").counter());
            assertNull(meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATION_DURATION).tag(CacheMetrics.TAG_OPERATION, "retrieve").timer());
        }

        // ==========================================
        // SEARCH Telemetry Tests
        // ==========================================

        @Test
        @DisplayName("Search success with scored points records exactly one SEARCH SUCCESS counter and timer")
        void searchSuccessWithResultsRecordsMetric() throws Exception {
            Map<String, String> payload = Map.of(
                    "prompt", "prompt",
                    "answer", "answer",
                    "model", "model",
                    "createdAt", "2026-11-07T20:00:00Z",
                    "embeddingModel", "em"
            );
            Points.ScoredPoint point = buildScoredPoint(sampleId, 0.95f, payload);
            when(mockClient.searchAsync(any(Points.SearchPoints.class)))
                    .thenReturn(Futures.immediateFuture(List.of(point)));

            List<SearchResult> results = repository.search(sampleVector, 5);
            assertEquals(1, results.size());

            Counter successCounter = meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATIONS)
                    .tag(CacheMetrics.TAG_OPERATION, "search")
                    .tag(CacheMetrics.TAG_STATUS, "success")
                    .counter();
            assertNotNull(successCounter);
            assertEquals(1.0, successCounter.count());

            Timer successTimer = meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATION_DURATION)
                    .tag(CacheMetrics.TAG_OPERATION, "search")
                    .tag(CacheMetrics.TAG_STATUS, "success")
                    .timer();
            assertNotNull(successTimer);
            assertEquals(1, successTimer.count());

            assertNull(meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATIONS).tag(CacheMetrics.TAG_OPERATION, "search").tag(CacheMetrics.TAG_STATUS, "timeout").counter());
            assertNull(meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATIONS).tag(CacheMetrics.TAG_OPERATION, "search").tag(CacheMetrics.TAG_STATUS, "error").counter());
            assertNull(meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATION_DURATION).tag(CacheMetrics.TAG_OPERATION, "search").tag(CacheMetrics.TAG_STATUS, "timeout").timer());
            assertNull(meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATION_DURATION).tag(CacheMetrics.TAG_OPERATION, "search").tag(CacheMetrics.TAG_STATUS, "error").timer());
        }

        @Test
        @DisplayName("Search success with empty list records exactly one SEARCH SUCCESS counter and timer")
        void searchSuccessEmptyRecordsMetric() throws Exception {
            when(mockClient.searchAsync(any(Points.SearchPoints.class)))
                    .thenReturn(Futures.immediateFuture(Collections.emptyList()));

            List<SearchResult> results = repository.search(sampleVector, 5);
            assertTrue(results.isEmpty());

            Counter successCounter = meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATIONS)
                    .tag(CacheMetrics.TAG_OPERATION, "search")
                    .tag(CacheMetrics.TAG_STATUS, "success")
                    .counter();
            assertNotNull(successCounter);
            assertEquals(1.0, successCounter.count());

            Timer successTimer = meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATION_DURATION)
                    .tag(CacheMetrics.TAG_OPERATION, "search")
                    .tag(CacheMetrics.TAG_STATUS, "success")
                    .timer();
            assertNotNull(successTimer);
            assertEquals(1, successTimer.count());

            assertNull(meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATIONS).tag(CacheMetrics.TAG_OPERATION, "search").tag(CacheMetrics.TAG_STATUS, "timeout").counter());
            assertNull(meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATIONS).tag(CacheMetrics.TAG_OPERATION, "search").tag(CacheMetrics.TAG_STATUS, "error").counter());
            assertNull(meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATION_DURATION).tag(CacheMetrics.TAG_OPERATION, "search").tag(CacheMetrics.TAG_STATUS, "timeout").timer());
            assertNull(meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATION_DURATION).tag(CacheMetrics.TAG_OPERATION, "search").tag(CacheMetrics.TAG_STATUS, "error").timer());
        }

        @Test
        @DisplayName("Search timeout records exactly one SEARCH TIMEOUT counter and timer and cancels future")
        void searchTimeoutRecordsTimeoutMetric() throws Exception {
            @SuppressWarnings("unchecked")
            ListenableFuture<List<Points.ScoredPoint>> mockFuture = mock(ListenableFuture.class);
            when(mockFuture.get(anyLong(), any(TimeUnit.class))).thenThrow(new TimeoutException("Search timed out"));
            when(mockClient.searchAsync(any(Points.SearchPoints.class))).thenReturn(mockFuture);

            assertThrows(QdrantOperationException.class, () -> repository.search(sampleVector, 5));

            verify(mockFuture).cancel(true);

            Counter timeoutCounter = meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATIONS)
                    .tag(CacheMetrics.TAG_OPERATION, "search")
                    .tag(CacheMetrics.TAG_STATUS, "timeout")
                    .counter();
            assertNotNull(timeoutCounter);
            assertEquals(1.0, timeoutCounter.count());

            Timer timeoutTimer = meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATION_DURATION)
                    .tag(CacheMetrics.TAG_OPERATION, "search")
                    .tag(CacheMetrics.TAG_STATUS, "timeout")
                    .timer();
            assertNotNull(timeoutTimer);
            assertEquals(1, timeoutTimer.count());

            assertNull(meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATIONS).tag(CacheMetrics.TAG_OPERATION, "search").tag(CacheMetrics.TAG_STATUS, "success").counter());
            assertNull(meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATIONS).tag(CacheMetrics.TAG_OPERATION, "search").tag(CacheMetrics.TAG_STATUS, "error").counter());
            assertNull(meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATION_DURATION).tag(CacheMetrics.TAG_OPERATION, "search").tag(CacheMetrics.TAG_STATUS, "success").timer());
            assertNull(meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATION_DURATION).tag(CacheMetrics.TAG_OPERATION, "search").tag(CacheMetrics.TAG_STATUS, "error").timer());
        }

        @Test
        @DisplayName("Search interruption records exactly one SEARCH ERROR counter and timer and restores interrupt flag")
        void searchInterruptionRecordsErrorMetricAndRestoresInterrupt() throws Exception {
            @SuppressWarnings("unchecked")
            ListenableFuture<List<Points.ScoredPoint>> mockFuture = mock(ListenableFuture.class);
            when(mockFuture.get(anyLong(), any(TimeUnit.class))).thenThrow(new InterruptedException("Search interrupted"));
            when(mockClient.searchAsync(any(Points.SearchPoints.class))).thenReturn(mockFuture);

            try {
                assertThrows(QdrantOperationException.class, () -> repository.search(sampleVector, 5));
                assertTrue(Thread.currentThread().isInterrupted(), "Thread interrupt flag must be restored");
            } finally {
                Thread.interrupted();
            }

            verify(mockFuture).cancel(true);

            Counter errorCounter = meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATIONS)
                    .tag(CacheMetrics.TAG_OPERATION, "search")
                    .tag(CacheMetrics.TAG_STATUS, "error")
                    .counter();
            assertNotNull(errorCounter);
            assertEquals(1.0, errorCounter.count());

            Timer errorTimer = meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATION_DURATION)
                    .tag(CacheMetrics.TAG_OPERATION, "search")
                    .tag(CacheMetrics.TAG_STATUS, "error")
                    .timer();
            assertNotNull(errorTimer);
            assertEquals(1, errorTimer.count());

            assertNull(meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATIONS).tag(CacheMetrics.TAG_OPERATION, "search").tag(CacheMetrics.TAG_STATUS, "success").counter());
            assertNull(meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATIONS).tag(CacheMetrics.TAG_OPERATION, "search").tag(CacheMetrics.TAG_STATUS, "timeout").counter());
            assertNull(meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATION_DURATION).tag(CacheMetrics.TAG_OPERATION, "search").tag(CacheMetrics.TAG_STATUS, "success").timer());
            assertNull(meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATION_DURATION).tag(CacheMetrics.TAG_OPERATION, "search").tag(CacheMetrics.TAG_STATUS, "timeout").timer());
        }

        @Test
        @DisplayName("Search ExecutionException with DEADLINE_EXCEEDED records exactly one SEARCH TIMEOUT counter and timer")
        void searchDeadlineExceededRecordsTimeoutMetric() throws Exception {
            @SuppressWarnings("unchecked")
            ListenableFuture<List<Points.ScoredPoint>> mockFuture = mock(ListenableFuture.class);
            when(mockFuture.get(anyLong(), any(TimeUnit.class)))
                    .thenThrow(new ExecutionException(new StatusException(Status.DEADLINE_EXCEEDED)));
            when(mockClient.searchAsync(any(Points.SearchPoints.class))).thenReturn(mockFuture);

            assertThrows(QdrantOperationException.class, () -> repository.search(sampleVector, 5));

            Counter timeoutCounter = meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATIONS)
                    .tag(CacheMetrics.TAG_OPERATION, "search")
                    .tag(CacheMetrics.TAG_STATUS, "timeout")
                    .counter();
            assertNotNull(timeoutCounter);
            assertEquals(1.0, timeoutCounter.count());

            Timer timeoutTimer = meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATION_DURATION)
                    .tag(CacheMetrics.TAG_OPERATION, "search")
                    .tag(CacheMetrics.TAG_STATUS, "timeout")
                    .timer();
            assertNotNull(timeoutTimer);
            assertEquals(1, timeoutTimer.count());

            assertNull(meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATIONS).tag(CacheMetrics.TAG_OPERATION, "search").tag(CacheMetrics.TAG_STATUS, "success").counter());
            assertNull(meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATIONS).tag(CacheMetrics.TAG_OPERATION, "search").tag(CacheMetrics.TAG_STATUS, "error").counter());
            assertNull(meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATION_DURATION).tag(CacheMetrics.TAG_OPERATION, "search").tag(CacheMetrics.TAG_STATUS, "success").timer());
            assertNull(meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATION_DURATION).tag(CacheMetrics.TAG_OPERATION, "search").tag(CacheMetrics.TAG_STATUS, "error").timer());
        }

        @Test
        @DisplayName("Search ExecutionException with generic cause records exactly one SEARCH ERROR counter and timer")
        void searchGenericExecutionExceptionRecordsErrorMetric() throws Exception {
            @SuppressWarnings("unchecked")
            ListenableFuture<List<Points.ScoredPoint>> mockFuture = mock(ListenableFuture.class);
            when(mockFuture.get(anyLong(), any(TimeUnit.class)))
                    .thenThrow(new ExecutionException(new RuntimeException("Search failed in gRPC layer")));
            when(mockClient.searchAsync(any(Points.SearchPoints.class))).thenReturn(mockFuture);

            assertThrows(QdrantOperationException.class, () -> repository.search(sampleVector, 5));

            Counter errorCounter = meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATIONS)
                    .tag(CacheMetrics.TAG_OPERATION, "search")
                    .tag(CacheMetrics.TAG_STATUS, "error")
                    .counter();
            assertNotNull(errorCounter);
            assertEquals(1.0, errorCounter.count());

            Timer errorTimer = meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATION_DURATION)
                    .tag(CacheMetrics.TAG_OPERATION, "search")
                    .tag(CacheMetrics.TAG_STATUS, "error")
                    .timer();
            assertNotNull(errorTimer);
            assertEquals(1, errorTimer.count());

            assertNull(meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATIONS).tag(CacheMetrics.TAG_OPERATION, "search").tag(CacheMetrics.TAG_STATUS, "success").counter());
            assertNull(meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATIONS).tag(CacheMetrics.TAG_OPERATION, "search").tag(CacheMetrics.TAG_STATUS, "timeout").counter());
            assertNull(meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATION_DURATION).tag(CacheMetrics.TAG_OPERATION, "search").tag(CacheMetrics.TAG_STATUS, "success").timer());
            assertNull(meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATION_DURATION).tag(CacheMetrics.TAG_OPERATION, "search").tag(CacheMetrics.TAG_STATUS, "timeout").timer());
        }

        @Test
        @DisplayName("Search validation failures record zero metrics")
        void searchValidationFailuresRecordZeroMetrics() {
            assertThrows(IllegalArgumentException.class, () -> repository.search(null, 5));
            assertThrows(IllegalArgumentException.class, () -> repository.search(Collections.emptyList(), 5));
            assertThrows(IllegalArgumentException.class, () -> repository.search(sampleVector, 0));
            assertThrows(IllegalArgumentException.class, () -> repository.search(sampleVector, -1));

            List<Float> nanVector = new ArrayList<>(sampleVector);
            nanVector.set(0, Float.NaN);
            assertThrows(IllegalArgumentException.class, () -> repository.search(nanVector, 5));

            assertNull(meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATIONS).tag(CacheMetrics.TAG_OPERATION, "search").counter());
            assertNull(meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATION_DURATION).tag(CacheMetrics.TAG_OPERATION, "search").timer());
        }

        @Test
        @DisplayName("Search mapping failure records exactly one SEARCH ERROR counter and timer")
        void searchMappingFailureRecordsErrorMetric() throws Exception {
            Points.ScoredPoint invalidPoint = Points.ScoredPoint.newBuilder()
                    .setScore(0.85f)
                    .build();
            when(mockClient.searchAsync(any(Points.SearchPoints.class)))
                    .thenReturn(Futures.immediateFuture(List.of(invalidPoint)));

            assertThrows(QdrantOperationException.class, () -> repository.search(sampleVector, 5));

            Counter errorCounter = meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATIONS)
                    .tag(CacheMetrics.TAG_OPERATION, "search")
                    .tag(CacheMetrics.TAG_STATUS, "error")
                    .counter();
            assertNotNull(errorCounter);
            assertEquals(1.0, errorCounter.count());

            Timer errorTimer = meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATION_DURATION)
                    .tag(CacheMetrics.TAG_OPERATION, "search")
                    .tag(CacheMetrics.TAG_STATUS, "error")
                    .timer();
            assertNotNull(errorTimer);
            assertEquals(1, errorTimer.count());

            assertNull(meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATIONS).tag(CacheMetrics.TAG_OPERATION, "search").tag(CacheMetrics.TAG_STATUS, "success").counter());
            assertNull(meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATIONS).tag(CacheMetrics.TAG_OPERATION, "search").tag(CacheMetrics.TAG_STATUS, "timeout").counter());
        }

        @Test
        @DisplayName("Wrapped InterruptedException inside ExecutionException restores interrupt flag and records exactly one SEARCH ERROR counter and timer")
        void searchWrappedInterruptedExceptionRestoresInterrupt() throws Exception {
            @SuppressWarnings("unchecked")
            ListenableFuture<List<Points.ScoredPoint>> mockFuture = mock(ListenableFuture.class);
            when(mockFuture.get(anyLong(), any(TimeUnit.class)))
                    .thenThrow(new ExecutionException(new InterruptedException("Wrapped interrupt")));
            when(mockClient.searchAsync(any(Points.SearchPoints.class))).thenReturn(mockFuture);

            try {
                assertThrows(QdrantOperationException.class, () -> repository.search(sampleVector, 5));
                assertTrue(Thread.currentThread().isInterrupted(), "Thread interrupt flag must be restored");
            } finally {
                Thread.interrupted();
            }

            Counter errorCounter = meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATIONS)
                    .tag(CacheMetrics.TAG_OPERATION, "search")
                    .tag(CacheMetrics.TAG_STATUS, "error")
                    .counter();
            assertNotNull(errorCounter);
            assertEquals(1.0, errorCounter.count());

            Timer errorTimer = meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATION_DURATION)
                    .tag(CacheMetrics.TAG_OPERATION, "search")
                    .tag(CacheMetrics.TAG_STATUS, "error")
                    .timer();
            assertNotNull(errorTimer);
            assertEquals(1, errorTimer.count());

            assertNull(meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATIONS).tag(CacheMetrics.TAG_OPERATION, "search").tag(CacheMetrics.TAG_STATUS, "success").counter());
            assertNull(meterRegistry.find(CacheMetrics.METRIC_QDRANT_OPERATIONS).tag(CacheMetrics.TAG_OPERATION, "search").tag(CacheMetrics.TAG_STATUS, "timeout").counter());
        }
    }
}
