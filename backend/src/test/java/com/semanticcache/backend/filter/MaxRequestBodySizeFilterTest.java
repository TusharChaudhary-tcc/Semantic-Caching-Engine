package com.semanticcache.backend.filter;

import com.semanticcache.backend.exception.PayloadTooLargeException;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaxRequestBodySizeFilterTest {

    private static final long LIMIT = 65536L;
    private final MaxRequestBodySizeFilter filter = new MaxRequestBodySizeFilter(LIMIT);

    @Test
    @DisplayName("Content-Length above limit returns HTTP 413 and does not invoke filter chain")
    void contentLengthAboveLimitReturns413() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/cache");
        request.setContent("{}".getBytes());
        request.addHeader("Content-Length", String.valueOf(LIMIT + 1));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(413, response.getStatus());
        assertTrue(response.getContentAsString().contains("\"status\":413"));
        assertTrue(response.getContentAsString().contains("Payload Too Large"));
        assertTrue(response.getContentAsString().contains("Request body exceeds maximum allowed size of 65536 bytes"));
        // Filter chain was NOT invoked
        assertEquals(null, chain.getRequest());
    }

    @Test
    @DisplayName("Content-Length exactly at limit invokes filter chain")
    void contentLengthAtLimitPassesThrough() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/cache");
        byte[] body = new byte[(int) LIMIT];
        request.setContent(body);
        request.addHeader("Content-Length", String.valueOf(LIMIT));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertTrue(chain.getRequest() instanceof MaxRequestBodySizeFilter.BoundedRequestWrapper);
        assertEquals(request, ((HttpServletRequestWrapper) chain.getRequest()).getRequest());
    }

    @Test
    @DisplayName("Content-Length below limit invokes filter chain")
    void contentLengthBelowLimitPassesThrough() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/cache");
        request.setContent("{\"prompt\":\"hello\"}".getBytes());
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertTrue(chain.getRequest() instanceof MaxRequestBodySizeFilter.BoundedRequestWrapper);
        assertEquals(request, ((HttpServletRequestWrapper) chain.getRequest()).getRequest());
    }

    @Test
    @DisplayName("GET request bypasses filter and invokes filter chain")
    void getRequestPassesThrough() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/cache");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(request, chain.getRequest());
    }

    @Test
    @DisplayName("OPTIONS request bypasses filter and invokes filter chain")
    void optionsRequestPassesThrough() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/cache");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(request, chain.getRequest());
    }

    @Test
    @DisplayName("Unrelated path bypasses filter and invokes filter chain")
    void unrelatedPathPassesThrough() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/health");
        request.setContent(new byte[(int) LIMIT + 1000]);
        request.addHeader("Content-Length", String.valueOf(LIMIT + 1000));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(request, chain.getRequest());
    }

    @Test
    @DisplayName("Context path is handled correctly when checking path")
    void contextPathHandledCorrectly() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/context/api/cache");
        request.setContextPath("/context");
        request.addHeader("Content-Length", String.valueOf(LIMIT + 10));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(413, response.getStatus());
        assertEquals(null, chain.getRequest());
    }

    @Test
    @DisplayName("Invalid configured limits (zero and negative) are rejected")
    void invalidConfiguredLimitsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new MaxRequestBodySizeFilter(0));
        assertThrows(IllegalArgumentException.class, () -> new MaxRequestBodySizeFilter(-1));
    }

    @Test
    @DisplayName("Bounded stream accepts exactly 65536 bytes via single-byte reads")
    void boundedStreamAcceptsExactlyLimitSingleByteReads() throws IOException {
        byte[] data = new byte[(int) LIMIT];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i % 127);
        }
        ServletInputStream delegate = createServletInputStream(data);
        MaxRequestBodySizeFilter.BoundedServletInputStream stream =
                new MaxRequestBodySizeFilter.BoundedServletInputStream(delegate, LIMIT);

        for (int i = 0; i < LIMIT; i++) {
            int b = stream.read();
            assertEquals(data[i], (byte) b);
        }

        // EOF read must return -1 without throwing
        assertEquals(-1, stream.read());
        assertTrue(stream.isFinished());
        assertEquals(LIMIT, stream.getBytesRead());
    }

    @Test
    @DisplayName("Bounded stream rejects 65537 bytes via single-byte read on byte 65537")
    void boundedStreamRejectsOverLimitSingleByteReads() throws IOException {
        byte[] data = new byte[(int) LIMIT + 1];
        ServletInputStream delegate = createServletInputStream(data);
        MaxRequestBodySizeFilter.BoundedServletInputStream stream =
                new MaxRequestBodySizeFilter.BoundedServletInputStream(delegate, LIMIT);

        for (int i = 0; i < LIMIT; i++) {
            stream.read();
        }

        // 65537th byte must throw PayloadTooLargeException
        assertThrows(PayloadTooLargeException.class, stream::read);
    }

    @Test
    @DisplayName("Bounded stream accepts exactly 65536 bytes via bulk read")
    void boundedStreamAcceptsExactlyLimitBulkRead() throws IOException {
        byte[] data = new byte[(int) LIMIT];
        ServletInputStream delegate = createServletInputStream(data);
        MaxRequestBodySizeFilter.BoundedServletInputStream stream =
                new MaxRequestBodySizeFilter.BoundedServletInputStream(delegate, LIMIT);

        byte[] buffer = new byte[70000];
        int count = stream.read(buffer, 0, buffer.length);

        assertEquals(LIMIT, count);
        assertEquals(-1, stream.read(buffer, 0, buffer.length));
    }

    @Test
    @DisplayName("Bounded stream rejects bulk read exceeding limit")
    void boundedStreamRejectsBulkReadOverLimit() {
        byte[] data = new byte[(int) LIMIT + 10];
        ServletInputStream delegate = createServletInputStream(data);
        MaxRequestBodySizeFilter.BoundedServletInputStream stream =
                new MaxRequestBodySizeFilter.BoundedServletInputStream(delegate, LIMIT);

        byte[] buffer = new byte[70000];
        assertThrows(PayloadTooLargeException.class, () -> stream.read(buffer, 0, buffer.length));
    }

    @Test
    @DisplayName("Bounded stream skip() cannot bypass limit")
    void boundedStreamSkipCannotBypassLimit() throws IOException {
        byte[] data = new byte[(int) LIMIT + 10];
        ServletInputStream delegate = createServletInputStream(data);
        MaxRequestBodySizeFilter.BoundedServletInputStream stream =
                new MaxRequestBodySizeFilter.BoundedServletInputStream(delegate, LIMIT);

        assertThrows(PayloadTooLargeException.class, () -> stream.skip(70000));
    }

    @Test
    @DisplayName("Bounded stream delegates isReady, available, and close methods correctly")
    void boundedStreamDelegatesMethods() throws IOException {
        byte[] data = new byte[100];
        ServletInputStream delegate = createServletInputStream(data);
        MaxRequestBodySizeFilter.BoundedServletInputStream stream =
                new MaxRequestBodySizeFilter.BoundedServletInputStream(delegate, LIMIT);

        assertTrue(stream.isReady());
        assertFalse(stream.isFinished());
        assertEquals(100, stream.available());
        stream.close();
    }

    @Test
    @DisplayName("Bounded stream delegates setReadListener to underlying stream")
    void boundedStreamDelegatesSetReadListener() {
        RecordingServletInputStream delegate = new RecordingServletInputStream(new byte[10]);
        MaxRequestBodySizeFilter.BoundedServletInputStream stream =
                new MaxRequestBodySizeFilter.BoundedServletInputStream(delegate, LIMIT);

        ReadListener testListener = new ReadListener() {
            @Override
            public void onDataAvailable() {}
            @Override
            public void onAllDataRead() {}
            @Override
            public void onError(Throwable t) {}
        };

        stream.setReadListener(testListener);
        assertSame(testListener, delegate.getRecordedListener());
    }

    @Test
    @DisplayName("Filter safely detects PayloadTooLargeException within wrapped exception cause chain")
    void exceptionChainWithPayloadTooLargeHandledSafely() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/cache");
        request.setContent("{}".getBytes());
        MockHttpServletResponse response = new MockHttpServletResponse();

        PayloadTooLargeException rootEx = new PayloadTooLargeException("Oversized payload");
        RuntimeException middleEx = new RuntimeException("Jackson error", rootEx);
        ServletException outerEx = new ServletException("Filter error", middleEx);

        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res)
                    throws IOException, ServletException {
                throw outerEx;
            }
        };

        filter.doFilter(request, response, chain);

        assertEquals(413, response.getStatus());
        assertTrue(response.getContentAsString().contains("\"status\":413"));
        assertTrue(response.getContentAsString().contains("Payload Too Large"));
        assertTrue(response.getContentAsString().contains("Request body exceeds maximum allowed size of 65536 bytes"));
    }

    @Test
    @DisplayName("Filter does not convert unrelated exceptions into HTTP 413")
    void unrelatedExceptionNotConvertedTo413() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/cache");
        request.setContent("{}".getBytes());
        MockHttpServletResponse response = new MockHttpServletResponse();

        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res)
                    throws IOException, ServletException {
                throw new ServletException("Database error");
            }
        };

        ServletException ex = assertThrows(ServletException.class, () -> filter.doFilter(request, response, chain));
        assertEquals("Database error", ex.getMessage());
        assertFalse(response.getStatus() == 413);
    }

    @Test
    @DisplayName("Filter handles self-referencing exception cause chain without infinite traversal")
    void selfReferencingExceptionHandledSafely() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/cache");
        request.setContent("{}".getBytes());
        MockHttpServletResponse response = new MockHttpServletResponse();

        RuntimeException cyclicEx = new RuntimeException("Cyclic error") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };

        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res)
                    throws IOException, ServletException {
                throw new ServletException("Wrapped cyclic", cyclicEx);
            }
        };

        assertThrows(ServletException.class, () -> filter.doFilter(request, response, chain));
        assertFalse(response.getStatus() == 413);
    }

    private ServletInputStream createServletInputStream(byte[] data) {
        return new RecordingServletInputStream(data);
    }

    private static class RecordingServletInputStream extends ServletInputStream {
        private final ByteArrayInputStream bais;
        private ReadListener recordedListener;

        RecordingServletInputStream(byte[] data) {
            this.bais = new ByteArrayInputStream(data);
        }

        public ReadListener getRecordedListener() {
            return recordedListener;
        }

        @Override
        public boolean isFinished() {
            return bais.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            this.recordedListener = readListener;
        }

        @Override
        public int read() {
            return bais.read();
        }

        @Override
        public int read(byte[] b, int off, int len) {
            return bais.read(b, off, len);
        }

        @Override
        public long skip(long n) {
            return bais.skip(n);
        }

        @Override
        public int available() {
            return bais.available();
        }

        @Override
        public void close() throws IOException {
            bais.close();
        }
    }
}
