package com.semanticcache.backend.controller;

import com.semanticcache.backend.dto.CacheMetricsResponse;
import com.semanticcache.backend.telemetry.CacheMetrics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MetricsControllerTest {

    @Test
    @DisplayName("Constructor throws NullPointerException when CacheMetrics is null")
    void constructorThrowsOnNullMetrics() {
        NullPointerException ex = assertThrows(NullPointerException.class, () -> new MetricsController(null));
        assertEquals("cacheMetrics must not be null", ex.getMessage());
    }

    @Test
    @DisplayName("getMetrics returns CacheMetricsResponse from CacheMetrics.getSnapshot()")
    void getMetricsDelegatesToCacheMetrics() {
        CacheMetrics cacheMetrics = mock(CacheMetrics.class);
        CacheMetricsResponse expected = new CacheMetricsResponse(
                50L,
                40L,
                10L,
                1L,
                0.80,
                10L,
                50L,
                60L
        );
        when(cacheMetrics.getSnapshot()).thenReturn(expected);

        MetricsController controller = new MetricsController(cacheMetrics);
        CacheMetricsResponse actual = controller.getMetrics();

        assertNotNull(actual);
        assertEquals(expected, actual);
        verify(cacheMetrics).getSnapshot();
    }

    @Test
    @DisplayName("GET /api/metrics returns HTTP 200 with JSON payload matching snapshot")
    void getMetricsEndpointReturnsOkWithJson() throws Exception {
        CacheMetrics cacheMetrics = mock(CacheMetrics.class);
        CacheMetricsResponse snapshot = new CacheMetricsResponse(
                100L,
                75L,
                25L,
                2L,
                0.75,
                25L,
                100L,
                125L
        );
        when(cacheMetrics.getSnapshot()).thenReturn(snapshot);

        MetricsController controller = new MetricsController(cacheMetrics);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(get("/api/metrics"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.totalRequests").value(100))
                .andExpect(jsonPath("$.cacheHits").value(75))
                .andExpect(jsonPath("$.cacheMisses").value(25))
                .andExpect(jsonPath("$.requestErrors").value(2))
                .andExpect(jsonPath("$.hitRate").value(0.75))
                .andExpect(jsonPath("$.geminiGenerationCalls").value(25))
                .andExpect(jsonPath("$.geminiEmbeddingCalls").value(100))
                .andExpect(jsonPath("$.qdrantOperations").value(125));

        verify(cacheMetrics).getSnapshot();
    }
}
