package com.flowdesk.gateway;

import com.flowdesk.common.security.IssuedJwt;
import com.flowdesk.common.security.JwtIssuer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
  * 通过本地请求记录后端验证网关路径归属、JWT 边界、内部头清理和 Webhook 原文透传，覆盖路由前的拒绝行为。
  */
 @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayRoutingIntegrationTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    private static final KeyPair KEY_PAIR = newKeyPair();
    private static final JwtIssuer ISSUER = new JwtIssuer(
            KEY_PAIR.getPrivate(), "flowdesk-iam-service", "flowdesk-api", "flowdesk-internal", 900, 300);
    private static final Backend IAM = new Backend("iam");
    private static final Backend TICKET = new Backend("ticket");
    private static final Backend WORKFLOW = new Backend("workflow");
    private static final Backend ALERT = new Backend("alert");
    private static final Backend NOTIFICATION = new Backend("notification");

    static {
        IAM.start();
        TICKET.start();
        WORKFLOW.start();
        ALERT.start();
        NOTIFICATION.start();
    }

    @LocalServerPort
    private int gatewayPort;

    private WebTestClient client;

    /**
     * 注册测试 JWT 密钥与各服务本地后端地址，使路由集成测试覆盖真实网关过滤链。
     */
    @DynamicPropertySource
    static void gatewayProperties(DynamicPropertyRegistry registry) {
        registry.add("flowdesk.security.jwt.public-key-pem", () -> publicKeyPem());
        registry.add("flowdesk.gateway.routes.iam", () -> IAM.baseUrl());
        registry.add("flowdesk.gateway.routes.ticket", () -> TICKET.baseUrl());
        registry.add("flowdesk.gateway.routes.workflow", () -> WORKFLOW.baseUrl());
        registry.add("flowdesk.gateway.routes.alert", () -> ALERT.baseUrl());
        registry.add("flowdesk.gateway.routes.notification", () -> NOTIFICATION.baseUrl());
        registry.add("flowdesk.gateway.webhook-require-tls", () -> "false");
        registry.add("spring.cloud.service-registry.auto-registration.enabled", () -> "false");
    }

    /**
     * 重置各测试后端的请求计数、头部和正文记录，避免用例间透传断言相互影响。
     */
    @BeforeEach
    void resetBackends() {
        IAM.reset();
        TICKET.reset();
        WORKFLOW.reset();
        ALERT.reset();
        NOTIFICATION.reset();
        client = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + gatewayPort).build();
    }

    /**
     * 验证 API 网关不提供前端页面或类路径静态资源，避免路由职责混淆。
     */
    @Test
    void frontendPagesAndClasspathAssetsAreNotServedByTheApiGateway() {
        // Keep a classpath fixture to catch accidental re-enabling of Spring's
        // default resource mappings, including stale static files from old builds.
        for (String path : java.util.List.of(
                "/", "/index.html", "/login", "/tickets/FD-000001",
                "/settings/users", "/settings/catalog", "/assets/test.js",
                "/gateway-static-must-not-be-served.txt")) {
            client.get().uri(path).exchange().expectStatus().isNotFound();
        }

        assertThat(IAM.requests()).isZero();
        assertThat(TICKET.requests()).isZero();
    }

    /**
     * 验证健康探针无需 JWT 且禁用缓存，运维状态不会被缓存响应掩盖。
     */
    @Test
    void healthProbesArePublicAndDoNotCacheOperationalState() {
        client.get().uri("/api/v1/internal/health/liveness")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectHeader().exists("X-Correlation-Id");

        client.get().uri("/api/v1/internal/health/readiness")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectHeader().exists("X-Correlation-Id");
    }

    /**
     * 验证未知 API 路径返回统一 JSON 未找到响应，不回退为前端页面。
     */
    @Test
    void unknownApiPathsReturnJsonNotFound() {
        client.get().uri("/api/v1/unknown")
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentTypeCompatibleWith("application/json")
                .expectBody(String.class).value(body -> assertThat(body).doesNotContain("<html"));
    }

    /**
     * 验证登录公开路由到 IAM，同时移除客户端伪造的内部身份头。
     */
    @Test
    void loginIsPublicAndRoutesToIamWhileForgedInternalHeadersAreRemoved() {
        client.post().uri("/api/v1/auth/login")
                .header("X-Tenant-Id", TENANT.toString())
                .contentType(org.springframework.http.MediaType.parseMediaType(
                        "application/json; charset=utf-8"))
                .bodyValue("""
                        {"tenantCode":"ACME_SUPPORT","username":"alice","password":"<redacted>"}
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists("X-Correlation-Id")
                .expectBody(String.class).value(body -> assertThat(body).contains("iam"));

        assertThat(IAM.requests()).isEqualTo(1);
        assertThat(IAM.lastHeader("X-Tenant-Id")).isNull();
        assertThat(IAM.lastHeader("X-Correlation-Id")).isNotNull();
    }

    /**
     * 验证人类 API 只到达其唯一归属后端，可信身份由可验签的 JWT 透传。
     */
    @Test
    void humanApiRoutesToOneBackendAndForwardsOnlyTheVerifiableJwt() {
        IssuedJwt jwt = ISSUER.issueHuman(TENANT, USER, java.util.Set.of(), java.util.Set.of());
        client.get().uri("/api/v1/tickets?status=OPEN")
                .header("Authorization", "Bearer " + jwt.token())
                .header("X-Correlation-Id", UUID.randomUUID().toString())
                .header("X-Tenant-Id", UUID.randomUUID().toString())
                .header("X-FlowDesk-Internal-Actor", "fake")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).value(body -> assertThat(body).contains("ticket"));

        assertThat(TICKET.requests()).isEqualTo(1);
        assertThat(TICKET.lastHeader("Authorization")).isEqualTo("Bearer " + jwt.token());
        assertThat(TICKET.lastHeader("X-Tenant-Id")).isNull();
        assertThat(TICKET.lastHeader("X-FlowDesk-Internal-Actor")).isNull();
    }

    /**
     * 验证保护 API 缺少 JWT 时在路由前拒绝，后端不收到请求。
     */
    @Test
    void protectedApiWithoutJwtIsRejectedBeforeRouting() {
        client.get().uri("/api/v1/tickets")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().exists("X-Correlation-Id")
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("AUTHENTICATION_REQUIRED");
        assertThat(TICKET.requests()).isZero();
    }

    /**
     * 验证格式错误 JWT 以及误用于人类 API 的服务 JWT 均被拒绝。
     */
    @Test
    void malformedJwtAndServiceJwtOnHumanApiAreRejected() {
        client.get().uri("/api/v1/workflow-definitions")
                .header("Authorization", "Bearer not-a-jwt")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.error.code").isEqualTo("AUTHENTICATION_FAILED");
        assertThat(WORKFLOW.requests()).isZero();

        IssuedJwt service = ISSUER.issueService(TENANT, "flowdesk-ticket-service");
        client.get().uri("/api/v1/notifications")
                .header("Authorization", "Bearer " + service.token())
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.error.code").isEqualTo("AUTHENTICATION_FAILED");
        assertThat(NOTIFICATION.requests()).isZero();
    }

    /**
     * 验证每个业务路径族仅路由到其归属服务，避免多个后端竞争同一路由。
     */
    @Test
    void everyPathFamilyRoutesToItsSingleOwner() {
        IssuedJwt jwt = ISSUER.issueHuman(TENANT, USER, java.util.Set.of(), java.util.Set.of());
        client.get().uri("/api/v1/workflow-definitions")
                .header("Authorization", "Bearer " + jwt.token()).exchange().expectStatus().isOk();
        client.get().uri("/api/v1/alert-events")
                .header("Authorization", "Bearer " + jwt.token()).exchange().expectStatus().isOk();
        client.get().uri("/api/v1/notifications")
                .header("Authorization", "Bearer " + jwt.token()).exchange().expectStatus().isOk();
        client.get().uri("/api/v1/users/me")
                .header("Authorization", "Bearer " + jwt.token()).exchange().expectStatus().isOk();
        client.get().uri("/api/v1/roles")
                .header("Authorization", "Bearer " + jwt.token()).exchange().expectStatus().isOk();

        assertThat(WORKFLOW.requests()).isEqualTo(1);
        assertThat(ALERT.requests()).isEqualTo(1);
        assertThat(NOTIFICATION.requests()).isEqualTo(1);
        assertThat(IAM.requests()).isEqualTo(2);
        assertThat(TICKET.requests()).isZero();
    }

    /**
     * 验证 Webhook 无需登录 JWT 即可透传，签名安全头和原始正文保持完整。
     */
    @Test
    void webhookPassesThroughWithoutJwtWithSecurityHeadersAndRawBodyIntact() {
        byte[] rawBody = "{\"eventId\":\"e-1\",\"fingerprint\":\"fp\"}\n".getBytes(StandardCharsets.UTF_8);
        String apiKey = "key-" + UUID.randomUUID();
        String signature = "sha256=" + "0".repeat(64);
        client.post().uri("/api/v1/webhooks/monitoring/alerts")
                .header("X-FlowDesk-Api-Key", apiKey)
                .header("X-FlowDesk-Signature-Version", "1")
                .header("X-FlowDesk-Timestamp", "1789000000")
                .header("X-FlowDesk-Nonce", "abcdefghijklmnop")
                .header("X-FlowDesk-Signature", signature)
                .header("X-Tenant-Id", TENANT.toString())
                .contentType(org.springframework.http.MediaType.parseMediaType("application/json;charset=utf-8"))
                .bodyValue(rawBody)
                .exchange()
                .expectStatus().isOk();

        assertThat(ALERT.requests()).isEqualTo(1);
        assertThat(ALERT.lastHeader("X-FlowDesk-Api-Key")).isEqualTo(apiKey);
        assertThat(ALERT.lastHeader("X-FlowDesk-Signature")).isEqualTo(signature);
        assertThat(ALERT.lastHeader("X-Tenant-Id")).isNull();
        assertThat(ALERT.lastHeader("X-Correlation-Id")).isNotNull();
        assertThat(ALERT.lastBody()).isEqualTo(rawBody);
    }

    /**
     * 验证空 Webhook 正文仍传给告警服务，按先认证后业务模式校验的顺序处理。
     */
    @Test
    void emptyWebhookBodyIsForwardedForPostAuthenticationSchemaValidation() {
        client.post().uri("/api/v1/webhooks/monitoring/alerts")
                .header("X-FlowDesk-Api-Key", "key-" + UUID.randomUUID())
                .header("X-FlowDesk-Signature-Version", "1")
                .header("X-FlowDesk-Timestamp", "1789000000")
                .header("X-FlowDesk-Nonce", "abcdefghijklmnop")
                .header("X-FlowDesk-Signature", "sha256=" + "0".repeat(64))
                .contentType(org.springframework.http.MediaType.parseMediaType(
                        "application/json;charset=utf-8"))
                .bodyValue(new byte[0])
                .exchange()
                .expectStatus().isOk();

        assertThat(ALERT.requests()).isEqualTo(1);
        assertThat(ALERT.lastBody()).isEmpty();
    }

    /**
     * 验证 Webhook 拒绝查询参数、不允许的认证头及重复安全头，避免签名解释歧义。
     */
    @Test
    void webhookRejectsQueryForbiddenHeadersAndDuplicateSecurityHeaders() {
        String apiKey = "key-" + UUID.randomUUID();
        String signature = "sha256=" + "0".repeat(64);

        client.post().uri("/api/v1/webhooks/monitoring/alerts?tenantId=1")
                .header("X-FlowDesk-Api-Key", apiKey)
                .header("X-FlowDesk-Signature-Version", "1")
                .header("X-FlowDesk-Timestamp", "1789000000")
                .header("X-FlowDesk-Nonce", "abcdefghijklmnop")
                .header("X-FlowDesk-Signature", signature)
                .contentType(org.springframework.http.MediaType.parseMediaType("application/json;charset=utf-8"))
                .bodyValue("{}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error.code").isEqualTo("WEBHOOK_QUERY_FORBIDDEN");

        client.post().uri("/api/v1/webhooks/monitoring/alerts")
                .header("Authorization", "Bearer forged")
                .header("X-FlowDesk-Api-Key", apiKey)
                .header("X-FlowDesk-Signature-Version", "1")
                .header("X-FlowDesk-Timestamp", "1789000000")
                .header("X-FlowDesk-Nonce", "abcdefghijklmnop")
                .header("X-FlowDesk-Signature", signature)
                .contentType(org.springframework.http.MediaType.parseMediaType("application/json;charset=utf-8"))
                .bodyValue("{}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error.code").isEqualTo("WEBHOOK_HEADER_MISSING");

        client.post().uri("/api/v1/webhooks/monitoring/alerts")
                .header("X-FlowDesk-Api-Key", apiKey, apiKey)
                .header("X-FlowDesk-Signature-Version", "1")
                .header("X-FlowDesk-Timestamp", "1789000000")
                .header("X-FlowDesk-Nonce", "abcdefghijklmnop")
                .header("X-FlowDesk-Signature", signature)
                .contentType(org.springframework.http.MediaType.parseMediaType("application/json;charset=utf-8"))
                .bodyValue("{}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error.code").isEqualTo("WEBHOOK_HEADER_MISSING");

        assertThat(ALERT.requests()).isZero();
    }

    /**
     * 验证超过 64 KiB 的 Webhook 正文在网关路由前拒绝，不占用后端认证处理。
     */
    @Test
    void webhookBodyLargerThan64KiBIsRejectedBeforeRouting() {
        String apiKey = "key-" + UUID.randomUUID();
        String signature = "sha256=" + "0".repeat(64);
        String body = "{\"payload\":\"" + "x".repeat(65 * 1024) + "\"}";

        client.post().uri("/api/v1/webhooks/monitoring/alerts")
                .header("X-FlowDesk-Api-Key", apiKey)
                .header("X-FlowDesk-Signature-Version", "1")
                .header("X-FlowDesk-Timestamp", "1789000000")
                .header("X-FlowDesk-Nonce", "abcdefghijklmnop")
                .header("X-FlowDesk-Signature", signature)
                .contentType(org.springframework.http.MediaType.parseMediaType("application/json;charset=utf-8"))
                .bodyValue(body)
                .exchange()
                .expectStatus().isEqualTo(413)
                .expectBody().jsonPath("$.error.code").isEqualTo("REQUEST_TOO_LARGE");

        assertThat(ALERT.requests()).isZero();
    }

    /**
     * 生成测试 RSA 密钥对，签发令牌与网关验签配置共享同一密钥材料。
     */
    private static KeyPair newKeyPair() {
        try {
            return KeyPairGenerator.getInstance("RSA").generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 将测试 RSA 公钥编码为 PEM，供网关从正常配置路径加载验签密钥。
     */
    private static String publicKeyPem() {
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                .encodeToString(KEY_PAIR.getPublic().getEncoded());
        return "-----BEGIN PUBLIC KEY-----\n" + base64 + "\n-----END PUBLIC KEY-----\n";
    }

    /**
     * 记录请求次数、头部和正文的本地 HTTP 测试后端，用于判断路由归属、内部头清理及 Webhook 原文透传。
     */
    private static final class Backend {
        private final String name;
        private final AtomicInteger requests = new AtomicInteger();
        private final Map<String, String> lastHeaders = new ConcurrentHashMap<>();
        private volatile byte[] lastBody;
        private volatile HttpServer server;
        private volatile int port;

        /**
         * 创建指定服务名称及监听端口的测试后端，保存请求记录容器供后续路由和透传断言使用。
         */
        private Backend(String name) {
            this.name = name;
        }

        /**
         * 启动保存请求快照的本地测试后端，返回实例供网关路由配置引用。
         */
        void start() {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                port = server.getAddress().getPort();
                server.createContext("/", this::handle);
                server.start();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        /**
         * 读取网关转发请求并返回可识别的 JSON 后端标记；记录必要请求信息供路由和透传断言。
         */
        private void handle(HttpExchange exchange) throws IOException {
            requests.incrementAndGet();
            exchange.getRequestHeaders().forEach((key, value) ->
                    lastHeaders.put(key.toLowerCase(java.util.Locale.ROOT), String.join(",", value)));
            lastBody = exchange.getRequestBody().readAllBytes();
            byte[] response = ("{\"backend\":\"" + name + "\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        }

        /**
         * 清空测试后端记录的请求计数、头部及正文，为下一次独立断言建立起点。
         */
        void reset() {
            requests.set(0);
            lastHeaders.clear();
            lastBody = null;
        }

        /**
         * 返回当前测试后端的本地 HTTP 根地址，供网关路由配置绑定。
         */
        String baseUrl() {
            return "http://127.0.0.1:" + port;
        }

        /**
         * 返回后端实际接收的请求次数，判断请求是否在网关被拒绝或路由到唯一服务。
         */
        int requests() {
            return requests.get();
        }

        /**
         * 按不区分大小写的头名称读取最近请求值，供内部头清理及安全头透传断言。
         */
        String lastHeader(String name) {
            return lastHeaders.get(name.toLowerCase(java.util.Locale.ROOT));
        }

        /**
         * 返回最近请求的原始正文字节，检查 Webhook 签名覆盖的内容是否原样透传。
         */
        byte[] lastBody() {
            return lastBody;
        }
    }
}
