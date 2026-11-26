package com.semanticcache.backend.service;

import com.google.genai.Client;
import com.google.genai.types.GenerateContentResponse;
import com.semanticcache.backend.exception.GeminiApiException;
import com.semanticcache.backend.exception.GeminiEmptyResponseException;
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
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeoutException;

@Service
public class GeminiService implements GeminiProvider {

    private static final Logger log = LoggerFactory.getLogger(GeminiService.class);

    private final GeminiClientProvider clientProvider;
    private final String model;
    private final CacheMetrics cacheMetrics;

    @Autowired
    public GeminiService(
            GeminiClientProvider clientProvider,
            @Value("${gemini.model:gemini-3.8-flash}") String model,
            CacheMetrics cacheMetrics
    ) {
        this.clientProvider = Objects.requireNonNull(clientProvider, "clientProvider must not be null");
        this.model = model;
        this.cacheMetrics = Objects.requireNonNull(cacheMetrics, "cacheMetrics must not be null");
    }

    @Override
    public String generateAnswer(String prompt) {
        log.info("Sending request to Gemini API using model: [{}]", model);

        long startNanos = System.nanoTime();
        try {
            Client client = clientProvider.getClient();
            GenerateContentResponse response = client.models.generateContent(model, prompt, null);

            if (response == null) {
                throw new GeminiEmptyResponseException("Received null response from Gemini API.");
            }

            String text = response.text();
            if (text == null || text.trim().isEmpty()) {
                throw new GeminiEmptyResponseException("Gemini API returned an empty or blank response.");
            }

            Duration duration = Duration.ofNanos(System.nanoTime() - startNanos);
            cacheMetrics.recordGeminiGeneration(model, CacheMetrics.OperationStatus.SUCCESS, duration);
            return text.trim();
        } catch (GeminiMissingCredentialsException | GeminiEmptyResponseException e) {
            Duration duration = Duration.ofNanos(System.nanoTime() - startNanos);
            CacheMetrics.OperationStatus status = isTimeout(e)
                    ? CacheMetrics.OperationStatus.TIMEOUT
                    : CacheMetrics.OperationStatus.ERROR;
            cacheMetrics.recordGeminiGeneration(model, status, duration);
            throw e;
        } catch (Exception e) {
            Duration duration = Duration.ofNanos(System.nanoTime() - startNanos);
            CacheMetrics.OperationStatus status = isTimeout(e)
                    ? CacheMetrics.OperationStatus.TIMEOUT
                    : CacheMetrics.OperationStatus.ERROR;
            cacheMetrics.recordGeminiGeneration(model, status, duration);

            if (hasInterruptedException(e)) {
                Thread.currentThread().interrupt();
            }

            // Log safe metadata only: model and exception class; never log prompt or raw exception details
            log.error("Gemini API call failed for model [{}]: exception class [{}]", model, e.getClass().getName());
            // Do not expose raw SDK message in the exception; preserve cause for internal debugging
            throw new GeminiApiException("Failed to generate response from Gemini API", e);
        }
    }

    public String getModel() {
        return model;
    }

    @Override
    public String getModelName() {
        return model;
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
