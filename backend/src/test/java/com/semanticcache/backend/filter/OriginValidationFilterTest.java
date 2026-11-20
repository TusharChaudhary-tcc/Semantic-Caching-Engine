package com.semanticcache.backend.filter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OriginValidationFilterTest {

    private static final String TRUSTED_ORIGIN = "http://localhost:5173";
    private static final String XML_HTTP_REQUEST = "XMLHttpRequest";

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private OriginValidationFilter filter;

    @BeforeEach
    void setUp() {
        filter = new OriginValidationFilter(new String[]{TRUSTED_ORIGIN}, objectMapper);
    }

    @Nested
    @DisplayName("Endpoint & Method Matching")
    class EndpointAndMethodMatchingTests {

        @Test
        @DisplayName("OPTIONS preflight requests immediately bypass filter")
        void optionsPassThrough() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/auth/refresh");
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicBoolean downstreamCalled = new AtomicBoolean(false);

            filter.doFilter(request, response, (req, res) -> downstreamCalled.set(true));

            assertTrue(downstreamCalled.get());
            assertEquals(200, response.getStatus());
        }

        @Test
        @DisplayName("GET /api/auth/refresh bypasses validation")
        void getRefreshBypassesFilter() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/auth/refresh");
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicBoolean downstreamCalled = new AtomicBoolean(false);

            filter.doFilter(request, response, (req, res) -> downstreamCalled.set(true));

            assertTrue(downstreamCalled.get());
        }

        @Test
        @DisplayName("POST /api/auth/register bypasses validation")
        void registerBypassesFilter() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/register");
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicBoolean downstreamCalled = new AtomicBoolean(false);

            filter.doFilter(request, response, (req, res) -> downstreamCalled.set(true));

            assertTrue(downstreamCalled.get());
        }

        @Test
        @DisplayName("POST /api/auth/login bypasses validation")
        void loginBypassesFilter() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicBoolean downstreamCalled = new AtomicBoolean(false);

            filter.doFilter(request, response, (req, res) -> downstreamCalled.set(true));

            assertTrue(downstreamCalled.get());
        }

        @Test
        @DisplayName("POST /api/cache bypasses validation")
        void cacheBypassesFilter() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/cache");
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicBoolean downstreamCalled = new AtomicBoolean(false);

            filter.doFilter(request, response, (req, res) -> downstreamCalled.set(true));

            assertTrue(downstreamCalled.get());
        }

        @Test
        @DisplayName("GET /api/health bypasses validation")
        void healthBypassesFilter() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/health");
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicBoolean downstreamCalled = new AtomicBoolean(false);

            filter.doFilter(request, response, (req, res) -> downstreamCalled.set(true));

            assertTrue(downstreamCalled.get());
        }
    }

    @Nested
    @DisplayName("Origin Header Validation")
    class OriginHeaderValidationTests {

        @Test
        @DisplayName("Missing both Origin and Referer returns 403 ORIGIN_REQUIRED")
        void missingOriginAndReferer() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/refresh");
            request.addHeader("X-Requested-With", XML_HTTP_REQUEST);
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicBoolean downstreamCalled = new AtomicBoolean(false);

            filter.doFilter(request, response, (req, res) -> downstreamCalled.set(true));

            assertFalse(downstreamCalled.get());
            assertEquals(403, response.getStatus());

            JsonNode json = objectMapper.readTree(response.getContentAsString());
            assertEquals(403, json.get("status").asInt());
            assertEquals("ORIGIN_REQUIRED", json.get("error").asText());
            assertEquals("Origin or Referer header is required", json.get("message").asText());
            assertEquals("/api/auth/refresh", json.get("path").asText());
            assertNotNull(json.get("timestamp"));
        }

        @Test
        @DisplayName("Blank Origin returns 403 UNTRUSTED_ORIGIN")
        void blankOriginReturnsUntrustedOrigin() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/logout");
            request.addHeader(HttpHeaders.ORIGIN, "   ");
            request.addHeader("X-Requested-With", XML_HTTP_REQUEST);
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, new MockFilterChain());

            assertEquals(403, response.getStatus());
            JsonNode json = objectMapper.readTree(response.getContentAsString());
            assertEquals("UNTRUSTED_ORIGIN", json.get("error").asText());
        }

        @Test
        @DisplayName("Blank Origin with trusted Referer returns 403 UNTRUSTED_ORIGIN (never falls back)")
        void blankOriginWithTrustedRefererRejected() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/refresh");
            request.addHeader(HttpHeaders.ORIGIN, "   ");
            request.addHeader(HttpHeaders.REFERER, "http://localhost:5173/dashboard");
            request.addHeader("X-Requested-With", XML_HTTP_REQUEST);
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicBoolean downstreamCalled = new AtomicBoolean(false);

            filter.doFilter(request, response, (req, res) -> downstreamCalled.set(true));

            assertFalse(downstreamCalled.get());
            assertEquals(403, response.getStatus());
            JsonNode json = objectMapper.readTree(response.getContentAsString());
            assertEquals("UNTRUSTED_ORIGIN", json.get("error").asText());
        }

        @Test
        @DisplayName("Literal 'null' Origin returns 403 UNTRUSTED_ORIGIN")
        void originLiteralNull() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/refresh");
            request.addHeader(HttpHeaders.ORIGIN, "null");
            request.addHeader("X-Requested-With", XML_HTTP_REQUEST);
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, new MockFilterChain());

            assertEquals(403, response.getStatus());
            JsonNode json = objectMapper.readTree(response.getContentAsString());
            assertEquals("UNTRUSTED_ORIGIN", json.get("error").asText());
            assertEquals("Untrusted or malformed origin", json.get("message").asText());
        }

        @Test
        @DisplayName("Untrusted Origin returns 403 UNTRUSTED_ORIGIN")
        void untrustedOrigin() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/refresh");
            request.addHeader(HttpHeaders.ORIGIN, "http://malicious-site.com");
            request.addHeader("X-Requested-With", XML_HTTP_REQUEST);
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, new MockFilterChain());

            assertEquals(403, response.getStatus());
            JsonNode json = objectMapper.readTree(response.getContentAsString());
            assertEquals("UNTRUSTED_ORIGIN", json.get("error").asText());
        }

        @Test
        @DisplayName("Origin with untrusted value and trusted Referer rejects immediately without falling back")
        void untrustedOriginWithTrustedRefererNeverFallsBack() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/refresh");
            request.addHeader(HttpHeaders.ORIGIN, "http://malicious-site.com");
            request.addHeader(HttpHeaders.REFERER, "http://localhost:5173/dashboard");
            request.addHeader("X-Requested-With", XML_HTTP_REQUEST);
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicBoolean downstreamCalled = new AtomicBoolean(false);

            filter.doFilter(request, response, (req, res) -> downstreamCalled.set(true));

            assertFalse(downstreamCalled.get());
            assertEquals(403, response.getStatus());
            JsonNode json = objectMapper.readTree(response.getContentAsString());
            assertEquals("UNTRUSTED_ORIGIN", json.get("error").asText());
        }

        @Test
        @DisplayName("Origin containing a trailing slash returns 403 UNTRUSTED_ORIGIN")
        void originWithTrailingSlashRejected() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/refresh");
            request.addHeader(HttpHeaders.ORIGIN, "http://localhost:5173/");
            request.addHeader("X-Requested-With", XML_HTTP_REQUEST);
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, new MockFilterChain());

            assertEquals(403, response.getStatus());
            JsonNode json = objectMapper.readTree(response.getContentAsString());
            assertEquals("UNTRUSTED_ORIGIN", json.get("error").asText());
        }

        @Test
        @DisplayName("Origin containing a path returns 403 UNTRUSTED_ORIGIN")
        void originWithPathRejected() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/refresh");
            request.addHeader(HttpHeaders.ORIGIN, "http://localhost:5173/api/auth/refresh");
            request.addHeader("X-Requested-With", XML_HTTP_REQUEST);
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, new MockFilterChain());

            assertEquals(403, response.getStatus());
            JsonNode json = objectMapper.readTree(response.getContentAsString());
            assertEquals("UNTRUSTED_ORIGIN", json.get("error").asText());
        }

        @Test
        @DisplayName("Origin containing a query string returns 403 UNTRUSTED_ORIGIN")
        void originWithQueryRejected() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/refresh");
            request.addHeader(HttpHeaders.ORIGIN, "http://localhost:5173?redirect=dashboard");
            request.addHeader("X-Requested-With", XML_HTTP_REQUEST);
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, new MockFilterChain());

            assertEquals(403, response.getStatus());
            JsonNode json = objectMapper.readTree(response.getContentAsString());
            assertEquals("UNTRUSTED_ORIGIN", json.get("error").asText());
        }

        @Test
        @DisplayName("Origin containing a fragment returns 403 UNTRUSTED_ORIGIN")
        void originWithFragmentRejected() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/refresh");
            request.addHeader(HttpHeaders.ORIGIN, "http://localhost:5173#section");
            request.addHeader("X-Requested-With", XML_HTTP_REQUEST);
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, new MockFilterChain());

            assertEquals(403, response.getStatus());
            JsonNode json = objectMapper.readTree(response.getContentAsString());
            assertEquals("UNTRUSTED_ORIGIN", json.get("error").asText());
        }

        @Test
        @DisplayName("Malformed Origin URL returns 403 UNTRUSTED_ORIGIN")
        void malformedOrigin() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/refresh");
            request.addHeader(HttpHeaders.ORIGIN, "http://[invalid-ipv6-address");
            request.addHeader("X-Requested-With", XML_HTTP_REQUEST);
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, new MockFilterChain());

            assertEquals(403, response.getStatus());
            JsonNode json = objectMapper.readTree(response.getContentAsString());
            assertEquals("UNTRUSTED_ORIGIN", json.get("error").asText());
        }

        @Test
        @DisplayName("Origin with userinfo returns 403 UNTRUSTED_ORIGIN")
        void originWithUserInfo() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/refresh");
            request.addHeader(HttpHeaders.ORIGIN, "http://admin:secret@localhost:5173");
            request.addHeader("X-Requested-With", XML_HTTP_REQUEST);
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, new MockFilterChain());

            assertEquals(403, response.getStatus());
            JsonNode json = objectMapper.readTree(response.getContentAsString());
            assertEquals("UNTRUSTED_ORIGIN", json.get("error").asText());
        }

        @Test
        @DisplayName("Multiple Origin headers return 403 UNTRUSTED_ORIGIN")
        void multipleOriginHeaders() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/refresh");
            request.addHeader(HttpHeaders.ORIGIN, TRUSTED_ORIGIN);
            request.addHeader(HttpHeaders.ORIGIN, "http://attacker.com");
            request.addHeader("X-Requested-With", XML_HTTP_REQUEST);
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, new MockFilterChain());

            assertEquals(403, response.getStatus());
            JsonNode json = objectMapper.readTree(response.getContentAsString());
            assertEquals("UNTRUSTED_ORIGIN", json.get("error").asText());
        }

        @Test
        @DisplayName("Hostname suffix attack (e.g. localhost:5173.attacker.com) returns 403 UNTRUSTED_ORIGIN")
        void hostnameSuffixAttack() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/refresh");
            request.addHeader(HttpHeaders.ORIGIN, "http://localhost:5173.attacker.com");
            request.addHeader("X-Requested-With", XML_HTTP_REQUEST);
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, new MockFilterChain());

            assertEquals(403, response.getStatus());
            JsonNode json = objectMapper.readTree(response.getContentAsString());
            assertEquals("UNTRUSTED_ORIGIN", json.get("error").asText());
        }

        @Test
        @DisplayName("Trusted Origin succeeds on /api/auth/refresh")
        void trustedOriginOnRefresh() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/refresh");
            request.addHeader(HttpHeaders.ORIGIN, TRUSTED_ORIGIN);
            request.addHeader("X-Requested-With", XML_HTTP_REQUEST);
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicBoolean downstreamCalled = new AtomicBoolean(false);

            filter.doFilter(request, response, (req, res) -> downstreamCalled.set(true));

            assertTrue(downstreamCalled.get());
        }

        @Test
        @DisplayName("Trusted Origin succeeds on /api/auth/logout")
        void trustedOriginOnLogout() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/logout");
            request.addHeader(HttpHeaders.ORIGIN, TRUSTED_ORIGIN);
            request.addHeader("X-Requested-With", XML_HTTP_REQUEST);
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicBoolean downstreamCalled = new AtomicBoolean(false);

            filter.doFilter(request, response, (req, res) -> downstreamCalled.set(true));

            assertTrue(downstreamCalled.get());
        }
    }

    @Nested
    @DisplayName("Referer Fallback Validation")
    class RefererFallbackValidationTests {

        @Test
        @DisplayName("Trusted Referer fallback succeeds when Origin is absent")
        void trustedRefererFallbackSucceeds() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/refresh");
            request.addHeader(HttpHeaders.REFERER, "http://localhost:5173/dashboard?tab=profile#section");
            request.addHeader("X-Requested-With", XML_HTTP_REQUEST);
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicBoolean downstreamCalled = new AtomicBoolean(false);

            filter.doFilter(request, response, (req, res) -> downstreamCalled.set(true));

            assertTrue(downstreamCalled.get());
        }

        @Test
        @DisplayName("Untrusted Referer returns 403 UNTRUSTED_ORIGIN when Origin is absent")
        void untrustedRefererFallback() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/refresh");
            request.addHeader(HttpHeaders.REFERER, "http://evil-site.com/login");
            request.addHeader("X-Requested-With", XML_HTTP_REQUEST);
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, new MockFilterChain());

            assertEquals(403, response.getStatus());
            JsonNode json = objectMapper.readTree(response.getContentAsString());
            assertEquals("UNTRUSTED_ORIGIN", json.get("error").asText());
        }

        @Test
        @DisplayName("Malformed Referer URL returns 403 UNTRUSTED_ORIGIN")
        void malformedReferer() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/refresh");
            request.addHeader(HttpHeaders.REFERER, "not-a-valid-uri://///");
            request.addHeader("X-Requested-With", XML_HTTP_REQUEST);
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, new MockFilterChain());

            assertEquals(403, response.getStatus());
            JsonNode json = objectMapper.readTree(response.getContentAsString());
            assertEquals("UNTRUSTED_ORIGIN", json.get("error").asText());
        }

        @Test
        @DisplayName("Referer with userinfo returns 403 UNTRUSTED_ORIGIN")
        void refererWithUserInfo() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/refresh");
            request.addHeader(HttpHeaders.REFERER, "http://admin:pass@localhost:5173/login");
            request.addHeader("X-Requested-With", XML_HTTP_REQUEST);
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, new MockFilterChain());

            assertEquals(403, response.getStatus());
            JsonNode json = objectMapper.readTree(response.getContentAsString());
            assertEquals("UNTRUSTED_ORIGIN", json.get("error").asText());
        }

        @Test
        @DisplayName("Non-http/https Referer scheme returns 403 UNTRUSTED_ORIGIN")
        void nonHttpRefererScheme() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/refresh");
            request.addHeader(HttpHeaders.REFERER, "javascript:alert(1)");
            request.addHeader("X-Requested-With", XML_HTTP_REQUEST);
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, new MockFilterChain());

            assertEquals(403, response.getStatus());
            JsonNode json = objectMapper.readTree(response.getContentAsString());
            assertEquals("UNTRUSTED_ORIGIN", json.get("error").asText());
        }
    }

    @Nested
    @DisplayName("X-Requested-With CSRF Validation")
    class XRequestedWithValidationTests {

        @Test
        @DisplayName("Missing X-Requested-With returns 403 CSRF_VALIDATION_FAILED")
        void missingXRequestedWith() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/refresh");
            request.addHeader(HttpHeaders.ORIGIN, TRUSTED_ORIGIN);
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicBoolean downstreamCalled = new AtomicBoolean(false);

            filter.doFilter(request, response, (req, res) -> downstreamCalled.set(true));

            assertFalse(downstreamCalled.get());
            assertEquals(403, response.getStatus());

            JsonNode json = objectMapper.readTree(response.getContentAsString());
            assertEquals(403, json.get("status").asInt());
            assertEquals("CSRF_VALIDATION_FAILED", json.get("error").asText());
            assertEquals("CSRF validation failed: missing or invalid X-Requested-With header", json.get("message").asText());
            assertEquals("/api/auth/refresh", json.get("path").asText());
        }

        @Test
        @DisplayName("Invalid X-Requested-With returns 403 CSRF_VALIDATION_FAILED")
        void invalidXRequestedWith() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/refresh");
            request.addHeader(HttpHeaders.ORIGIN, TRUSTED_ORIGIN);
            request.addHeader("X-Requested-With", "FetchAPI");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, new MockFilterChain());

            assertEquals(403, response.getStatus());
            JsonNode json = objectMapper.readTree(response.getContentAsString());
            assertEquals("CSRF_VALIDATION_FAILED", json.get("error").asText());
        }

        @Test
        @DisplayName("Case-insensitive and trimmed X-Requested-With (xmlhttprequest) succeeds")
        void caseInsensitiveXRequestedWith() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/refresh");
            request.addHeader(HttpHeaders.ORIGIN, TRUSTED_ORIGIN);
            request.addHeader("X-Requested-With", "  xmlhttprequest  ");
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicBoolean downstreamCalled = new AtomicBoolean(false);

            filter.doFilter(request, response, (req, res) -> downstreamCalled.set(true));

            assertTrue(downstreamCalled.get());
        }
    }

    @Nested
    @DisplayName("Normalization and Edge Cases")
    class NormalizationEdgeCasesTests {

        @Test
        @DisplayName("Default port 80 normalization matches origin without port")
        void defaultHttpPortNormalization() {
            OriginValidationFilter httpFilter = new OriginValidationFilter(new String[]{"http://example.com"});
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/refresh");
            request.addHeader(HttpHeaders.ORIGIN, "http://example.com:80");
            request.addHeader("X-Requested-With", XML_HTTP_REQUEST);
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicBoolean downstreamCalled = new AtomicBoolean(false);

            try {
                httpFilter.doFilter(request, response, (req, res) -> downstreamCalled.set(true));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

            assertTrue(downstreamCalled.get());
        }

        @Test
        @DisplayName("Default port 443 normalization matches https origin without port")
        void defaultHttpsPortNormalization() {
            OriginValidationFilter httpsFilter = new OriginValidationFilter(new String[]{"https://secure.example.com"});
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/refresh");
            request.addHeader(HttpHeaders.ORIGIN, "https://secure.example.com:443");
            request.addHeader("X-Requested-With", XML_HTTP_REQUEST);
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicBoolean downstreamCalled = new AtomicBoolean(false);

            try {
                httpsFilter.doFilter(request, response, (req, res) -> downstreamCalled.set(true));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

            assertTrue(downstreamCalled.get());
        }

        @Test
        @DisplayName("Wildcard origin in configuration is ignored and never trusted")
        void wildcardOriginIgnored() {
            OriginValidationFilter wildcardFilter = new OriginValidationFilter(new String[]{"*"});
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/refresh");
            request.addHeader(HttpHeaders.ORIGIN, "http://attacker.com");
            request.addHeader("X-Requested-With", XML_HTTP_REQUEST);
            MockHttpServletResponse response = new MockHttpServletResponse();

            try {
                wildcardFilter.doFilter(request, response, new MockFilterChain());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

            assertEquals(403, response.getStatus());
        }
    }
}
