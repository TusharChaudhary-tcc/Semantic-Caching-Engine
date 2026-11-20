package com.semanticcache.backend.filter;

import com.semanticcache.backend.repository.VectorRepository;
import com.semanticcache.backend.service.CacheDecisionService;
import com.semanticcache.backend.service.CacheRetrievalService;
import com.semanticcache.backend.service.GeminiProvider;
import com.semanticcache.backend.service.HealthService;
import com.semanticcache.backend.service.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class JwtAuthenticationFilterIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @MockitoBean
    private HealthService healthService;

    @MockitoBean
    private CacheRetrievalService cacheRetrievalService;

    @MockitoBean
    private CacheDecisionService cacheDecisionService;

    @MockitoBean
    private GeminiProvider geminiProvider;

    @MockitoBean
    private VectorRepository vectorRepository;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Anonymous request to public endpoint continues normally")
    void anonymousRequestToPublicEndpointSucceeds() throws Exception {
        when(healthService.getStatus()).thenAnswer(invocation -> {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            return auth == null ? "ANONYMOUS" : "UNEXPECTED_PRINCIPAL";
        });

        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("UP")))
                .andExpect(jsonPath("$.message", is("ANONYMOUS")));

        assertNull(SecurityContextHolder.getContext().getAuthentication(),
                "SecurityContext must be clear after request completes");
    }

    @Test
    @DisplayName("Request with valid JWT reaches application with expected authenticated UUID principal")
    void validJwtReachesEndpointWithAuthenticatedPrincipal() throws Exception {
        UUID userId = UUID.randomUUID();
        String validToken = jwtService.generateAccessToken(userId.toString());

        when(healthService.getStatus()).thenAnswer(invocation -> {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof UUID principalUuid && principalUuid.equals(userId)) {
                return "AUTHENTICATED:" + principalUuid;
            }
            return "AUTH_FAILURE";
        });

        mockMvc.perform(get("/api/health")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("UP")))
                .andExpect(jsonPath("$.message", is("AUTHENTICATED:" + userId)));

        assertNull(SecurityContextHolder.getContext().getAuthentication(),
                "SecurityContext must be clear after request completes");
    }

    @Test
    @DisplayName("Invalid or tampered JWT returns 401 with standard ErrorResponse JSON schema")
    void invalidJwtReturns401WithErrorResponse() throws Exception {
        UUID userId = UUID.randomUUID();
        String validToken = jwtService.generateAccessToken(userId.toString());
        String tamperedToken = validToken + "tampered";

        mockMvc.perform(get("/api/health")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tamperedToken))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\""))
                .andExpect(jsonPath("$.timestamp", notNullValue()))
                .andExpect(jsonPath("$.status", is(401)))
                .andExpect(jsonPath("$.error", is("Unauthorized")))
                .andExpect(jsonPath("$.message", is("Invalid or expired access token")))
                .andExpect(jsonPath("$.path", is("/api/health")));

        verify(healthService, never()).getStatus();
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    @DisplayName("Unsupported authorization scheme returns 401")
    void unsupportedAuthorizationSchemeReturns401() throws Exception {
        mockMvc.perform(get("/api/health")
                        .header(HttpHeaders.AUTHORIZATION, "Basic dXNlcjpwYXNz"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\""))
                .andExpect(jsonPath("$.status", is(401)))
                .andExpect(jsonPath("$.error", is("Unauthorized")))
                .andExpect(jsonPath("$.message", is("Unsupported or malformed authorization header")))
                .andExpect(jsonPath("$.path", is("/api/health")));

        verify(healthService, never()).getStatus();
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    @DisplayName("OPTIONS request passes through without requiring a JWT")
    void optionsRequestPassesThroughWithoutJwt() throws Exception {
        mockMvc.perform(options("/api/health"))
                .andExpect(status().isOk());

        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }
}
