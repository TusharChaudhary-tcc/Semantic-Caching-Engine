package com.semanticcache.backend.service;

import com.semanticcache.backend.config.GroqConfig;
import com.semanticcache.backend.exception.GeminiApiException;
import com.semanticcache.backend.exception.GeminiEmptyResponseException;
import com.semanticcache.backend.exception.GeminiMissingCredentialsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Upstream LLM provider for Groq Cloud (OpenAI-compatible Chat Completions API).
 * Enables ultra-fast LPU generation (Llama-3.3-70B / Llama-3.1-8B) on semantic cache misses.
 */
@Service
public class GroqService implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(GroqService.class);

    private final GroqConfig config;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Autowired
    public GroqService(GroqConfig config) {
        this(
                config,
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(config.getTimeoutSeconds()))
                        .build(),
                new ObjectMapper()
        );
    }

    GroqService(GroqConfig config, HttpClient httpClient, ObjectMapper objectMapper) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Override
    public String getProviderName() {
        return "groq";
    }

    @Override
    public String getModelName() {
        return config.getModel();
    }

    @Override
    public boolean isAvailable() {
        return config.getApiKey() != null && !config.getApiKey().isBlank();
    }

    @Override
    public String generateAnswer(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("Prompt must not be null or blank");
        }
        if (!isAvailable()) {
            throw new GeminiMissingCredentialsException("GROQ_API_KEY is not configured.");
        }

        log.info("Sending request to Groq API using model: [{}]", config.getModel());
        try {
            Map<String, Object> payload = Map.of(
                    "model", config.getModel(),
                    "messages", List.of(Map.of("role", "user", "content", prompt.trim()))
            );
            String requestBody = objectMapper.writeValueAsString(payload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(config.getBaseUrl()))
                    .timeout(Duration.ofSeconds(config.getTimeoutSeconds()))
                    .header("Authorization", "Bearer " + config.getApiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new GeminiApiException("Groq API returned HTTP status " + response.statusCode());
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode contentNode = root.path("choices").path(0).path("message").path("content");
            String text = contentNode.isMissingNode() || contentNode.isNull() ? null : contentNode.asString();

            if (text == null || text.isBlank()) {
                throw new GeminiEmptyResponseException("Groq API returned an empty or blank response.");
            }
            return text.trim();
        } catch (GeminiMissingCredentialsException | GeminiApiException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GeminiApiException("Groq API call interrupted", e);
        } catch (Exception e) {
            log.error("Groq API call failed for model [{}]: exception class [{}]",
                    config.getModel(), e.getClass().getName());
            throw new GeminiApiException("Failed to generate response from Groq API", e);
        }
    }
}
