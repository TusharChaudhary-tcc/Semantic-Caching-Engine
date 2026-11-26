package com.semanticcache.backend.service;

import com.semanticcache.backend.config.CacheConfig;
import com.semanticcache.backend.config.CrossEncoderConfig;
import com.semanticcache.backend.dto.CacheLookupResult;
import com.semanticcache.backend.dto.CacheRequest;
import com.semanticcache.backend.dto.CacheResponse;
import com.semanticcache.backend.dto.SearchFilter;
import com.semanticcache.backend.dto.SearchResult;
import com.semanticcache.backend.dto.TopCandidatesResult;
import com.semanticcache.backend.repository.VectorRepository;
import com.semanticcache.backend.telemetry.CacheMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class Stage2CrossEncoderIntegrationTest {

    @Mock
    private CrossEncoderService mockCrossEncoderService;

    @Mock
    private CacheRetrievalService mockCacheRetrievalService;

    @Mock
    private GeminiProvider mockGeminiProvider;

    @Mock
    private VectorRepository mockVectorRepository;

    private CacheConfig cacheConfig;
    private CrossEncoderConfig crossEncoderConfig;
    private DefaultCacheDecisionService decisionService;
    private CacheService cacheService;

    @BeforeEach
    void setUp() {
        cacheConfig = new CacheConfig(0.85f);
        crossEncoderConfig = new CrossEncoderConfig(true, "models/m.onnx", "models/v.txt", 0.85f, 0.75f, 3, 256, false);
        decisionService = new DefaultCacheDecisionService(cacheConfig, mockCrossEncoderService, crossEncoderConfig);
        cacheService = new CacheService(
                mockCacheRetrievalService,
                decisionService,
                mockGeminiProvider,
                mockVectorRepository,
                new CacheMetrics(new SimpleMeterRegistry()),
                "gemini-3.8-flash",
                "gemini-embedding-001",
                mockCrossEncoderService,
                crossEncoderConfig
        );
    }

    private SearchResult candidate(String prompt, String answer, float stage1Score) {
        return new SearchResult(
                UUID.randomUUID(),
                prompt,
                answer,
                "gemini-3.8-flash",
                Instant.now(),
                "gemini-embedding-001",
                stage1Score,
                null,
                "PUBLIC"
        );
    }

    @Test
    @DisplayName("Stage-2 Cross-Encoder rejects false-positive intent mismatch (password reset vs password strength)")
    void rejectsIntentMismatchDespiteHighStage1Similarity() {
        when(mockCrossEncoderService.isAvailable()).thenReturn(true);
        SearchResult mismatch = candidate("What is a good password strength?", "Use 12+ characters.", 0.89f);
        when(mockCrossEncoderService.score("How do I reset my password?", "What is a good password strength?"))
                .thenReturn(0.22f);

        when(mockCacheRetrievalService.findTopCandidatesWithVector("How do I reset my password?", 3, SearchFilter.publicOnly()))
                .thenReturn(new TopCandidatesResult(List.of(mismatch), List.of(0.1f, 0.2f)));
        when(mockGeminiProvider.generateAnswer("How do I reset my password?"))
                .thenReturn("Click Forgot Password to reset it.");

        CacheResponse response = cacheService.processPrompt(new CacheRequest("How do I reset my password?"));

        assertFalse(response.cacheHit());
        assertEquals("Click Forgot Password to reset it.", response.answer());
        assertEquals("CROSS_ENCODER_REJECTED", response.verificationStage());
        assertEquals(0.22f, response.stage2CrossEncoderScore(), 1e-5);
        verify(mockGeminiProvider).generateAnswer("How do I reset my password?");
    }

    @Test
    @DisplayName("Stage-2 Cross-Encoder re-ranks Top-K candidates and selects highest intent match")
    void reranksTopKAndSelectsBestIntentMatch() {
        when(mockCrossEncoderService.isAvailable()).thenReturn(true);
        SearchResult firstByCosine = candidate("What is password strength?", "Strength rules...", 0.88f);
        SearchResult secondByCosine = candidate("I forgot my password", "Reset via email link.", 0.82f);

        when(mockCrossEncoderService.score("How do I reset my password?", "What is password strength?"))
                .thenReturn(0.18f);
        when(mockCrossEncoderService.score("How do I reset my password?", "I forgot my password"))
                .thenReturn(0.94f);

        CacheLookupResult result = decisionService.evaluateCandidates(
                "How do I reset my password?",
                List.of(firstByCosine, secondByCosine),
                SearchFilter.publicOnly()
        );

        assertTrue(result.isHit());
        assertEquals("CROSS_ENCODER_VERIFIED", result.reason());
        assertTrue(result.matchedResult().isPresent());
        assertEquals("Reset via email link.", result.matchedResult().get().answer());
        assertEquals(0.94f, result.crossEncoderScore().orElseThrow(), 1e-5);
    }

    @Test
    @DisplayName("Sigmoid maps logits cleanly to [0, 1]")
    void sigmoidBounds() {
        assertEquals(0.5f, OnnxCrossEncoderService.sigmoid(0.0f), 1e-5);
        assertTrue(OnnxCrossEncoderService.sigmoid(5.0f) > 0.99f);
        assertTrue(OnnxCrossEncoderService.sigmoid(-5.0f) < 0.01f);
        assertNotNull(new CacheResponse("ok", true));
    }
}
