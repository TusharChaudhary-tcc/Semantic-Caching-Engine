package com.semanticcache.backend.service;

import com.semanticcache.backend.config.GroqConfig;
import com.semanticcache.backend.exception.GeminiMissingCredentialsException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MultiLlmRouterTest {

    @Mock
    private GeminiProvider mockGeminiProvider;

    @Mock
    private HttpClient mockHttpClient;

    @Mock
    private HttpResponse<String> mockHttpResponse;

    @Test
    @DisplayName("GroqService parses OpenAI-compatible chat completion response")
    @SuppressWarnings("unchecked")
    void groqServiceParsesCompletion() throws Exception {
        GroqConfig config = new GroqConfig("gsk_test_key", "llama-3.3-70b-versatile",
                "https://api.groq.com/openai/v1/chat/completions", 30, "groq");
        GroqService groqService = new GroqService(config, mockHttpClient, new ObjectMapper());

        String json = """
                {
                  "id": "chatcmpl-123",
                  "choices": [
                    { "message": { "role": "assistant", "content": "Fast answer from Groq LPU." } }
                  ]
                }
                """;
        when(mockHttpResponse.statusCode()).thenReturn(200);
        when(mockHttpResponse.body()).thenReturn(json);
        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(mockHttpResponse);

        String answer = groqService.generateAnswer("What is semantic caching?");
        assertEquals("Fast answer from Groq LPU.", answer);
        assertTrue(groqService.isAvailable());
        assertEquals("groq", groqService.getProviderName());
    }

    @Test
    @DisplayName("GroqService throws missing credentials when GROQ_API_KEY is blank")
    void groqServiceThrowsWhenApiKeyMissing() {
        GroqConfig config = new GroqConfig("", "llama-3.3-70b-versatile",
                "https://api.groq.com/openai/v1/chat/completions", 30, "gemini");
        GroqService groqService = new GroqService(config, mockHttpClient, new ObjectMapper());

        assertFalse(groqService.isAvailable());
        assertThrows(GeminiMissingCredentialsException.class, () -> groqService.generateAnswer("Hello"));
    }

    @Test
    @DisplayName("Router falls back to Gemini when default is groq but API key is missing")
    void routerFallsBackToGeminiWhenGroqUnavailable() {
        GroqConfig config = new GroqConfig("", "llama-3.3-70b-versatile",
                "https://api.groq.com/openai/v1/chat/completions", 30, "groq");
        GroqService groqService = new GroqService(config, mockHttpClient, new ObjectMapper());
        LlmRouterService router = new LlmRouterService(mockGeminiProvider, groqService, config);

        assertSame(mockGeminiProvider, router.resolve(null));
    }

    @Test
    @DisplayName("Router selects Groq when configured and requested")
    void routerSelectsGroqWhenAvailable() {
        GroqConfig config = new GroqConfig("gsk_live", "llama-3.3-70b-versatile",
                "https://api.groq.com/openai/v1/chat/completions", 30, "gemini");
        GroqService groqService = new GroqService(config, mockHttpClient, new ObjectMapper());
        LlmRouterService router = new LlmRouterService(mockGeminiProvider, groqService, config);

        assertSame(groqService, router.resolve("groq"));
        assertSame(mockGeminiProvider, router.resolve("gemini"));
    }
}
