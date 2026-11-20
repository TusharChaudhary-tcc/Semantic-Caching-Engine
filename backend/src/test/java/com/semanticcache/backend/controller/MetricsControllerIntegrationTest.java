package com.semanticcache.backend.controller;

import com.semanticcache.backend.repository.VectorRepository;
import com.semanticcache.backend.service.CacheDecisionService;
import com.semanticcache.backend.service.CacheRetrievalService;
import com.semanticcache.backend.service.GeminiProvider;
import com.semanticcache.backend.telemetry.CacheMetrics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class MetricsControllerIntegrationTest {

    private static final String ALLOWED_ORIGIN = "http://localhost:5173";
    private static final String DISALLOWED_ORIGIN = "http://unauthorized-domain.com";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CacheMetrics cacheMetrics;

    @MockitoBean
    private CacheRetrievalService cacheRetrievalService;

    @MockitoBean
    private CacheDecisionService cacheDecisionService;

    @MockitoBean
    private GeminiProvider geminiProvider;

    @MockitoBean
    private VectorRepository vectorRepository;

    @Test
    @DisplayName("GET /api/metrics returns 200 OK and valid JSON response schema")
    void getMetricsReturnsOkWithValidSchema() throws Exception {
        mockMvc.perform(get("/api/metrics"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.totalRequests", greaterThanOrEqualTo(0)))
                .andExpect(jsonPath("$.cacheHits", greaterThanOrEqualTo(0)))
                .andExpect(jsonPath("$.cacheMisses", greaterThanOrEqualTo(0)))
                .andExpect(jsonPath("$.requestErrors", greaterThanOrEqualTo(0)))
                .andExpect(jsonPath("$.hitRate", greaterThanOrEqualTo(0.0)))
                .andExpect(jsonPath("$.geminiGenerationCalls", greaterThanOrEqualTo(0)))
                .andExpect(jsonPath("$.geminiEmbeddingCalls", greaterThanOrEqualTo(0)))
                .andExpect(jsonPath("$.qdrantOperations", greaterThanOrEqualTo(0)));
    }

    @Test
    @DisplayName("GET /api/metrics accurately reflects recorded cache telemetry")
    void getMetricsReflectsRecordedTelemetry() throws Exception {
        cacheMetrics.recordCacheHit(Duration.ofMillis(15));
        cacheMetrics.recordCacheMiss(CacheMetrics.MissReason.BELOW_THRESHOLD, Duration.ofMillis(30));

        mockMvc.perform(get("/api/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cacheHits", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.cacheMisses", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.totalRequests", greaterThanOrEqualTo(2)));
    }

    @Test
    @DisplayName("GET /api/metrics from allowed origin includes Access-Control-Allow-Origin header")
    void getMetricsFromAllowedOriginIncludesCorsHeader() throws Exception {
        mockMvc.perform(get("/api/metrics")
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN));
    }

    @Test
    @DisplayName("Preflight OPTIONS on /api/metrics from allowed origin returns 200 with CORS headers")
    void preflightOnMetricsFromAllowedOriginSucceeds() throws Exception {
        mockMvc.perform(options("/api/metrics")
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, containsString("GET")));
    }

    @Test
    @DisplayName("Preflight OPTIONS on /api/metrics from disallowed origin returns 403 Forbidden")
    void preflightOnMetricsFromDisallowedOriginFails() throws Exception {
        mockMvc.perform(options("/api/metrics")
                        .header(HttpHeaders.ORIGIN, DISALLOWED_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }
}
