package com.semanticcache.backend.controller;

import com.semanticcache.backend.config.CacheConfig;
import com.semanticcache.backend.dto.CacheLookupResult;
import com.semanticcache.backend.dto.SearchFilter;
import com.semanticcache.backend.dto.SearchResult;
import com.semanticcache.backend.exception.GeminiApiException;
import com.semanticcache.backend.exception.GeminiEmptyResponseException;
import com.semanticcache.backend.exception.GeminiMissingCredentialsException;
import com.semanticcache.backend.exception.QdrantOperationException;
import com.semanticcache.backend.service.CacheDecisionService;
import com.semanticcache.backend.service.CacheRetrievalService;
import com.semanticcache.backend.service.DefaultCacheDecisionService;
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

import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    /**
     * Real DefaultCacheDecisionService instance used to exercise genuine privacy and threshold
     * evaluation logic where practical while isolating external vector retrieval and LLM boundaries.
     */
    private final DefaultCacheDecisionService realCacheDecisionService =
            new DefaultCacheDecisionService(new CacheConfig(0.85f));

    @BeforeEach
    void setUpMocks() {
        when(cacheRetrievalService.findBestCandidate(anyString(), any(SearchFilter.class)))
                .thenReturn(Optional.empty());
        when(cacheDecisionService.evaluate(any(), any(SearchFilter.class)))
                .thenAnswer(invocation -> realCacheDecisionService.evaluate(
                        invocation.getArgument(0), invocation.getArgument(1)));
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

    @Test
    @DisplayName("POST /api/cache on public cache hit returns 200 OK with cached answer and cacheHit=true without invoking Gemini")
    void publicCacheHitReturnsCachedAnswerWithCacheHitTrue() throws Exception {
        // External retrieval boundary: mock candidate returned by Qdrant vector retrieval for publicOnly filter
        SearchResult publicCandidate = new SearchResult(
                UUID.randomUUID(),
                "What is caching?",
                "Caching stores data in high-speed storage for fast retrieval.",
                "gemini-3.8-flash",
                Instant.now(),
                "gemini-embedding-001",
                0.95f,
                null,
                "PUBLIC"
        );
        when(cacheRetrievalService.findBestCandidate(eq("What is caching?"), eq(SearchFilter.publicOnly())))
                .thenReturn(Optional.of(publicCandidate));

        // Real DefaultCacheDecisionService evaluates candidate: score 0.95 >= 0.85 threshold, visibility PUBLIC -> HIT
        mockMvc.perform(post("/api/cache")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "prompt": "What is caching?"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer", is("Caching stores data in high-speed storage for fast retrieval.")))
                .andExpect(jsonPath("$.cacheHit", is(true)));

        // Verify downstream boundaries: external Gemini generation is NEVER called on a cache hit
        verify(geminiProvider, never()).generateAnswer(anyString());

        // Explicitly verify retrieval service received internally generated SearchFilter.publicOnly() for anonymous request
        ArgumentCaptor<SearchFilter> filterCaptor = ArgumentCaptor.forClass(SearchFilter.class);
        verify(cacheRetrievalService).findBestCandidate(eq("What is caching?"), filterCaptor.capture());
        assertEquals(SearchFilter.publicOnly(), filterCaptor.getValue());
        assertTrue(filterCaptor.getValue().isPublicOnly());
        assertFalse(filterCaptor.getValue().hasOwner());
    }

    @Test
    @DisplayName("POST /api/cache with private candidate rejects candidate for anonymous caller and falls back to Gemini")
    void privateCandidateRejectedForAnonymousRequestFallsBackToGemini() throws Exception {
        // External retrieval boundary: candidate has PRIVATE visibility and an owner
        SearchResult privateCandidate = new SearchResult(
                UUID.randomUUID(),
                "What is my secret account balance?",
                "Your confidential balance is $10,000.",
                "gemini-3.8-flash",
                Instant.now(),
                "gemini-embedding-001",
                0.98f,
                "user-secret-123",
                "PRIVATE"
        );
        when(cacheRetrievalService.findBestCandidate(eq("What is my secret account balance?"), eq(SearchFilter.publicOnly())))
                .thenReturn(Optional.of(privateCandidate));
        when(geminiProvider.generateAnswer(eq("What is my secret account balance?")))
                .thenReturn("Account details cannot be retrieved anonymously.");

        // Real DefaultCacheDecisionService evaluates candidate: anonymous filter (publicOnly) rejects PRIVATE candidate -> MISS
        mockMvc.perform(post("/api/cache")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "prompt": "What is my secret account balance?"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer", is("Account details cannot be retrieved anonymously.")))
                .andExpect(jsonPath("$.cacheHit", is(false)));

        // Verify external LLM boundary was invoked as fallback
        verify(geminiProvider).generateAnswer(eq("What is my secret account balance?"));

        // Explicitly verify retrieval service received internally generated SearchFilter.publicOnly() for anonymous request
        ArgumentCaptor<SearchFilter> filterCaptor = ArgumentCaptor.forClass(SearchFilter.class);
        verify(cacheRetrievalService).findBestCandidate(eq("What is my secret account balance?"), filterCaptor.capture());
        assertEquals(SearchFilter.publicOnly(), filterCaptor.getValue());
        assertTrue(filterCaptor.getValue().isPublicOnly());
        assertFalse(filterCaptor.getValue().hasOwner());
    }

    @Test
    @DisplayName("POST /api/cache propagates Qdrant retrieval exception as sanitized HTTP 502 and never calls Gemini")
    void qdrantRetrievalExceptionPropagatesAsHttp502() throws Exception {
        // External retrieval boundary: Qdrant vector retrieval throws runtime exception
        when(cacheRetrievalService.findBestCandidate(eq("Explain neural networks"), eq(SearchFilter.publicOnly())))
                .thenThrow(new QdrantOperationException("Qdrant connection timed out during vector similarity search"));

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
                .andExpect(jsonPath("$.message", is("Failed to process vector database operation. Please try again later.")))
                .andExpect(jsonPath("$.path", is("/api/cache")))
                .andExpect(jsonPath("$.timestamp", notNullValue()));

        // Verify downstream boundaries: external Gemini generation is NEVER called when retrieval itself fails
        verify(geminiProvider, never()).generateAnswer(anyString());

        // Explicitly verify retrieval service received internally generated SearchFilter.publicOnly() for anonymous request
        ArgumentCaptor<SearchFilter> filterCaptor = ArgumentCaptor.forClass(SearchFilter.class);
        verify(cacheRetrievalService).findBestCandidate(eq("Explain neural networks"), filterCaptor.capture());
        assertEquals(SearchFilter.publicOnly(), filterCaptor.getValue());
        assertTrue(filterCaptor.getValue().isPublicOnly());
        assertFalse(filterCaptor.getValue().hasOwner());
    }
}
