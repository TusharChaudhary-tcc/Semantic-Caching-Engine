package com.semanticcache.backend.filter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.semanticcache.backend.exception.InvalidJwtException;
import com.semanticcache.backend.service.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Mock
    private JwtService jwtService;

    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        filter = new JwtAuthenticationFilter(jwtService, objectMapper);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("1. Missing Authorization header continues anonymously and invokes downstream chain")
    void missingAuthorizationHeaderContinuesAnonymously() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/cache");
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicBoolean chainInvoked = new AtomicBoolean(false);
        AtomicReference<Authentication> seenAuth = new AtomicReference<>();

        FilterChain chain = (req, res) -> {
            chainInvoked.set(true);
            seenAuth.set(SecurityContextHolder.getContext().getAuthentication());
        };

        filter.doFilter(request, response, chain);

        assertTrue(chainInvoked.get(), "Downstream filter chain must be invoked");
        assertNull(seenAuth.get(), "Security context must remain anonymous");
        assertEquals(200, response.getStatus());
        verify(jwtService, never()).validateAccessToken(anyString());
    }

    @Test
    @DisplayName("2. Empty header returns 401")
    void emptyAuthorizationHeaderReturns401() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/cache");
        request.addHeader(HttpHeaders.AUTHORIZATION, "");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        assertEquals("Bearer error=\"invalid_token\"", response.getHeader(HttpHeaders.WWW_AUTHENTICATE));
        assertNull(chain.getRequest(), "Chain must not be called");
        assertErrorResponseSchema(response.getContentAsString(), "/api/cache");
        verify(jwtService, never()).validateAccessToken(anyString());
    }

    @Test
    @DisplayName("3. Whitespace-only header returns 401")
    void whitespaceOnlyAuthorizationHeaderReturns401() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/cache");
        request.addHeader(HttpHeaders.AUTHORIZATION, "    ");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        assertEquals("Bearer error=\"invalid_token\"", response.getHeader(HttpHeaders.WWW_AUTHENTICATE));
        assertNull(chain.getRequest(), "Chain must not be called");
        assertErrorResponseSchema(response.getContentAsString(), "/api/cache");
        verify(jwtService, never()).validateAccessToken(anyString());
    }

    @Test
    @DisplayName("4. Valid Bearer token authenticates the expected UUID in SecurityContext")
    void validBearerTokenAuthenticatesExpectedUuid() throws ServletException, IOException {
        UUID expectedUuid = UUID.randomUUID();
        String rawToken = "valid.jwt.token";

        when(jwtService.validateAccessToken(rawToken)).thenReturn(expectedUuid);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/cache");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + rawToken);
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicBoolean chainInvoked = new AtomicBoolean(false);
        AtomicReference<Authentication> observedAuth = new AtomicReference<>();

        FilterChain chain = (req, res) -> {
            chainInvoked.set(true);
            observedAuth.set(SecurityContextHolder.getContext().getAuthentication());
        };

        filter.doFilter(request, response, chain);

        assertTrue(chainInvoked.get());
        assertEquals(200, response.getStatus());

        Authentication auth = observedAuth.get();
        assertNotNull(auth, "Authentication must be set in SecurityContext during chain execution");
        assertEquals(expectedUuid, auth.getPrincipal());
        assertNull(auth.getCredentials());
        assertTrue(auth.getAuthorities().isEmpty());
        assertTrue(auth.isAuthenticated());
    }

    @Test
    @DisplayName("5. Lowercase bearer scheme is accepted")
    void lowercaseBearerSchemeIsAccepted() throws ServletException, IOException {
        UUID expectedUuid = UUID.randomUUID();
        String rawToken = "lowercase.jwt.token";

        when(jwtService.validateAccessToken(rawToken)).thenReturn(expectedUuid);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/cache");
        request.addHeader(HttpHeaders.AUTHORIZATION, "bearer " + rawToken);
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<Authentication> observedAuth = new AtomicReference<>();
        FilterChain chain = (req, res) -> observedAuth.set(SecurityContextHolder.getContext().getAuthentication());

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertNotNull(observedAuth.get());
        assertEquals(expectedUuid, observedAuth.get().getPrincipal());
        verify(jwtService).validateAccessToken(rawToken);
    }

    @Test
    @DisplayName("6. Mixed-case Bearer scheme is accepted")
    void mixedCaseBearerSchemeIsAccepted() throws ServletException, IOException {
        UUID expectedUuid = UUID.randomUUID();
        String rawToken = "mixedcase.jwt.token";

        when(jwtService.validateAccessToken(rawToken)).thenReturn(expectedUuid);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/cache");
        request.addHeader(HttpHeaders.AUTHORIZATION, "BeArEr " + rawToken);
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<Authentication> observedAuth = new AtomicReference<>();
        FilterChain chain = (req, res) -> observedAuth.set(SecurityContextHolder.getContext().getAuthentication());

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertNotNull(observedAuth.get());
        assertEquals(expectedUuid, observedAuth.get().getPrincipal());
        verify(jwtService).validateAccessToken(rawToken);
    }

    @Test
    @DisplayName("7. Bare Bearer without token returns 401")
    void bareBearerWithoutTokenReturns401() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/cache");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        assertEquals("Bearer error=\"invalid_token\"", response.getHeader(HttpHeaders.WWW_AUTHENTICATE));
        assertNull(chain.getRequest());
        verify(jwtService, never()).validateAccessToken(anyString());
    }

    @Test
    @DisplayName("8. 'Bearer ' with blank token returns 401")
    void bearerWithEmptyTokenReturns401() throws ServletException, IOException {
        when(jwtService.validateAccessToken("")).thenThrow(new InvalidJwtException("Token must not be null or blank"));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/cache");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer ");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        assertEquals("Bearer error=\"invalid_token\"", response.getHeader(HttpHeaders.WWW_AUTHENTICATE));
        assertNull(chain.getRequest());
        verify(jwtService).validateAccessToken("");
    }

    @Test
    @DisplayName("9. 'Bearer    ' with whitespace token returns 401")
    void bearerWithWhitespaceTokenReturns401() throws ServletException, IOException {
        when(jwtService.validateAccessToken("   ")).thenThrow(new InvalidJwtException("Token must not be null or blank"));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/cache");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer    ");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        assertEquals("Bearer error=\"invalid_token\"", response.getHeader(HttpHeaders.WWW_AUTHENTICATE));
        assertNull(chain.getRequest());
        verify(jwtService).validateAccessToken("   ");
    }

    @Test
    @DisplayName("10. Basic authentication returns 401")
    void basicAuthenticationReturns401() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/cache");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Basic dXNlcjpwYXNz");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        assertEquals("Bearer error=\"invalid_token\"", response.getHeader(HttpHeaders.WWW_AUTHENTICATE));
        assertNull(chain.getRequest());
        verify(jwtService, never()).validateAccessToken(anyString());
    }

    @Test
    @DisplayName("11. Unsupported authorization scheme returns 401")
    void unsupportedAuthorizationSchemeReturns401() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/cache");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Digest username=\"Mufasa\"");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        assertEquals("Bearer error=\"invalid_token\"", response.getHeader(HttpHeaders.WWW_AUTHENTICATE));
        assertNull(chain.getRequest());
        verify(jwtService, never()).validateAccessToken(anyString());
    }

    @Test
    @DisplayName("12. Invalid JWT returns 401")
    void invalidJwtReturns401() throws ServletException, IOException {
        when(jwtService.validateAccessToken("tampered.token"))
                .thenThrow(new InvalidJwtException("JWT validation failed: SignatureException"));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/cache");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer tampered.token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        assertNull(chain.getRequest());
        assertErrorResponseSchema(response.getContentAsString(), "/api/cache");
    }

    @Test
    @DisplayName("13. Expired JWT returns 401")
    void expiredJwtReturns401() throws ServletException, IOException {
        when(jwtService.validateAccessToken("expired.token"))
                .thenThrow(new InvalidJwtException("JWT validation failed: ExpiredJwtException"));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/cache");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer expired.token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        assertNull(chain.getRequest());
        assertErrorResponseSchema(response.getContentAsString(), "/api/cache");
    }

    @Test
    @DisplayName("14. Downstream filter chain is not called for invalid credentials")
    void downstreamChainNotCalledForInvalidCredentials() throws ServletException, IOException {
        when(jwtService.validateAccessToken("bad.token"))
                .thenThrow(new InvalidJwtException("Malformed or unparseable JWT"));

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/cache");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer bad.token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicBoolean chainInvoked = new AtomicBoolean(false);
        FilterChain chain = (req, res) -> chainInvoked.set(true);

        filter.doFilter(request, response, chain);

        assertFalse(chainInvoked.get(), "Downstream chain must never be called on invalid token");
        assertEquals(401, response.getStatus());
    }

    @Test
    @DisplayName("15. SecurityContext is cleared after a successful authenticated request")
    void securityContextClearedAfterSuccessfulAuthenticatedRequest() throws ServletException, IOException {
        UUID userId = UUID.randomUUID();
        when(jwtService.validateAccessToken("valid.token")).thenReturn(userId);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/cache");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer valid.token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertNull(SecurityContextHolder.getContext().getAuthentication(),
                "SecurityContext must be cleared after filter completion");
    }

    @Test
    @DisplayName("16. SecurityContext is cleared after an anonymous request")
    void securityContextClearedAfterAnonymousRequest() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/health");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertNull(SecurityContextHolder.getContext().getAuthentication(),
                "SecurityContext must be empty after anonymous request");
    }

    @Test
    @DisplayName("17. SecurityContext is cleared after an OPTIONS request")
    void securityContextClearedAfterOptionsRequest() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/cache");
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicBoolean chainInvoked = new AtomicBoolean(false);
        FilterChain chain = (req, res) -> chainInvoked.set(true);

        filter.doFilter(request, response, chain);

        assertTrue(chainInvoked.get(), "OPTIONS requests must pass to downstream chain");
        assertNull(SecurityContextHolder.getContext().getAuthentication(),
                "SecurityContext must be empty after OPTIONS request");
        verify(jwtService, never()).validateAccessToken(anyString());
    }

    @Test
    @DisplayName("18. SecurityContext is cleared if the downstream chain throws an exception")
    void securityContextClearedWhenDownstreamChainThrows() {
        UUID userId = UUID.randomUUID();
        when(jwtService.validateAccessToken("valid.token")).thenReturn(userId);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/cache");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer valid.token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain throwingChain = (req, res) -> {
            assertNotNull(SecurityContextHolder.getContext().getAuthentication(),
                    "Context must be authenticated during chain execution");
            throw new RuntimeException("Simulated controller failure");
        };

        assertThrows(RuntimeException.class, () -> filter.doFilter(request, response, throwingChain));

        assertNull(SecurityContextHolder.getContext().getAuthentication(),
                "SecurityContext must be cleared even when downstream chain throws exception");
    }

    @Test
    @DisplayName("19. No stale pre-existing authentication survives into a new request")
    void noStalePreExistingAuthenticationSurvivesIntoNewRequest() throws ServletException, IOException {
        // Artificially pollute SecurityContext prior to filter invocation
        SecurityContext staleContext = SecurityContextHolder.createEmptyContext();
        staleContext.setAuthentication(new UsernamePasswordAuthenticationToken(UUID.randomUUID(), null, Collections.emptyList()));
        SecurityContextHolder.setContext(staleContext);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/health");
        // No Authorization header
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<Authentication> contextInsideChain = new AtomicReference<>();
        FilterChain chain = (req, res) -> contextInsideChain.set(SecurityContextHolder.getContext().getAuthentication());

        filter.doFilter(request, response, chain);

        assertNull(contextInsideChain.get(), "Pre-existing context must be cleared at start of request");
        assertNull(SecurityContextHolder.getContext().getAuthentication(), "Context must remain clear after completion");
    }

    @Test
    @DisplayName("20. The response does not expose the token or secret")
    void responseDoesNotExposeTokenOrSecret() throws ServletException, IOException {
        String sensitiveToken = "super-secret-raw-jwt-content-that-must-never-leak";
        when(jwtService.validateAccessToken(sensitiveToken))
                .thenThrow(new InvalidJwtException("Malformed or unparseable JWT"));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/cache");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + sensitiveToken);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        String responseBody = response.getContentAsString();
        assertFalse(responseBody.contains(sensitiveToken), "Response body must not contain the raw token");
        assertFalse(responseBody.contains("secret"), "Response body must not contain secret details");
        assertFalse(responseBody.contains("Exception"), "Response body must not expose raw exception names");
    }

    @Test
    @DisplayName("Default constructor instantiates filter with internal ObjectMapper")
    void defaultConstructorInitializesSuccessfully() {
        JwtAuthenticationFilter defaultFilter = new JwtAuthenticationFilter(jwtService);
        assertNotNull(defaultFilter);
    }

    private void assertErrorResponseSchema(String responseJson, String expectedPath) throws IOException {
        JsonNode root = objectMapper.readTree(responseJson);
        assertTrue(root.hasNonNull("timestamp"), "Must have timestamp field");
        assertEquals(401, root.get("status").asInt(), "Status must be 401");
        assertEquals("Unauthorized", root.get("error").asText(), "Error must be 'Unauthorized'");
        assertTrue(root.hasNonNull("message"), "Must have non-null message field");
        assertEquals(expectedPath, root.get("path").asText(), "Path must match expected");
    }
}
