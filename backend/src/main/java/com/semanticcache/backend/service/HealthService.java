package com.semanticcache.backend.service;

import com.google.common.util.concurrent.ListenableFuture;
import com.semanticcache.backend.dto.HealthResponse;
import com.semanticcache.backend.provider.DefaultGeminiClientProvider;
import io.qdrant.client.QdrantClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

@Service
public class HealthService {

    private static final Logger log = LoggerFactory.getLogger(HealthService.class);

    private static final String STATUS_UP = "UP";
    private static final String STATUS_DOWN = "DOWN";
    private static final String CREDENTIAL_CONFIGURED = "CONFIGURED";
    private static final String CREDENTIAL_MISSING = "MISSING";

    private final QdrantClient qdrantClient;
    private final int qdrantTimeoutSeconds;
    private final Supplier<Boolean> geminiCredentialSupplier;

    @Autowired
    public HealthService(
            @Autowired(required = false) QdrantClient qdrantClient,
            @Value("${qdrant.health-timeout-seconds:3}") int qdrantTimeoutSeconds
    ) {
        this(qdrantClient, qdrantTimeoutSeconds, () -> {
            String apiKey = System.getenv(DefaultGeminiClientProvider.ENV_GOOGLE_API_KEY);
            return apiKey != null && !apiKey.trim().isEmpty();
        });
    }

    public HealthService(
            QdrantClient qdrantClient,
            int qdrantTimeoutSeconds,
            Supplier<Boolean> geminiCredentialSupplier
    ) {
        this.qdrantClient = qdrantClient;
        this.qdrantTimeoutSeconds = qdrantTimeoutSeconds > 0 ? qdrantTimeoutSeconds : 3;
        this.geminiCredentialSupplier = geminiCredentialSupplier != null
                ? geminiCredentialSupplier
                : () -> false;
    }

    public HealthService() {
        this(null, 3, () -> false);
    }

    public String getStatus() {
        return "Semantic Cache backend is running!";
    }

    /**
     * Reports liveness independently of external dependencies.
     * Always returns UP as long as the application process is running.
     */
    public HealthResponse checkLiveness() {
        return HealthResponse.of(STATUS_UP, "Semantic Cache backend is live");
    }

    /**
     * Evaluates readiness to serve traffic:
     * 1. Performs a bounded Qdrant health check.
     * 2. Checks presence of Gemini credentials without invoking live network inference.
     *
     * Note: Gemini credentials affect cache retrieval because text embeddings must be
     * generated before querying the vector store.
     * We report credential status as CONFIGURED / MISSING and do not falsely claim
     * the external Gemini service is operational.
     */
    public HealthResponse checkReadiness() {
        String qdrantStatus = checkQdrantHealth();
        boolean credentialsConfigured = Boolean.TRUE.equals(geminiCredentialSupplier.get());
        String geminiStatus = credentialsConfigured ? CREDENTIAL_CONFIGURED : CREDENTIAL_MISSING;

        Map<String, String> components = new LinkedHashMap<>();
        components.put("qdrant", qdrantStatus);
        components.put("geminiCredentials", geminiStatus);

        boolean ready = STATUS_UP.equals(qdrantStatus) && credentialsConfigured;
        String overallStatus = ready ? STATUS_UP : STATUS_DOWN;
        String message = ready
                ? "Semantic Cache backend is ready"
                : "Semantic Cache backend is not ready";

        return HealthResponse.of(overallStatus, message, components);
    }

    private String checkQdrantHealth() {
        if (qdrantClient == null) {
            log.warn("QdrantClient bean is not available for readiness check");
            return STATUS_DOWN;
        }

        ListenableFuture<?> future = null;
        try {
            future = qdrantClient.healthCheckAsync();
            future.get(qdrantTimeoutSeconds, TimeUnit.SECONDS);
            return STATUS_UP;
        } catch (InterruptedException e) {
            cancelFuture(future);
            Thread.currentThread().interrupt();
            log.warn("Qdrant health check interrupted: exception class [{}]", e.getClass().getName());
            return STATUS_DOWN;
        } catch (TimeoutException e) {
            cancelFuture(future);
            log.warn("Qdrant health check timed out after [{}] seconds: exception class [{}]",
                    qdrantTimeoutSeconds, e.getClass().getName());
            return STATUS_DOWN;
        } catch (ExecutionException e) {
            log.warn("Qdrant health check failed: exception class [{}]",
                    e.getCause() != null ? e.getCause().getClass().getName() : e.getClass().getName());
            return STATUS_DOWN;
        } catch (Exception e) {
            log.warn("Qdrant health check encountered unexpected failure: exception class [{}]", e.getClass().getName());
            return STATUS_DOWN;
        }
    }

    private void cancelFuture(ListenableFuture<?> future) {
        if (future != null) {
            try {
                future.cancel(true);
            } catch (Exception e) {
                log.warn("Failed to cancel Qdrant health check future: exception class [{}]", e.getClass().getName());
            }
        }
    }
}
