package com.semanticcache.backend.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class CacheControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("POST /api/cache with valid prompt returns 200 OK")
    void validPromptReturnsOk() throws Exception {
        mockMvc.perform(post("/api/cache")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "prompt": "Explain neural networks"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer", is("This is a temporary mocked backend response for your prompt: 'Explain neural networks'")))
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
}
