package com.semanticcache.backend.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.semanticcache.backend.dto.ErrorResponse;
import com.semanticcache.backend.exception.InvalidJwtException;
import com.semanticcache.backend.service.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Objects;
import java.util.UUID;

/**
 * Servlet filter that inspects and authenticates Bearer JWT access tokens on incoming requests.
 * <p>
 * Requests lacking an Authorization header continue anonymously. Any supplied but invalid,
 * expired, malformed, or unsupported credentials are immediately rejected with HTTP 401.
 * Guaranteed cleanup ensures no thread-local security context leaks across requests.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String BEARER_PREFIX = "bearer ";
    private static final int BEARER_PREFIX_LENGTH = BEARER_PREFIX.length();

    private final JwtService jwtService;
    private final ObjectMapper objectMapper;

    @Autowired
    public JwtAuthenticationFilter(JwtService jwtService, @Autowired(required = false) ObjectMapper objectMapper) {
        this.jwtService = Objects.requireNonNull(jwtService, "jwtService must not be null");
        this.objectMapper = objectMapper != null ? objectMapper : createDefaultObjectMapper();
    }

    public JwtAuthenticationFilter(JwtService jwtService) {
        this(jwtService, null);
    }

    private static ObjectMapper createDefaultObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.findAndRegisterModules();
        return mapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        // Clear any pre-existing security context at request start
        SecurityContextHolder.clearContext();

        try {
            // Pass-through OPTIONS requests for CORS preflight, keeping inside the cleanup lifecycle
            if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
                filterChain.doFilter(request, response);
                return;
            }

            String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);

            // Absent header: continue anonymously
            if (authHeader == null) {
                filterChain.doFilter(request, response);
                return;
            }

            // Empty or whitespace-only header: reject with 401
            if (authHeader.trim().isEmpty()) {
                writeUnauthorizedResponse(response, request.getRequestURI(), "Authorization header must not be empty or blank");
                return;
            }

            // Unsupported scheme or missing Bearer prefix: reject with 401 (case-insensitive)
            if (authHeader.length() < BEARER_PREFIX_LENGTH
                    || !authHeader.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX_LENGTH)) {
                writeUnauthorizedResponse(response, request.getRequestURI(), "Unsupported or malformed authorization header");
                return;
            }

            // Extract raw token substring without trimming to preserve token integrity
            String token = authHeader.substring(BEARER_PREFIX_LENGTH);

            UUID userId;
            try {
                userId = jwtService.validateAccessToken(token);
            } catch (InvalidJwtException | IllegalArgumentException e) {
                log.warn("JWT authentication failed on path [{}]", request.getRequestURI());
                writeUnauthorizedResponse(response, request.getRequestURI(), "Invalid or expired access token");
                return;
            }

            // Establish authenticated principal in SecurityContext
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            Authentication auth = new UsernamePasswordAuthenticationToken(userId, null, Collections.emptyList());
            context.setAuthentication(auth);
            SecurityContextHolder.setContext(context);

            filterChain.doFilter(request, response);
        } finally {
            // Strictly clear thread-local context on every exit path
            SecurityContextHolder.clearContext();
        }
    }

    private void writeUnauthorizedResponse(HttpServletResponse response, String path, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\"");

        ErrorResponse errorResponse = ErrorResponse.of(
                HttpStatus.UNAUTHORIZED.value(),
                HttpStatus.UNAUTHORIZED.getReasonPhrase(),
                message,
                path != null ? path : ""
        );

        response.getWriter().write(objectMapper.writeValueAsString(errorResponse));
        response.getWriter().flush();
    }
}
