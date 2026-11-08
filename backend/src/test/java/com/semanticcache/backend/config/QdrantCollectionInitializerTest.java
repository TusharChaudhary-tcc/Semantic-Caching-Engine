package com.semanticcache.backend.config;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.semanticcache.backend.exception.QdrantCommunicationException;
import com.semanticcache.backend.exception.QdrantIncompatibleCollectionException;
import com.semanticcache.backend.exception.QdrantInitializationException;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections.CollectionConfig;
import io.qdrant.client.grpc.Collections.CollectionInfo;
import io.qdrant.client.grpc.Collections.CollectionOperationResponse;
import io.qdrant.client.grpc.Collections.CollectionParams;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.VectorParams;
import io.qdrant.client.grpc.Collections.VectorsConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;

import java.net.ConnectException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QdrantCollectionInitializerTest {

    private static final String COLLECTION_NAME = "semantic_cache";
    private static final int VECTOR_SIZE = 768;

    @Mock
    private QdrantClient mockClient;

    private QdrantConfig qdrantConfig;
    private QdrantCollectionInitializer initializer;

    @BeforeEach
    void setUp() {
        qdrantConfig = new QdrantConfig("localhost", 6334, false, "", COLLECTION_NAME, VECTOR_SIZE);
        // Default failOnStartupError is true (fail-fast production setting)
        initializer = new QdrantCollectionInitializer(mockClient, qdrantConfig, true, true);
    }

    private CollectionInfo buildMockCollectionInfo(long size, Distance distance) {
        VectorParams vectorParams = VectorParams.newBuilder()
                .setSize(size)
                .setDistance(distance)
                .build();

        VectorsConfig vectorsConfig = VectorsConfig.newBuilder()
                .setParams(vectorParams)
                .build();

        CollectionParams collectionParams = CollectionParams.newBuilder()
                .setVectorsConfig(vectorsConfig)
                .build();

        CollectionConfig collectionConfig = CollectionConfig.newBuilder()
                .setParams(collectionParams)
                .build();

        return CollectionInfo.newBuilder()
                .setConfig(collectionConfig)
                .build();
    }

    @Test
    @DisplayName("Creates collection with configured name, dimension and Cosine distance when absent")
    void createsCollectionWhenAbsent() {
        when(mockClient.collectionExistsAsync(COLLECTION_NAME))
                .thenReturn(Futures.immediateFuture(Boolean.FALSE));
        when(mockClient.createCollectionAsync(eq(COLLECTION_NAME), any(VectorParams.class)))
                .thenReturn(Futures.immediateFuture(CollectionOperationResponse.getDefaultInstance()));

        initializer.initialize();

        ArgumentCaptor<VectorParams> paramsCaptor = ArgumentCaptor.forClass(VectorParams.class);
        verify(mockClient).createCollectionAsync(eq(COLLECTION_NAME), paramsCaptor.capture());

        VectorParams capturedParams = paramsCaptor.getValue();
        assertEquals(VECTOR_SIZE, capturedParams.getSize());
        assertEquals(Distance.Cosine, capturedParams.getDistance());
    }

    @Test
    @DisplayName("Reuses an existing compatible collection without recreating or modifying it")
    void reusesExistingCompatibleCollection() {
        when(mockClient.collectionExistsAsync(COLLECTION_NAME))
                .thenReturn(Futures.immediateFuture(Boolean.TRUE));

        CollectionInfo compatibleInfo = buildMockCollectionInfo(768, Distance.Cosine);
        when(mockClient.getCollectionInfoAsync(COLLECTION_NAME))
                .thenReturn(Futures.immediateFuture(compatibleInfo));

        assertDoesNotThrow(() -> initializer.initialize());

        verify(mockClient, never()).createCollectionAsync(anyString(), any(VectorParams.class));
        verify(mockClient, never()).deleteCollectionAsync(anyString());
    }

    @Test
    @DisplayName("Incompatible dimension always fails startup even when offline startup is enabled (failOnStartupError=false)")
    void incompatibleDimensionAlwaysFailsStartupEvenWhenOfflineStartupEnabled() {
        QdrantCollectionInitializer offlineInitializer =
                new QdrantCollectionInitializer(mockClient, qdrantConfig, true, false);

        when(mockClient.collectionExistsAsync(COLLECTION_NAME))
                .thenReturn(Futures.immediateFuture(Boolean.TRUE));

        CollectionInfo incompatibleInfo = buildMockCollectionInfo(512, Distance.Cosine); // Expected 768
        when(mockClient.getCollectionInfoAsync(COLLECTION_NAME))
                .thenReturn(Futures.immediateFuture(incompatibleInfo));

        QdrantIncompatibleCollectionException ex = assertThrows(
                QdrantIncompatibleCollectionException.class,
                () -> offlineInitializer.run(new DefaultApplicationArguments())
        );

        assertTrue(ex.getMessage().contains("vector dimension [512]"));
        assertTrue(ex.getMessage().contains("configured dimension is [768]"));

        verify(mockClient, never()).createCollectionAsync(anyString(), any(VectorParams.class));
        verify(mockClient, never()).deleteCollectionAsync(anyString());
    }

    @Test
    @DisplayName("Incorrect distance always fails startup even when offline startup is enabled (failOnStartupError=false)")
    void incorrectDistanceAlwaysFailsStartupEvenWhenOfflineStartupEnabled() {
        QdrantCollectionInitializer offlineInitializer =
                new QdrantCollectionInitializer(mockClient, qdrantConfig, true, false);

        when(mockClient.collectionExistsAsync(COLLECTION_NAME))
                .thenReturn(Futures.immediateFuture(Boolean.TRUE));

        CollectionInfo incompatibleInfo = buildMockCollectionInfo(768, Distance.Euclid); // Expected Cosine
        when(mockClient.getCollectionInfoAsync(COLLECTION_NAME))
                .thenReturn(Futures.immediateFuture(incompatibleInfo));

        QdrantIncompatibleCollectionException ex = assertThrows(
                QdrantIncompatibleCollectionException.class,
                () -> offlineInitializer.run(new DefaultApplicationArguments())
        );

        assertTrue(ex.getMessage().contains("distance metric [Euclid]"));
        assertTrue(ex.getMessage().contains("expected distance metric is [Cosine]"));

        verify(mockClient, never()).createCollectionAsync(anyString(), any(VectorParams.class));
        verify(mockClient, never()).deleteCollectionAsync(anyString());
    }

    @Test
    @DisplayName("Missing or empty vector configuration always fails startup")
    void missingVectorConfigurationAlwaysFailsStartup() {
        QdrantCollectionInitializer offlineInitializer =
                new QdrantCollectionInitializer(mockClient, qdrantConfig, true, false);

        when(mockClient.collectionExistsAsync(COLLECTION_NAME))
                .thenReturn(Futures.immediateFuture(Boolean.TRUE));

        CollectionParams emptyParams = CollectionParams.newBuilder().build();
        CollectionConfig config = CollectionConfig.newBuilder().setParams(emptyParams).build();
        CollectionInfo emptyInfo = CollectionInfo.newBuilder().setConfig(config).build();

        when(mockClient.getCollectionInfoAsync(COLLECTION_NAME))
                .thenReturn(Futures.immediateFuture(emptyInfo));

        assertThrows(
                QdrantIncompatibleCollectionException.class,
                () -> offlineInitializer.run(new DefaultApplicationArguments())
        );

        verify(mockClient, never()).createCollectionAsync(anyString(), any(VectorParams.class));
        verify(mockClient, never()).deleteCollectionAsync(anyString());
    }

    @Test
    @DisplayName("Genuine gRPC connectivity failure (UNAVAILABLE) is tolerated when offline startup is enabled (failOnStartupError=false)")
    void genuineGrpcConnectivityFailureToleratedWhenOfflineEnabled() {
        QdrantCollectionInitializer offlineInitializer =
                new QdrantCollectionInitializer(mockClient, qdrantConfig, true, false);

        StatusRuntimeException grpcUnavailable = new StatusRuntimeException(Status.UNAVAILABLE);
        when(mockClient.collectionExistsAsync(COLLECTION_NAME))
                .thenReturn(Futures.immediateFailedFuture(grpcUnavailable));

        assertDoesNotThrow(() -> offlineInitializer.run(new DefaultApplicationArguments()));
    }

    @Test
    @DisplayName("Genuine network transport failure (ConnectException) is tolerated when offline startup is enabled")
    void genuineTransportFailureToleratedWhenOfflineEnabled() {
        QdrantCollectionInitializer offlineInitializer =
                new QdrantCollectionInitializer(mockClient, qdrantConfig, true, false);

        ConnectException connectException = new ConnectException("Connection refused");
        when(mockClient.collectionExistsAsync(COLLECTION_NAME))
                .thenReturn(Futures.immediateFailedFuture(connectException));

        assertDoesNotThrow(() -> offlineInitializer.run(new DefaultApplicationArguments()));
    }

    @Test
    @DisplayName("Genuine gRPC connectivity failure aborts startup when fail-fast is enabled (failOnStartupError=true)")
    void genuineGrpcConnectivityFailureFailsStartupWhenFailFastEnabled() {
        StatusRuntimeException grpcUnavailable = new StatusRuntimeException(Status.UNAVAILABLE);
        when(mockClient.collectionExistsAsync(COLLECTION_NAME))
                .thenReturn(Futures.immediateFailedFuture(grpcUnavailable));

        QdrantCommunicationException ex = assertThrows(
                QdrantCommunicationException.class,
                () -> initializer.run(new DefaultApplicationArguments())
        );

        assertEquals("Failed to initialize Qdrant collection due to vector database communication failure", ex.getMessage());
        assertNotNull(ex.getCause());
    }

    @Test
    @DisplayName("An unrelated SDK/programming exception aborts startup even when offline startup is enabled (failOnStartupError=false)")
    void unrelatedSdkExceptionAbortsStartupEvenWhenOfflineStartupEnabled() {
        QdrantCollectionInitializer offlineInitializer =
                new QdrantCollectionInitializer(mockClient, qdrantConfig, true, false);

        NullPointerException unrelatedException = new NullPointerException("SDK internal bug");
        when(mockClient.collectionExistsAsync(COLLECTION_NAME))
                .thenReturn(Futures.immediateFailedFuture(unrelatedException));

        QdrantInitializationException ex = assertThrows(
                QdrantInitializationException.class,
                () -> offlineInitializer.run(new DefaultApplicationArguments())
        );

        // Ensure it is NOT a QdrantCommunicationException (which would have been tolerated)
        assertFalse(ex instanceof QdrantCommunicationException,
                "Unrelated exception must not be classified as QdrantCommunicationException");
        assertEquals("Failed to initialize Qdrant collection due to unexpected execution failure", ex.getMessage());
    }

    @Test
    @DisplayName("A non-connectivity gRPC status (e.g. INVALID_ARGUMENT) aborts startup even when offline startup is enabled")
    void nonConnectivityGrpcStatusAbortsStartupEvenWhenOfflineStartupEnabled() {
        QdrantCollectionInitializer offlineInitializer =
                new QdrantCollectionInitializer(mockClient, qdrantConfig, true, false);

        StatusRuntimeException invalidArgument = new StatusRuntimeException(Status.INVALID_ARGUMENT);
        when(mockClient.collectionExistsAsync(COLLECTION_NAME))
                .thenReturn(Futures.immediateFailedFuture(invalidArgument));

        QdrantInitializationException ex = assertThrows(
                QdrantInitializationException.class,
                () -> offlineInitializer.run(new DefaultApplicationArguments())
        );

        assertFalse(ex instanceof QdrantCommunicationException,
                "gRPC INVALID_ARGUMENT must not be classified as QdrantCommunicationException");
    }

    @Test
    @DisplayName("Thread interruption aborts startup even when offline startup is enabled (failOnStartupError=false)")
    void threadInterruptionAbortsStartupEvenWhenOfflineStartupEnabled() throws Exception {
        QdrantCollectionInitializer offlineInitializer =
                new QdrantCollectionInitializer(mockClient, qdrantConfig, true, false);

        @SuppressWarnings("unchecked")
        ListenableFuture<Boolean> mockFuture = mock(ListenableFuture.class);
        when(mockFuture.get()).thenThrow(new InterruptedException("Interrupted during test"));
        when(mockClient.collectionExistsAsync(COLLECTION_NAME)).thenReturn(mockFuture);

        Thread.interrupted(); // clear status prior to test

        try {
            QdrantInitializationException ex = assertThrows(
                    QdrantInitializationException.class,
                    () -> offlineInitializer.run(new DefaultApplicationArguments())
            );

            assertFalse(ex instanceof QdrantCommunicationException);
            assertTrue(Thread.currentThread().isInterrupted(), "Thread interrupted status must be restored");
            assertEquals("Failed to initialize Qdrant collection due to thread interruption", ex.getMessage());
        } finally {
            Thread.interrupted(); // clean up
        }
    }

    @Test
    @DisplayName("Uses custom configured collection name and vector dimension")
    void usesCustomConfiguredValues() {
        QdrantConfig customConfig = new QdrantConfig("localhost", 6334, false, "", "custom_embeddings", 1536);
        QdrantCollectionInitializer customInitializer = new QdrantCollectionInitializer(mockClient, customConfig, true, true);

        when(mockClient.collectionExistsAsync("custom_embeddings"))
                .thenReturn(Futures.immediateFuture(Boolean.FALSE));
        when(mockClient.createCollectionAsync(eq("custom_embeddings"), any(VectorParams.class)))
                .thenReturn(Futures.immediateFuture(CollectionOperationResponse.getDefaultInstance()));

        customInitializer.initialize();

        ArgumentCaptor<VectorParams> paramsCaptor = ArgumentCaptor.forClass(VectorParams.class);
        verify(mockClient).createCollectionAsync(eq("custom_embeddings"), paramsCaptor.capture());

        VectorParams capturedParams = paramsCaptor.getValue();
        assertEquals(1536, capturedParams.getSize());
        assertEquals(Distance.Cosine, capturedParams.getDistance());
    }

    @Test
    @DisplayName("Initialization behavior is idempotent across repeated invocations")
    void initializationIsIdempotent() {
        when(mockClient.collectionExistsAsync(COLLECTION_NAME))
                .thenReturn(Futures.immediateFuture(Boolean.FALSE))
                .thenReturn(Futures.immediateFuture(Boolean.TRUE));

        when(mockClient.createCollectionAsync(eq(COLLECTION_NAME), any(VectorParams.class)))
                .thenReturn(Futures.immediateFuture(CollectionOperationResponse.getDefaultInstance()));

        CollectionInfo compatibleInfo = buildMockCollectionInfo(768, Distance.Cosine);
        when(mockClient.getCollectionInfoAsync(COLLECTION_NAME))
                .thenReturn(Futures.immediateFuture(compatibleInfo));

        initializer.initialize(); // First run
        initializer.initialize(); // Second run

        verify(mockClient, times(1)).createCollectionAsync(eq(COLLECTION_NAME), any(VectorParams.class));
    }

    @Test
    @DisplayName("ApplicationRunner skips initialization when disabled by configuration")
    void skipsInitializationWhenDisabled() {
        QdrantCollectionInitializer disabledInitializer =
                new QdrantCollectionInitializer(mockClient, qdrantConfig, false, true);

        disabledInitializer.run(new DefaultApplicationArguments());

        verify(mockClient, never()).collectionExistsAsync(anyString());
    }
}
