package com.semanticcache.backend.provider;

import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import com.semanticcache.backend.exception.GeminiMissingCredentialsException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class DefaultGeminiClientProvider implements GeminiClientProvider {

    private static final Logger log = LoggerFactory.getLogger(DefaultGeminiClientProvider.class);
    public static final String ENV_GOOGLE_API_KEY = "GOOGLE_API_KEY";

    private final int timeoutSeconds;
    private final Object lock = new Object();
    private volatile Client cachedClient;

    public DefaultGeminiClientProvider(
            @Value("${gemini.timeout-seconds:30}") int timeoutSeconds
    ) {
        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException(
                    "gemini.timeout-seconds must be greater than zero. Configured value: " + timeoutSeconds
            );
        }
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public Client getClient() {
        if (cachedClient == null) {
            synchronized (lock) {
                if (cachedClient == null) {
                    cachedClient = createClient();
                }
            }
        }
        return cachedClient;
    }

    private Client createClient() {
        String apiKey = resolveApiKey();
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new GeminiMissingCredentialsException(
                    "Google Gemini API key is not configured. Please set the GOOGLE_API_KEY environment variable."
            );
        }

        return createClientInstance(apiKey.trim(), timeoutSeconds);
    }

    /**
     * Instantiates the Client with configured apiKey and timeout.
     * Package-private / protected to allow mocking in lifecycle unit tests without network calls.
     */
    protected Client createClientInstance(String apiKey, int timeoutSeconds) {
        HttpOptions httpOptions = HttpOptions.builder()
                // .timeout(timeoutSeconds * 1000)
                .timeout(Math.multiplyExact(timeoutSeconds, 1000))
                .build();

        return Client.builder()
                .apiKey(apiKey)
                .httpOptions(httpOptions)
                .build();
    }

    /**
     * Resolves the API key securely from environment variables.
     * Never logs, prints, or exposes the key value.
     */
    protected String resolveApiKey() {
        return System.getenv(ENV_GOOGLE_API_KEY);
    }

    @PreDestroy
    public void close() {
        synchronized (lock) {
            if (cachedClient != null) {
                try {
                    cachedClient.close();
                    log.info("Closed Gemini Client instance successfully.");
                } catch (Exception e) {
                    log.warn("Error closing Gemini Client instance: [{}]", e.getClass().getName());
                } finally {
                    cachedClient = null;
                }
            }
        }
    }
}
