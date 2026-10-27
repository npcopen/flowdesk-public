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
 * 在控制器和业务逻辑执行前校验请求体大小及 Content-Encoding。
 * 有 Content-Length 时先检查长度；无长度的分块请求使用有界输入流，超限后由统一异常处理返回 413。
 */
public class FlowDeskRequestBodyPolicyFilter extends OncePerRequestFilter {

    static final long LOGIN_MAX_BYTES = 8L * 1024L;
    static final long WEBHOOK_MAX_BYTES = 64L * 1024L;
    static final long DEFAULT_MAX_BYTES = 256L * 1024L;

    private final ObjectMapper mapper;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    /**
     * 保存错误响应 JSON 映射器，供请求体协议检查失败时输出统一信封。
     */
    public FlowDeskRequestBodyPolicyFilter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 仅对 POST、PUT 和 PATCH 写入请求执行请求体检查，其他方法跳过。
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !isBodyMethod(request.getMethod());
    }

    /**
     * 检查内容编码及已声明长度，按路径应用大小限制；未知长度请求套用有界输入流，非法请求提前返回错误。
     */
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

    /**
     * 分别为登录、Webhook 与普通写入路径选择 8 KiB、64 KiB 和 256 KiB 上限。
     */
    private long limitFor(String uri) {
        if (pathMatcher.match("/api/v1/auth/login", uri)) {
            return LOGIN_MAX_BYTES;
        }
        if (pathMatcher.match("/api/v1/webhooks/**", uri)) {
            return WEBHOOK_MAX_BYTES;
        }
        return DEFAULT_MAX_BYTES;
    }

    /**
     * 识别需要检查请求体的 POST、PUT 和 PATCH 方法。
     */
    private static boolean isBodyMethod(String method) {
        return HttpMethod.POST.matches(method)
                || HttpMethod.PUT.matches(method)
                || HttpMethod.PATCH.matches(method);
    }

    /**
     * 读取上游保存的请求关联 UUID，缺失时生成标识供错误响应使用。
     */
    private static java.util.UUID correlationId(HttpServletRequest request) {
        Object value = request.getAttribute(FlowDeskRequestAttributes.CORRELATION_ID);
        return value instanceof java.util.UUID uuid ? uuid : java.util.UUID.randomUUID();
    }

    /**
     * 复制请求头枚举为列表，以识别缺失、单值或重复的内容编码头。
     */
    private static List<String> list(java.util.Enumeration<String> enumeration) {
        java.util.ArrayList<String> values = new java.util.ArrayList<>();
        while (enumeration.hasMoreElements()) {
            values.add(enumeration.nextElement());
        }
        return values;
    }

    /**
     * 为未知长度请求提供有界读取，复用同一计数输入流并支持按请求字符集读取。
     */
    private static final class BoundedBodyRequest extends HttpServletRequestWrapper {

        private final long limitBytes;
        private BoundedServletInputStream boundedStream;

        /**
         * 包装原 Servlet 请求并记录字节上限，实际读取时才创建计数输入流。
         */
        BoundedBodyRequest(HttpServletRequest request, long limitBytes) {
            super(request);
            this.limitBytes = limitBytes;
        }

        /**
         * 惰性创建并缓存有界输入流，重复获取时继续沿用同一读取计数。
         */
        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (boundedStream == null) {
                boundedStream = new BoundedServletInputStream(super.getInputStream(), limitBytes);
            }
            return boundedStream;
        }

        /**
         * 使用请求字符集读取有界流，字符集缺失或无效时回退 UTF-8。
         */
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

    /**
     * 累计实际读取字节并在超限时抛出专用异常，同时透传 Servlet 非阻塞状态。
     */
    private static final class BoundedServletInputStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private final long limitBytes;
        private long bytesRead;

        /**
         * 保存被包装输入流和字节上限，后续每次读取都参与同一累计计数。
         */
        BoundedServletInputStream(ServletInputStream delegate, long limitBytes) {
            this.delegate = delegate;
            this.limitBytes = limitBytes;
        }

        /**
         * 委托读取并累计实际字节数，超限立即抛出专用异常，EOF 不增加计数。
         */
        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value != -1) {
                bytesRead++;
                ensureWithinLimit();
            }
            return value;
        }

        /**
         * 委托读取并累计实际字节数，超限立即抛出专用异常，EOF 不增加计数。
         */
        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = delegate.read(buffer, offset, length);
            if (read > 0) {
                bytesRead += read;
                ensureWithinLimit();
            }
            return read;
        }

        /**
         * 比较累计读取量与上限，超出时抛出可转为 413 的请求体异常。
         */
        private void ensureWithinLimit() throws RequestBodyTooLargeException {
            if (bytesRead > limitBytes) {
                throw new RequestBodyTooLargeException(limitBytes);
            }
        }

        /**
         * 透传底层流的完成状态，保持 Servlet 非阻塞读取契约。
         */
        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        /**
         * 透传底层流的可读状态，供容器判断是否可以继续读取。
         */
        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        /**
         * 将监听器委托给原始 Servlet 流，保持容器异步读取机制。
         */
        @Override
        public void setReadListener(ReadListener readListener) {
            delegate.setReadListener(readListener);
        }
    }
}
