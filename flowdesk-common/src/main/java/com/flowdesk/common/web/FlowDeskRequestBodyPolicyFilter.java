package com.flowdesk.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Enforces API-SPEC §2.8 request-body limits and §2.6 Content-Encoding before any MVC
 * controller or business use case runs.
 *
 * <p>Content-Length is checked eagerly. Chunked bodies without a length are wrapped in a
 * bounded stream that raises {@link RequestBodyTooLargeException} the moment the limit
 * is exceeded; the shared exception handler renders it as 413 REQUEST_TOO_LARGE.</p>
 */
public class FlowDeskRequestBodyPolicyFilter extends OncePerRequestFilter {

    static final long LOGIN_MAX_BYTES = 8L * 1024L;
    static final long WEBHOOK_MAX_BYTES = 64L * 1024L;
    static final long DEFAULT_MAX_BYTES = 256L * 1024L;

    private final ObjectMapper mapper;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public FlowDeskRequestBodyPolicyFilter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !isBodyMethod(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long limit = limitFor(request.getRequestURI());

        List<String> encodings = list(request.getHeaders("Content-Encoding"));
        if (!encodings.isEmpty()
                && (encodings.size() != 1 || !"identity".equalsIgnoreCase(encodings.get(0)))) {
            ApiErrorWriter.write(response, 415, "CONTENT_ENCODING_UNSUPPORTED",
                    "Content-Encoding 只接受 identity。", false, correlationId(request), List.of(), mapper);
            return;
        }

        long contentLength = request.getContentLengthLong();
        if (contentLength > limit) {
            ApiErrorWriter.write(response, 413, "REQUEST_TOO_LARGE",
                    "请求 body 超过允许上限。", false, correlationId(request), List.of(), mapper);
            return;
        }

        HttpServletRequest wrapped = contentLength >= 0
                ? request
                : new BoundedBodyRequest(request, limit);
        filterChain.doFilter(wrapped, response);
    }

    private long limitFor(String uri) {
        if (pathMatcher.match("/api/v1/auth/login", uri)) {
            return LOGIN_MAX_BYTES;
        }
        if (pathMatcher.match("/api/v1/webhooks/**", uri)) {
            return WEBHOOK_MAX_BYTES;
        }
        return DEFAULT_MAX_BYTES;
    }

    private static boolean isBodyMethod(String method) {
        return HttpMethod.POST.matches(method)
                || HttpMethod.PUT.matches(method)
                || HttpMethod.PATCH.matches(method);
    }

    private static java.util.UUID correlationId(HttpServletRequest request) {
        Object value = request.getAttribute(FlowDeskRequestAttributes.CORRELATION_ID);
        return value instanceof java.util.UUID uuid ? uuid : java.util.UUID.randomUUID();
    }

    private static List<String> list(java.util.Enumeration<String> enumeration) {
        java.util.ArrayList<String> values = new java.util.ArrayList<>();
        while (enumeration.hasMoreElements()) {
            values.add(enumeration.nextElement());
        }
        return values;
    }

    private static final class BoundedBodyRequest extends HttpServletRequestWrapper {

        private final long limitBytes;
        private BoundedServletInputStream boundedStream;

        BoundedBodyRequest(HttpServletRequest request, long limitBytes) {
            super(request);
            this.limitBytes = limitBytes;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (boundedStream == null) {
                boundedStream = new BoundedServletInputStream(super.getInputStream(), limitBytes);
            }
            return boundedStream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            Charset charset = StandardCharsets.UTF_8;
            if (encoding != null) {
                try {
                    charset = Charset.forName(encoding);
                } catch (RuntimeException ignored) {
                    charset = StandardCharsets.UTF_8;
                }
            }
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }

    private static final class BoundedServletInputStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private final long limitBytes;
        private long bytesRead;

        BoundedServletInputStream(ServletInputStream delegate, long limitBytes) {
            this.delegate = delegate;
            this.limitBytes = limitBytes;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value != -1) {
                bytesRead++;
                ensureWithinLimit();
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = delegate.read(buffer, offset, length);
            if (read > 0) {
                bytesRead += read;
                ensureWithinLimit();
            }
            return read;
        }

        private void ensureWithinLimit() throws RequestBodyTooLargeException {
            if (bytesRead > limitBytes) {
                throw new RequestBodyTooLargeException(limitBytes);
            }
        }

        @Override
        public boolean isFinished() {
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
    }
}
