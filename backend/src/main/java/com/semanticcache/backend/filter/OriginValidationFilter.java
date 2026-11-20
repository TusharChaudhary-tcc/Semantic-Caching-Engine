package com.semanticcache.backend.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.semanticcache.backend.dto.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Servlet filter that enforces strict Origin/Referer validation and CSRF protection
 * (via X-Requested-With header) on sensitive state-changing authentication endpoints.
 * <p>
 * Specifically protects:
 * <ul>
 *     <li>POST /api/auth/refresh</li>
 *     <li>POST /api/auth/logout</li>
 * </ul>
 * All other paths and HTTP methods (including OPTIONS preflight requests) bypass this filter.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class OriginValidationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(OriginValidationFilter.class);

    private static final String REQUIRED_X_REQUESTED_WITH = "XMLHttpRequest";

    public static final String ERROR_CODE_ORIGIN_REQUIRED = "ORIGIN_REQUIRED";
    public static final String ERROR_CODE_UNTRUSTED_ORIGIN = "UNTRUSTED_ORIGIN";
    public static final String ERROR_CODE_CSRF_VALIDATION_FAILED = "CSRF_VALIDATION_FAILED";

    private final Set<String> trustedOrigins;
    private final ObjectMapper objectMapper;

    @Autowired
    public OriginValidationFilter(
            @Value("${app.cors.allowed-origins:http://localhost:5173}") String[] allowedOrigins,
            @Autowired(required = false) ObjectMapper objectMapper
    ) {
        this.trustedOrigins = parseAndNormalizeAllowedOrigins(allowedOrigins);
        this.objectMapper = objectMapper != null ? objectMapper : createDefaultObjectMapper();
    }

    public OriginValidationFilter(String[] allowedOrigins) {
        this(allowedOrigins, null);
    }

    private static ObjectMapper createDefaultObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.findAndRegisterModules();
        return mapper;
    }

    private static Set<String> parseAndNormalizeAllowedOrigins(String[] origins) {
        if (origins == null || origins.length == 0) {
            return Collections.emptySet();
        }
        Set<String> set = new HashSet<>();
        for (String origin : origins) {
            if (origin == null || origin.trim().isEmpty() || "*".equals(origin.trim())) {
                continue;
            }
            String normalized = normalizeOrigin(origin.trim());
            if (normalized != null) {
                set.add(normalized);
            }
        }
        return Collections.unmodifiableSet(set);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        // Pass-through OPTIONS requests for CORS preflight
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        // Only protect POST /api/auth/refresh and POST /api/auth/logout
        if (!isProtectedEndpoint(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        // 1. Origin / Referer validation
        String originValidationResult = validateOriginAndReferer(request);
        if (originValidationResult != null) {
            String message = ERROR_CODE_ORIGIN_REQUIRED.equals(originValidationResult)
                    ? "Origin or Referer header is required"
                    : "Untrusted or malformed origin";
            log.warn("Origin validation failed on path [{}]: code [{}]", request.getRequestURI(), originValidationResult);
            writeForbiddenResponse(response, request.getRequestURI(), originValidationResult, message);
            return;
        }

        // 2. X-Requested-With validation
        String xRequestedWith = request.getHeader("X-Requested-With");
        if (xRequestedWith == null || !REQUIRED_X_REQUESTED_WITH.equalsIgnoreCase(xRequestedWith.trim())) {
            log.warn("CSRF validation failed on path [{}]: missing or invalid X-Requested-With", request.getRequestURI());
            writeForbiddenResponse(
                    response,
                    request.getRequestURI(),
                    ERROR_CODE_CSRF_VALIDATION_FAILED,
                    "CSRF validation failed: missing or invalid X-Requested-With header"
            );
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean isProtectedEndpoint(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        String path = request.getRequestURI();
        if (path == null) {
            return false;
        }
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return "/api/auth/refresh".equals(path) || "/api/auth/logout".equals(path);
    }

    /**
     * Validates Origin and Referer fallback headers.
     *
     * @return null if validation succeeds, or the error code (ORIGIN_REQUIRED, UNTRUSTED_ORIGIN) if it fails.
     */
    private String validateOriginAndReferer(HttpServletRequest request) {
        Enumeration<String> originHeaders = request.getHeaders(HttpHeaders.ORIGIN);
        List<String> origins = originHeaders != null ? Collections.list(originHeaders) : Collections.emptyList();

        // If the Origin header exists, validate it strictly. NEVER fall back to Referer.
        if (!origins.isEmpty()) {
            if (origins.size() > 1) {
                return ERROR_CODE_UNTRUSTED_ORIGIN;
            }

            String originValue = origins.get(0);
            if (originValue == null || originValue.trim().isEmpty()) {
                return ERROR_CODE_UNTRUSTED_ORIGIN;
            }

            String trimmedOrigin = originValue.trim();
            // Reject multiple origins in single header or literal "null"
            if (trimmedOrigin.contains(",") || trimmedOrigin.contains(" ") || "null".equalsIgnoreCase(trimmedOrigin)) {
                return ERROR_CODE_UNTRUSTED_ORIGIN;
            }

            // Origin header must contain only scheme, host, and optional port (reject path, query, or fragment)
            if (hasPathQueryOrFragment(trimmedOrigin)) {
                return ERROR_CODE_UNTRUSTED_ORIGIN;
            }

            String normalized = normalizeOrigin(trimmedOrigin);
            if (normalized == null || !trustedOrigins.contains(normalized)) {
                return ERROR_CODE_UNTRUSTED_ORIGIN;
            }

            return null; // Origin valid and trusted
        }

        // Origin header is completely absent. Fall back to Referer.
        Enumeration<String> refererHeaders = request.getHeaders(HttpHeaders.REFERER);
        List<String> referers = refererHeaders != null ? Collections.list(refererHeaders) : Collections.emptyList();

        if (referers.isEmpty()) {
            return ERROR_CODE_ORIGIN_REQUIRED;
        }

        if (referers.size() > 1) {
            return ERROR_CODE_UNTRUSTED_ORIGIN;
        }

        String refererValue = referers.get(0);
        if (refererValue == null || refererValue.trim().isEmpty()) {
            return ERROR_CODE_ORIGIN_REQUIRED;
        }

        String trimmedReferer = refererValue.trim();
        if (trimmedReferer.contains(",") || trimmedReferer.contains(" ") || "null".equalsIgnoreCase(trimmedReferer)) {
            return ERROR_CODE_UNTRUSTED_ORIGIN;
        }

        String normalizedReferer = normalizeOrigin(trimmedReferer);
        if (normalizedReferer == null || !trustedOrigins.contains(normalizedReferer)) {
            return ERROR_CODE_UNTRUSTED_ORIGIN;
        }

        return null; // Referer valid and trusted
    }

    /**
     * Checks if a raw URL contains a path, query, or fragment component.
     * Used to ensure Origin headers contain strictly scheme, host, and optional port.
     */
    private static boolean hasPathQueryOrFragment(String rawUrl) {
        if (rawUrl == null) {
            return true;
        }
        URI uri;
        try {
            uri = new URI(rawUrl);
        } catch (URISyntaxException | IllegalArgumentException e) {
            return true;
        }

        if (uri.getQuery() != null || uri.getRawQuery() != null) {
            return true;
        }
        if (uri.getFragment() != null || uri.getRawFragment() != null) {
            return true;
        }

        String path = uri.getPath();
        if (path != null && !path.isEmpty()) {
            return true;
        }
        String rawPath = uri.getRawPath();
        if (rawPath != null && !rawPath.isEmpty()) {
            return true;
        }

        String scheme = uri.getScheme();
        if (scheme != null) {
            int prefixLength = scheme.length() + 3; // "scheme://"
            if (rawUrl.length() > prefixLength) {
                String remainder = rawUrl.substring(prefixLength);
                if (remainder.contains("/") || remainder.contains("?") || remainder.contains("#")) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Normalizes an HTTP/HTTPS URL into its origin representation (scheme://hostname[:port]).
     * Strips path, query, and fragment. Normalizes default ports (80 for http, 443 for https).
     * Rejects userinfo, non-http(s) schemes, and malformed syntax.
     */
    public static String normalizeOrigin(String rawUrl) {
        if (rawUrl == null || rawUrl.trim().isEmpty() || "null".equalsIgnoreCase(rawUrl.trim())) {
            return null;
        }
        if (rawUrl.contains("@")) {
            return null;
        }

        URI uri;
        try {
            uri = new URI(rawUrl.trim());
        } catch (URISyntaxException | IllegalArgumentException e) {
            return null;
        }

        if (!uri.isAbsolute()) {
            return null;
        }

        String scheme = uri.getScheme();
        if (scheme == null) {
            return null;
        }
        scheme = scheme.toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            return null;
        }

        if (uri.getUserInfo() != null || uri.getRawUserInfo() != null) {
            return null;
        }

        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            return null;
        }
        host = host.toLowerCase(Locale.ROOT);

        int port = uri.getPort();
        if (port < -1 || port > 65535) {
            return null;
        }

        int defaultPort = "http".equals(scheme) ? 80 : 443;

        StringBuilder sb = new StringBuilder();
        sb.append(scheme).append("://").append(host);
        if (port != -1 && port != defaultPort) {
            sb.append(':').append(port);
        }
        return sb.toString();
    }

    private void writeForbiddenResponse(HttpServletResponse response, String path, String errorCode, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        ErrorResponse errorResponse = ErrorResponse.of(
                HttpStatus.FORBIDDEN.value(),
                errorCode,
                message,
                path != null ? path : ""
        );

        response.getWriter().write(objectMapper.writeValueAsString(errorResponse));
        response.getWriter().flush();
    }
}
