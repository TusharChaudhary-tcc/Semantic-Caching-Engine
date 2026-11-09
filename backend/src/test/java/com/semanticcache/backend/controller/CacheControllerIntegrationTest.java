package com.semanticcache.backend.controller;

import com.semanticcache.backend.dto.CacheLookupResult;
import com.semanticcache.backend.dto.SearchFilter;
import com.semanticcache.backend.exception.GeminiApiException;
import com.semanticcache.backend.exception.GeminiEmptyResponseException;
import com.semanticcache.backend.exception.GeminiMissingCredentialsException;
import com.semanticcache.backend.service.CacheDecisionService;
import com.semanticcache.backend.service.CacheRetrievalService;
import com.semanticcache.backend.service.GeminiProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class CacheControllerIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CacheRetrievalService cacheRetrievalService;

    @MockitoBean
    private CacheDecisionService cacheDecisionService;

    @MockitoBean
    private GeminiProvider geminiProvider;

    @BeforeEach
    void setUpMocks() {
        when(cacheRetrievalService.findBestCandidate(anyString(), any(SearchFilter.class)))
                .thenReturn(Optional.empty());
        when(cacheDecisionService.evaluate(any(), any(SearchFilter.class)))
                .thenReturn(CacheLookupResult.missNoCandidate());
    }

    @Test
    @DisplayName("POST /api/cache with valid prompt returns 200 OK and Gemini answer with cacheHit=false")
    void validPromptReturnsOkWithGeneratedAnswer() throws Exception {
        when(geminiProvider.generateAnswer(eq("Explain neural networks")))
                .thenReturn("Neural networks are computing systems inspired by biological neural networks.");

        mockMvc.perform(post("/api/cache")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "prompt": "Explain neural networks"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer", is("Neural networks are computing systems inspired by biological neural networks.")))
                .andExpect(jsonPath("$.cacheHit", is(false)));
    }

    @Test
    @DisplayName("POST /api/cache with blank prompt returns 400 Bad Request and validation error map")
    void blankPromptReturns400WithValidationError() throws Exception {
        mockMvc.perform(post("/api/cache")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "prompt": "   "
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)))
                .andExpect(jsonPath("$.error", is("Bad Request")))
                .andExpect(jsonPath("$.message", is("Validation failed for one or more fields")))
                .andExpect(jsonPath("$.path", is("/api/cache")))
                .andExpect(jsonPath("$.validationErrors.prompt", is("Prompt cannot be blank")))
                .andExpect(jsonPath("$.timestamp", notNullValue()));
    }

    @Test
    @DisplayName("POST /api/cache with 4000-character prompt returns 200 OK")
    void boundaryLengthPromptReturns200() throws Exception {
        String prompt = "a".repeat(4000);
        when(geminiProvider.generateAnswer(eq(prompt)))
                .thenReturn("Answer for 4000 char prompt");

        mockMvc.perform(post("/api/cache")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"" + prompt + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer", is("Answer for 4000 char prompt")))
                .andExpect(jsonPath("$.cacheHit", is(false)));

        verify(geminiProvider).generateAnswer(eq(prompt));
    }

    @Test
    @DisplayName("POST /api/cache with 4001-character prompt returns 400 Bad Request and does not invoke downstream services")
    void overLimitPromptReturns400() throws Exception {
        String prompt = "a".repeat(4001);

        mockMvc.perform(post("/api/cache")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"" + prompt + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)))
                .andExpect(jsonPath("$.error", is("Bad Request")))
                .andExpect(jsonPath("$.message", is("Validation failed for one or more fields")))
                .andExpect(jsonPath("$.path", is("/api/cache")))
                .andExpect(jsonPath("$.validationErrors.prompt", is("Prompt cannot exceed 4000 characters")))
                .andExpect(jsonPath("$.timestamp", notNullValue()));

        verify(cacheRetrievalService, never()).findBestCandidate(anyString(), any());
        verify(cacheDecisionService, never()).evaluate(any(), any());
        verify(geminiProvider, never()).generateAnswer(anyString());
    }

    @Test
    @DisplayName("POST /api/cache with Content-Length exceeding 65536 bytes returns 413 Payload Too Large")
    void declaredContentLengthExceedingLimitReturns413() throws Exception {
        byte[] payload = "{\"prompt\":\"test\"}".getBytes();

        mockMvc.perform(post("/api/cache")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Content-Length", 70000)
                        .content(payload))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.status", is(413)))
                .andExpect(jsonPath("$.error", is("Payload Too Large")))
                .andExpect(jsonPath("$.message", is("Request body exceeds maximum allowed size of 65536 bytes")))
                .andExpect(jsonPath("$.path", is("/api/cache")))
                .andExpect(jsonPath("$.timestamp", notNullValue()));

        verify(cacheRetrievalService, never()).findBestCandidate(anyString(), any());
        verify(cacheDecisionService, never()).evaluate(any(), any());
        verify(geminiProvider, never()).generateAnswer(anyString());
    }

    @Test
    @DisplayName("POST /api/cache with oversized JSON body via MockMvc returns 413 Payload Too Large")
    void oversizedBodyWithCalculatedContentLengthReturns413() throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"prompt\":\"hello\",\"extra\":\"");
        while (sb.length() < 70000) {
            sb.append("x");
        }
        sb.append("\"}");

        mockMvc.perform(post("/api/cache")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sb.toString().getBytes()))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.status", is(413)))
                .andExpect(jsonPath("$.error", is("Payload Too Large")))
                .andExpect(jsonPath("$.message", is("Request body exceeds maximum allowed size of 65536 bytes")))
                .andExpect(jsonPath("$.path", is("/api/cache")))
                .andExpect(jsonPath("$.timestamp", notNullValue()));

        verify(cacheRetrievalService, never()).findBestCandidate(anyString(), any());
        verify(cacheDecisionService, never()).evaluate(any(), any());
        verify(geminiProvider, never()).generateAnswer(anyString());
    }

    @Test
    @DisplayName("POST /api/cache with chunked streaming body without Content-Length returns 413 Payload Too Large")
    void genuineChunkedStreamExceedingLimitReturns413() throws Exception {
        byte[] payload = ("{\"prompt\":\"" + "a".repeat(70000) + "\"}").getBytes(StandardCharsets.UTF_8);

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/cache"))
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(payload)))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(413, response.statusCode());
        assertTrue(response.body().contains("\"status\":413"));
        assertTrue(response.body().contains("\"error\":\"Payload Too Large\""));
        assertTrue(response.body().contains("\"message\":\"Request body exceeds maximum allowed size of 65536 bytes\""));
        assertTrue(response.body().contains("\"path\":\"/api/cache\""));

        verify(cacheRetrievalService, never()).findBestCandidate(anyString(), any());
        verify(cacheDecisionService, never()).evaluate(any(), any());
        verify(geminiProvider, never()).generateAnswer(anyString());
    }

    @Test
    @DisplayName("POST /api/cache with valid JSON body under the limit is not rejected by size filter")
    void validBodyUnderLimitNotRejectedBySizeFilter() throws Exception {
        when(geminiProvider.generateAnswer(eq("Explain neural networks")))
                .thenReturn("Neural networks answer");

        mockMvc.perform(post("/api/cache")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"Explain neural networks\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer", is("Neural networks answer")))
                .andExpect(jsonPath("$.cacheHit", is(false)));

        verify(geminiProvider).generateAnswer(eq("Explain neural networks"));
    }

    @Test
    @DisplayName("POST /api/cache with empty JSON returns 400 Bad Request")
    void emptyBodyReturns400() throws Exception {
        mockMvc.perform(post("/api/cache")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)))
                .andExpect(jsonPath("$.validationErrors.prompt", is("Prompt cannot be blank")));
    }

    @Test
    @DisplayName("POST /api/cache with malformed JSON returns 400 Bad Request")
    void malformedJsonReturns400() throws Exception {
        mockMvc.perform(post("/api/cache")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{invalid-json}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)))
                .andExpect(jsonPath("$.message", is("Malformed JSON request body")));
    }

    @Test
    @DisplayName("POST /api/cache returns 503 Service Unavailable with sanitized message when Gemini API key is missing")
    void missingApiKeyReturns503WithSanitizedMessage() throws Exception {
        when(geminiProvider.generateAnswer(eq("Explain neural networks")))
                .thenThrow(new GeminiMissingCredentialsException("Internal raw message that must not be leaked"));

        mockMvc.perform(post("/api/cache")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "prompt": "Explain neural networks"
                                }
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status", is(503)))
                .andExpect(jsonPath("$.error", is("Service Unavailable")))
                .andExpect(jsonPath("$.message", is("The AI service is temporarily unavailable due to missing credentials. Please configure the server API key.")))
                .andExpect(jsonPath("$.path", is("/api/cache")));
    }

    @Test
    @DisplayName("POST /api/cache returns 502 Bad Gateway with sanitized message when Gemini API fails")
    void geminiApiErrorReturns502WithSanitizedMessage() throws Exception {
        when(geminiProvider.generateAnswer(eq("Explain neural networks")))
                .thenThrow(new GeminiApiException("Raw SDK exception: com.google.genai.errors.ClientException: Quota exceeded"));

        mockMvc.perform(post("/api/cache")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "prompt": "Explain neural networks"
                                }
                                """))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.status", is(502)))
                .andExpect(jsonPath("$.error", is("Bad Gateway")))
                .andExpect(jsonPath("$.message", is("The AI service failed to generate a response. Please try again later.")))
                .andExpect(jsonPath("$.path", is("/api/cache")));
    }

    @Test
    @DisplayName("POST /api/cache returns 502 Bad Gateway with sanitized message when Gemini returns empty response")
    void geminiEmptyResponseReturns502WithSanitizedMessage() throws Exception {
        when(geminiProvider.generateAnswer(eq("Explain neural networks")))
                .thenThrow(new GeminiEmptyResponseException("Empty response received"));

        mockMvc.perform(post("/api/cache")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "prompt": "Explain neural networks"
                                }
                                """))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.status", is(502)))
                .andExpect(jsonPath("$.error", is("Bad Gateway")))
                .andExpect(jsonPath("$.message", is("The AI service returned an empty response. Please try again later.")))
                .andExpect(jsonPath("$.path", is("/api/cache")));
    }
}
