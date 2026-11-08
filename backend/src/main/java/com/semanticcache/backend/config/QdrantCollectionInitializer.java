package com.semanticcache.backend.config;

import com.semanticcache.backend.exception.QdrantCommunicationException;
import com.semanticcache.backend.exception.QdrantIncompatibleCollectionException;
import com.semanticcache.backend.exception.QdrantInitializationException;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.StatusRuntimeException;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections.CollectionInfo;
import io.qdrant.client.grpc.Collections.CollectionParams;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.VectorParams;
import io.qdrant.client.grpc.Collections.VectorsConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Objects;
import java.util.concurrent.ExecutionException;

@Component
public class QdrantCollectionInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(QdrantCollectionInitializer.class);

    private final QdrantClient qdrantClient;
    private final QdrantConfig qdrantConfig;
    private final boolean initializeCollection;
    private final boolean failOnStartupError;

    public QdrantCollectionInitializer(
            QdrantClient qdrantClient,
            QdrantConfig qdrantConfig,
            @Value("${qdrant.initialize-collection:true}") boolean initializeCollection,
            @Value("${qdrant.fail-on-startup-error:true}") boolean failOnStartupError
    ) {
        this.qdrantClient = Objects.requireNonNull(qdrantClient, "qdrantClient must not be null");
        this.qdrantConfig = Objects.requireNonNull(qdrantConfig, "qdrantConfig must not be null");
        this.initializeCollection = initializeCollection;
        this.failOnStartupError = failOnStartupError;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!initializeCollection) {
            log.info("Qdrant collection initialization is disabled by configuration (qdrant.initialize-collection=false). Skipping.");
            return;
        }

        try {
            initialize();
        } catch (QdrantIncompatibleCollectionException e) {
            // Configuration/incompatibility errors ALWAYS abort application startup
            log.error("Incompatible Qdrant collection detected. Application startup aborted: [{}]", e.getMessage());
            throw e;
        } catch (QdrantCommunicationException e) {
            // Genuine connectivity / transport failures are tolerated only when failOnStartupError is explicitly false
            if (failOnStartupError) {
                log.error("Qdrant communication failed during application startup: [{}]", e.getMessage());
                throw e;
            }
            log.warn("Qdrant server is unavailable during application startup. Collection initialization skipped: [{}]", e.getMessage());
        } catch (QdrantInitializationException e) {
            // All other initialization errors (interruption, unexpected runtime failures, etc.) ALWAYS abort startup
            log.error("Qdrant initialization failed during application startup: [{}]", e.getMessage());
            throw e;
        }
    }

    /**
     * Idempotently initializes the configured Qdrant collection.
     * Verifies existence; creates collection with configured vector size and Cosine distance if absent;
     * verifies dimension and distance compatibility if already present.
     *
     * @throws QdrantIncompatibleCollectionException if existing collection has incompatible dimension, metric or config
     * @throws QdrantCommunicationException          if communication with Qdrant fails due to network/connectivity issues
     * @throws QdrantInitializationException         if thread is interrupted or an unexpected failure occurs
     */
    public synchronized void initialize() {
        String collectionName = qdrantConfig.getCollectionName();
        int expectedVectorSize = qdrantConfig.getVectorSize();

        log.info("Verifying Qdrant collection [{}] (expected dimension: [{}], distance: [Cosine])...",
                collectionName, expectedVectorSize);

        try {
            boolean exists = qdrantClient.collectionExistsAsync(collectionName).get();

            if (!exists) {
                log.info("Qdrant collection [{}] does not exist. Creating collection...", collectionName);

                VectorParams vectorParams = VectorParams.newBuilder()
                        .setSize(expectedVectorSize)
                        .setDistance(Distance.Cosine)
                        .build();

                qdrantClient.createCollectionAsync(collectionName, vectorParams).get();
                log.info("Successfully created Qdrant collection [{}] with dimension [{}] and distance [Cosine].",
                        collectionName, expectedVectorSize);
            } else {
                log.info("Qdrant collection [{}] already exists. Inspecting vector configuration...", collectionName);

                CollectionInfo info = qdrantClient.getCollectionInfoAsync(collectionName).get();
                validateExistingCollection(collectionName, info, expectedVectorSize);

                log.info("Qdrant collection [{}] is compatible and ready for use.", collectionName);
            }
        } catch (QdrantIncompatibleCollectionException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Thread was interrupted while initializing Qdrant collection [{}]: exception class [{}]",
                    collectionName, e.getClass().getName());
            throw new QdrantInitializationException("Failed to initialize Qdrant collection due to thread interruption", e);
        } catch (ExecutionException e) {
            log.error("Failed to initialize Qdrant collection [{}]: exception class [{}]",
                    collectionName, e.getClass().getName());
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (isCommunicationFailure(cause)) {
                throw new QdrantCommunicationException(
                        "Failed to initialize Qdrant collection due to vector database communication failure", cause);
            }
            throw new QdrantInitializationException(
                    "Failed to initialize Qdrant collection due to unexpected execution failure", cause);
        } catch (Exception e) {
            log.error("Failed to initialize Qdrant collection [{}]: exception class [{}]",
                    collectionName, e.getClass().getName());
            if (isCommunicationFailure(e)) {
                throw new QdrantCommunicationException(
                        "Failed to initialize Qdrant collection due to vector database communication failure", e);
            }
            throw new QdrantInitializationException(
                    "Failed to initialize Qdrant collection due to unexpected failure", e);
        }
    }

    /**
     * Determines whether a throwable or any cause in its causal chain represents a genuine
     * transport or network connectivity failure (e.g. gRPC UNAVAILABLE, connection refused, host unreachable).
     * Does not parse exception-message strings.
     *
     * @param throwable the throwable to inspect
     * @return true if this is a genuine communication/connectivity failure, false otherwise
     */
    private boolean isCommunicationFailure(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof StatusRuntimeException sre) {
                Status.Code code = sre.getStatus().getCode();
                if (code == Status.Code.UNAVAILABLE || code == Status.Code.DEADLINE_EXCEEDED) {
                    return true;
                }
            } else if (current instanceof StatusException se) {
                Status.Code code = se.getStatus().getCode();
                if (code == Status.Code.UNAVAILABLE || code == Status.Code.DEADLINE_EXCEEDED) {
                    return true;
                }
            } else if (current instanceof ConnectException
                    || current instanceof SocketException
                    || current instanceof UnknownHostException
                    || current instanceof NoRouteToHostException
                    || current instanceof SocketTimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void validateExistingCollection(String collectionName, CollectionInfo info, int expectedVectorSize) {
        if (info == null || !info.hasConfig() || !info.getConfig().hasParams()) {
            throw new QdrantIncompatibleCollectionException(
                    "Existing Qdrant collection [" + collectionName + "] has missing or invalid configuration metadata."
            );
        }

        CollectionParams params = info.getConfig().getParams();
        if (!params.hasVectorsConfig()) {
            throw new QdrantIncompatibleCollectionException(
                    "Existing Qdrant collection [" + collectionName + "] is missing vector configuration."
            );
        }

        VectorsConfig vectorsConfig = params.getVectorsConfig();
        VectorParams vectorParams;
        if (vectorsConfig.hasParams()) {
            vectorParams = vectorsConfig.getParams();
        } else if (vectorsConfig.hasParamsMap()) {
            throw new QdrantIncompatibleCollectionException(
                    "Existing Qdrant collection [" + collectionName + "] uses a named vectors map, which is incompatible with the expected single-vector configuration."
            );
        } else {
            throw new QdrantIncompatibleCollectionException(
                    "Existing Qdrant collection [" + collectionName + "] has no vector parameters configured."
            );
        }

        long existingSize = vectorParams.getSize();
        Distance existingDistance = vectorParams.getDistance();

        if (existingSize != expectedVectorSize) {
            throw new QdrantIncompatibleCollectionException(
                    "Existing Qdrant collection [" + collectionName + "] has vector dimension [" + existingSize +
                            "], but configured dimension is [" + expectedVectorSize + "]."
            );
        }

        if (existingDistance != Distance.Cosine) {
            throw new QdrantIncompatibleCollectionException(
                    "Existing Qdrant collection [" + collectionName + "] has distance metric [" + existingDistance +
                            "], but expected distance metric is [Cosine]."
            );
        }
    }
}
