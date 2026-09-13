package com.flowdesk.gateway;

import com.flowdesk.common.security.IssuedJwt;
import com.flowdesk.common.security.JwtIssuer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.loadbalancer.annotation.LoadBalancerClient;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the production default route URIs (lb://flowdesk-*-service) resolve
 * through spring-cloud-loadbalancer. The ordinary routing test overrides every route
 * with a direct localhost URI, so a missing load balancer dependency would go
 * unnoticed there. This test keeps the lb:// route and serves the IAM instance from
 * a fixed supplier, then asserts the request really reaches the backend.
 */
@SpringBootTest(
        classes = {FlowDeskGatewayApplication.class, GatewayLbRouteIntegrationTest.LbClientConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                // Only the lb route under test is reachable; others are left at their
                // defaults but unreachable because no supplier provides instances for them.
                "flowdesk.gateway.routes.iam=lb://flowdesk-iam-service"
        }
)
class GatewayLbRouteIntegrationTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    private static final KeyPair KEY_PAIR = newKeyPair();

    private static HttpServer backend;
    private static String backendUrl;

    @LocalServerPort
    private int gatewayPort;

    @DynamicPropertySource
    static void jwtPublicKey(DynamicPropertyRegistry registry) {
        registry.add("flowdesk.security.jwt.public-key-pem", () -> publicKeyPem());
        registry.add("spring.cloud.service-registry.auto-registration.enabled", () -> "false");
    }

    @BeforeAll
    static void startBackend() throws IOException {
        backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        backend.createContext("/", GatewayLbRouteIntegrationTest::handle);
        backend.start();
        backendUrl = "http://127.0.0.1:" + backend.getAddress().getPort();
    }

    @AfterAll
    static void stopBackend() {
        if (backend != null) {
            backend.stop(0);
        }
    }

    private static void handle(HttpExchange exchange) throws IOException {
        byte[] response = "{\"backend\":\"iam-via-lb\"}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    @Test
    void lbRouteResolvesThroughLoadBalancerToTheBackend() {
        JwtIssuer issuer = new JwtIssuer(
                KEY_PAIR.getPrivate(), "flowdesk-iam-service", "flowdesk-api", "flowdesk-internal", 900, 300);
        IssuedJwt jwt = issuer.issueHuman(TENANT, USER, java.util.Set.of(), java.util.Set.of());

        WebTestClient client = WebTestClient.bindToServer()
                .baseUrl("http://127.0.0.1:" + gatewayPort).build();

        client.get().uri("/api/v1/users/me")
                .header("Authorization", "Bearer " + jwt.token())
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).value(body -> assertThat(body).contains("iam-via-lb"));
    }

    /**
     * Per-client configuration: a fixed supplier that always returns the local test
     * backend as the single instance of flowdesk-iam-service.
     */
    @Configuration(proxyBeanMethods = false)
    @LoadBalancerClient(name = "flowdesk-iam-service", configuration = LbSupplierConfig.class)
    static class LbClientConfig {
    }

    @Configuration(proxyBeanMethods = false)
    static class LbSupplierConfig {

        @Bean
        ServiceInstanceListSupplier iamServiceInstanceSupplier() {
            return new ServiceInstanceListSupplier() {
                @Override
                public String getServiceId() {
                    return "flowdesk-iam-service";
                }

                @Override
                public Flux<List<ServiceInstance>> get() {
                    int port = Integer.parseInt(backendUrl.substring(backendUrl.lastIndexOf(':') + 1));
                    return Flux.just(List.of(new DefaultServiceInstance(
                            "iam-test-1", "flowdesk-iam-service", "127.0.0.1", port, false)));
                }
            };
        }
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
}
