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
 * Gateway single-target routing, edge JWT first check, correlation normalization and
 * internal-header sanitization (ARCH-DEC-005, ARCHITECTURE §7.4, API-SPEC §2.4/§3.1).
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

    @BeforeEach
    void resetBackends() {
        IAM.reset();
        TICKET.reset();
        WORKFLOW.reset();
        ALERT.reset();
        NOTIFICATION.reset();
        client = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + gatewayPort).build();
    }

    @Test
    void spaHistoryRoutesAndAssetsAreServedWithoutHumanJwt() {
        client.get().uri("/login")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith("text/html")
                .expectBody(String.class).value(body -> assertThat(body).contains("FlowDesk shell"));

        client.get().uri("/tickets/FD-000001")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith("text/html");

        for (String settings : java.util.List.of("/settings/users", "/settings/catalog")) {
            client.get().uri(settings).exchange().expectStatus().isOk()
                    .expectHeader().contentTypeCompatibleWith("text/html");
        }

        client.get().uri("/assets/test.js")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith("text/javascript")
                .expectBody(String.class).value(body -> assertThat(body).contains("FLOWDESK_TEST_ASSET"));
    }

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

    @Test
    void apiPathsNeverFallBackToTheSpaShell() {
        client.get().uri("/api/v1/unknown")
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentTypeCompatibleWith("application/json")
                .expectBody(String.class).value(body -> assertThat(body).doesNotContain("FlowDesk shell"));
    }

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

    private static KeyPair newKeyPair() {
        try {
            return KeyPairGenerator.getInstance("RSA").generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String publicKeyPem() {
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                .encodeToString(KEY_PAIR.getPublic().getEncoded());
        return "-----BEGIN PUBLIC KEY-----\n" + base64 + "\n-----END PUBLIC KEY-----\n";
    }

    private static final class Backend {
        private final String name;
        private final AtomicInteger requests = new AtomicInteger();
        private final Map<String, String> lastHeaders = new ConcurrentHashMap<>();
        private volatile byte[] lastBody;
        private volatile HttpServer server;
        private volatile int port;

        private Backend(String name) {
            this.name = name;
        }

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

        void reset() {
            requests.set(0);
            lastHeaders.clear();
            lastBody = null;
        }

        String baseUrl() {
            return "http://127.0.0.1:" + port;
        }

        int requests() {
            return requests.get();
        }

        String lastHeader(String name) {
            return lastHeaders.get(name.toLowerCase(java.util.Locale.ROOT));
        }

        byte[] lastBody() {
            return lastBody;
        }
    }
}
