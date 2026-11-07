package com.semanticcache.backend.provider;

import com.google.genai.Client;
import com.semanticcache.backend.exception.GeminiMissingCredentialsException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class DefaultGeminiClientProviderTest {

    @Test
    @DisplayName("Throws GeminiMissingCredentialsException when GOOGLE_API_KEY is null")
    void throwsWhenApiKeyIsNull() {
        DefaultGeminiClientProvider provider = new DefaultGeminiClientProvider(30) {
            @Override
            protected String resolveApiKey() {
                return null;
            }
        };

        GeminiMissingCredentialsException ex = assertThrows(
                GeminiMissingCredentialsException.class,
                provider::getClient
        );

        assertTrue(ex.getMessage().contains("GOOGLE_API_KEY"));
    }

    @Test
    @DisplayName("Throws GeminiMissingCredentialsException when GOOGLE_API_KEY is blank")
    void throwsWhenApiKeyIsBlank() {
        DefaultGeminiClientProvider provider = new DefaultGeminiClientProvider(30) {
            @Override
            protected String resolveApiKey() {
                return "   ";
            }
        };

        assertThrows(GeminiMissingCredentialsException.class, provider::getClient);
    }

    @Test
    @DisplayName("Rejects zero or negative timeout values at configuration time")
    void rejectsInvalidTimeoutConfiguration() {
        IllegalArgumentException zeroEx = assertThrows(
                IllegalArgumentException.class,
                () -> new DefaultGeminiClientProvider(0)
        );
        assertTrue(zeroEx.getMessage().contains("must be greater than zero"));

        IllegalArgumentException negativeEx = assertThrows(
                IllegalArgumentException.class,
                () -> new DefaultGeminiClientProvider(-5)
        );
        assertTrue(negativeEx.getMessage().contains("must be greater than zero"));
    }

    @Test
    @DisplayName("Repeated getClient() calls return the exact same cached Client instance")
    void repeatedGetClientCallsReturnSameCachedInstance() {
        Client mockClient = mock(Client.class);

        DefaultGeminiClientProvider provider = new DefaultGeminiClientProvider(30) {
            @Override
            protected String resolveApiKey() {
                return "mock-api-key";
            }

            @Override
            protected Client createClientInstance(String apiKey, int timeoutSeconds) {
                return mockClient;
            }
        };

        Client firstCall = provider.getClient();
        Client secondCall = provider.getClient();

        assertNotNull(firstCall);
        assertSame(firstCall, secondCall, "Consecutive getClient() calls must return the same cached Client singleton");
    }

    @Test
    @DisplayName("Shutdown hook close() gracefully closes the cached Client")
    void shutdownHookClosesCachedClient() {
        Client mockClient = mock(Client.class);

        DefaultGeminiClientProvider provider = new DefaultGeminiClientProvider(30) {
            @Override
            protected String resolveApiKey() {
                return "mock-api-key";
            }

            @Override
            protected Client createClientInstance(String apiKey, int timeoutSeconds) {
                return mockClient;
            }
        };

        provider.getClient();
        provider.close();

        verify(mockClient, times(1)).close();

        // Idempotency: multiple close calls must not fail
        assertDoesNotThrow(provider::close);
    }
}
