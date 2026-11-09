package com.semanticcache.backend.filter;

import com.semanticcache.backend.exception.PayloadTooLargeException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;

/**
 * Servlet filter that enforces a strict maximum request body size on POST /api/cache.
 * <p>
 * Fast-path rejection is performed when Content-Length exceeds the configured limit.
 * For chunked or streaming requests without a declared length, a bounded stream wrapper
 * counts bytes as they are read and aborts immediately if the limit is exceeded.
 */
@Component
public class MaxRequestBodySizeFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(MaxRequestBodySizeFilter.class);

    private final long maxRequestBodyBytes;

    public MaxRequestBodySizeFilter(
            @Value("${cache.max-request-body-bytes:65536}") long maxRequestBodyBytes
    ) {
        if (maxRequestBodyBytes <= 0) {
            throw new IllegalArgumentException(
                    "cache.max-request-body-bytes must be greater than zero. Configured value: " + maxRequestBodyBytes
            );
        }
        this.maxRequestBodyBytes = maxRequestBodyBytes;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            uri = uri.substring(contextPath.length());
        }
        return !"/api/cache".equals(uri);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        long contentLength = request.getContentLengthLong();
        String contentLengthHeader = request.getHeader("Content-Length");
        if (contentLengthHeader != null) {
            try {
                long parsedHeader = Long.parseLong(contentLengthHeader.trim());
                if (parsedHeader > contentLength) {
                    contentLength = parsedHeader;
                }
            } catch (NumberFormatException ignored) {
                // Ignored, rely on contentLength
            }
        }
        if (contentLength > maxRequestBodyBytes) {
            log.warn("Request payload exceeded Content-Length limit [{} bytes] on path [{}]",
                    maxRequestBodyBytes, request.getRequestURI());
            writePayloadTooLargeResponse(response, request.getRequestURI());
            return;
        }

        BoundedRequestWrapper wrappedRequest = new BoundedRequestWrapper(request, maxRequestBodyBytes);
        try {
            filterChain.doFilter(wrappedRequest, response);
        } catch (Exception e) {
            if (containsPayloadTooLargeException(e)) {
                if (!response.isCommitted()) {
                    log.warn("Request payload exceeded size limit [{} bytes] on path [{}]",
                            maxRequestBodyBytes, request.getRequestURI());
                    writePayloadTooLargeResponse(response, request.getRequestURI());
                    return;
                }
            }
            if (e instanceof IOException) {
                throw (IOException) e;
            }
            if (e instanceof ServletException) {
                throw (ServletException) e;
            }
            throw new ServletException(e);
        }
    }

    private void writePayloadTooLargeResponse(HttpServletResponse response, String path) throws IOException {
        response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        String json = String.format(
                "{\"timestamp\":\"%s\",\"status\":%d,\"error\":\"%s\",\"message\":\"%s\",\"path\":\"%s\"}",
                Instant.now().toString(),
                HttpStatus.PAYLOAD_TOO_LARGE.value(),
                HttpStatus.PAYLOAD_TOO_LARGE.getReasonPhrase(),
                "Request body exceeds maximum allowed size of " + maxRequestBodyBytes + " bytes",
                path != null ? path : "/api/cache"
        );
        response.getWriter().write(json);
        response.getWriter().flush();
    }

    private boolean containsPayloadTooLargeException(Throwable throwable) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = throwable;
        while (current != null && seen.add(current)) {
            if (current instanceof PayloadTooLargeException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    public long getMaxRequestBodyBytes() {
        return maxRequestBodyBytes;
    }

    /**
     * Request wrapper that supplies a {@link BoundedServletInputStream}.
     */
    static class BoundedRequestWrapper extends HttpServletRequestWrapper {

        private final long maxBytes;
        private ServletInputStream inputStream;
        private BufferedReader reader;

        public BoundedRequestWrapper(HttpServletRequest request, long maxBytes) {
            super(request);
            this.maxBytes = maxBytes;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (reader != null) {
                throw new IllegalStateException("getReader() has already been called on this request");
            }
            if (inputStream == null) {
                inputStream = new BoundedServletInputStream(super.getInputStream(), maxBytes);
            }
            return inputStream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            if (inputStream != null) {
                throw new IllegalStateException("getInputStream() has already been called on this request");
            }
            if (reader == null) {
                String encoding = getCharacterEncoding();
                Charset charset = encoding != null ? Charset.forName(encoding) : StandardCharsets.UTF_8;
                reader = new BufferedReader(new InputStreamReader(getInputStream(), charset));
            }
            return reader;
        }
    }

    /**
     * Bounded stream implementation that tracks byte counts and probes for overflow at the boundary.
     */
    static class BoundedServletInputStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private final long maxBytes;
        private long bytesRead = 0;
        private boolean probeCompleted = false;

        public BoundedServletInputStream(ServletInputStream delegate, long maxBytes) {
            this.delegate = Objects.requireNonNull(delegate, "delegate ServletInputStream must not be null");
            this.maxBytes = maxBytes;
        }

        @Override
        public int read() throws IOException {
            if (bytesRead >= maxBytes) {
                int extra = delegate.read();
                if (extra == -1) {
                    probeCompleted = true;
                    return -1;
                }
                throw new PayloadTooLargeException(
                        "Request body exceeds maximum allowed size of " + maxBytes + " bytes"
                );
            }

            int b = delegate.read();
            if (b != -1) {
                bytesRead++;
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (b == null) {
                throw new NullPointerException("Buffer must not be null");
            }
            if (off < 0 || len < 0 || len > b.length - off) {
                throw new IndexOutOfBoundsException();
            }
            if (len == 0) {
                return 0;
            }

            if (bytesRead >= maxBytes) {
                int extra = delegate.read();
                if (extra == -1) {
                    probeCompleted = true;
                    return -1;
                }
                throw new PayloadTooLargeException(
                        "Request body exceeds maximum allowed size of " + maxBytes + " bytes"
                );
            }

            long remaining = maxBytes - bytesRead;
            // Attempt to read up to (remaining + 1) bytes to detect boundary overflow in a single read
            int toRead = (int) Math.min((long) len, remaining + 1);
            int count = delegate.read(b, off, toRead);
            if (count == -1) {
                return -1;
            }

            if (count > remaining) {
                bytesRead += remaining;
                throw new PayloadTooLargeException(
                        "Request body exceeds maximum allowed size of " + maxBytes + " bytes"
                );
            }

            bytesRead += count;
            return count;
        }

        @Override
        public long skip(long n) throws IOException {
            if (n <= 0) {
                return 0;
            }

            if (bytesRead >= maxBytes) {
                int extra = delegate.read();
                if (extra == -1) {
                    probeCompleted = true;
                    return 0;
                }
                throw new PayloadTooLargeException(
                        "Request body exceeds maximum allowed size of " + maxBytes + " bytes"
                );
            }

            long remaining = maxBytes - bytesRead;
            long toSkip = Math.min(n, remaining + 1);
            long skipped = delegate.skip(toSkip);
            if (skipped > remaining) {
                bytesRead += remaining;
                throw new PayloadTooLargeException(
                        "Request body exceeds maximum allowed size of " + maxBytes + " bytes"
                );
            }

            bytesRead += skipped;
            return skipped;
        }

        @Override
        public boolean isFinished() {
            if (probeCompleted) {
                return true;
            }
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            delegate.setReadListener(readListener);
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }

        @Override
        public int available() throws IOException {
            return delegate.available();
        }

        public long getBytesRead() {
            return bytesRead;
        }
    }
}
