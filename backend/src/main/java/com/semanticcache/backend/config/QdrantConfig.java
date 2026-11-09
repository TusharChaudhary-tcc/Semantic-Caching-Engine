package com.semanticcache.backend.config;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class QdrantConfig {

    private static final Logger log = LoggerFactory.getLogger(QdrantConfig.class);

    private final String host;
    private final int port;
    private final boolean useTls;
    private final String apiKey;
    private final String collectionName;
    private final int vectorSize;
    private final int timeoutSeconds;

    public QdrantConfig(
            String host,
            int port,
            boolean useTls,
            String apiKey,
            String collectionName,
            int vectorSize
    ) {
        this(host, port, useTls, apiKey, collectionName, vectorSize, 10);
    }

    @Autowired
    public QdrantConfig(
            @Value("${qdrant.host:localhost}") String host,
            @Value("${qdrant.port:6334}") int port,
            @Value("${qdrant.use-tls:false}") boolean useTls,
            @Value("${qdrant.api-key:}") String apiKey,
            @Value("${qdrant.collection-name:semantic_cache}") String collectionName,
            @Value("${qdrant.vector-size:${gemini.embedding-dimension:768}}") int vectorSize,
            @Value("${qdrant.timeout-seconds:10}") int timeoutSeconds
    ) {
        if (host == null || host.trim().isEmpty()) {
            throw new IllegalArgumentException("qdrant.host must not be null or blank");
        }
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("qdrant.port must be between 1 and 65535. Configured value: " + port);
        }
        if (collectionName == null || collectionName.trim().isEmpty()) {
            throw new IllegalArgumentException("qdrant.collection-name must not be null or blank");
        }
        if (vectorSize <= 0) {
            throw new IllegalArgumentException("qdrant.vector-size must be greater than zero. Configured value: " + vectorSize);
        }
        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException("qdrant.timeout-seconds must be greater than zero. Configured value: " + timeoutSeconds);
        }

        this.host = host.trim();
        this.port = port;
        this.useTls = useTls;
        this.apiKey = apiKey != null ? apiKey.trim() : "";
        this.collectionName = collectionName.trim();
        this.vectorSize = vectorSize;
        this.timeoutSeconds = timeoutSeconds;
    }

    /**
     * Creates and registers a singleton QdrantClient bean.
     * Managed with destroyMethod = "close" so that Spring closes the gRPC channel on application shutdown.
     */
    @Bean(destroyMethod = "close")
    public QdrantClient qdrantClient() {
        boolean apiKeyConfigured = !apiKey.isEmpty();
        log.info("Configuring QdrantClient connecting to [{}:{}] (TLS: [{}], API key configured: [{}])",
                host, port, useTls, apiKeyConfigured);

        // checkCompatibility=false prevents startup blocks and warnings when Qdrant server is starting or offline
        QdrantGrpcClient.Builder builder = QdrantGrpcClient.newBuilder(host, port, useTls, false);

        if (apiKeyConfigured) {
            builder.withApiKey(apiKey);
        }

        return new QdrantClient(builder.build());
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public boolean isUseTls() {
        return useTls;
    }

    public boolean isApiKeyConfigured() {
        return !apiKey.isEmpty();
    }

    public String getCollectionName() {
        return collectionName;
    }

    public int getVectorSize() {
        return vectorSize;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }
}
