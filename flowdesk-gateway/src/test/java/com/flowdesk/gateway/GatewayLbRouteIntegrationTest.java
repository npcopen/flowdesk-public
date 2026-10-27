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
  * 保留生产默认 lb 服务地址，验证请求真实经过 Spring Cloud LoadBalancer 到达固定 IAM 实例，覆盖直连路由测试无法发现的依赖缺失。
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

    /**
     * 注册测试 JWT 公钥并关闭自动服务注册，让负载均衡路由测试使用本地固定实例。
     */
    @DynamicPropertySource
    static void jwtPublicKey(DynamicPropertyRegistry registry) {
        registry.add("flowdesk.security.jwt.public-key-pem", () -> publicKeyPem());
        registry.add("spring.cloud.service-registry.auto-registration.enabled", () -> "false");
    }

    /**
     * 启动随机本地端口的 HTTP 测试后端并记录地址，供保留 lb 协议的网关路由解析。
     */
    @BeforeAll
    static void startBackend() throws IOException {
        backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        backend.createContext("/", GatewayLbRouteIntegrationTest::handle);
        backend.start();
        backendUrl = "http://127.0.0.1:" + backend.getAddress().getPort();
    }

    /**
     * 测试结束后停止本地 HTTP 后端并释放监听端口。
     */
    @AfterAll
    static void stopBackend() {
        if (backend != null) {
            backend.stop(0);
        }
    }

    /**
     * 读取网关转发请求并返回可识别的 JSON 后端标记；记录必要请求信息供路由和透传断言。
     */
    private static void handle(HttpExchange exchange) throws IOException {
        byte[] response = "{\"backend\":\"iam-via-lb\"}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    /**
     * 验证生产默认 lb 服务地址经 Spring Cloud LoadBalancer 解析后真正到达 IAM 测试后端。
     */
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
     * 为 IAM 负载均衡客户端绑定测试供应器配置，使当前客户端使用固定本地后端实例。
     */
    @Configuration(proxyBeanMethods = false)
    @LoadBalancerClient(name = "flowdesk-iam-service", configuration = LbSupplierConfig.class)
    static class LbClientConfig {
    }

    /**
     * 提供仅用于测试的 IAM 服务实例供应器，保留 lb 路由的解析机制而隔离外部注册中心。
     */
    @Configuration(proxyBeanMethods = false)
    static class LbSupplierConfig {

        /**
         * 向测试容器提供固定 IAM 服务实例列表，隔离注册中心并保留真实负载均衡解析路径。
         */
        @Bean
        ServiceInstanceListSupplier iamServiceInstanceSupplier() {
            return new ServiceInstanceListSupplier() {
                /**
                 * 返回该固定供应器负责的 IAM 服务标识，使负载均衡客户端只匹配当前测试路由。
                 */
                @Override
                public String getServiceId() {
                    return "flowdesk-iam-service";
                }

                /**
                 * 返回固定本地 IAM 实例列表的响应式流，供网关的 lb 路由选择可达测试后端。
                 */
                @Override
                public Flux<List<ServiceInstance>> get() {
                    int port = Integer.parseInt(backendUrl.substring(backendUrl.lastIndexOf(':') + 1));
                    return Flux.just(List.of(new DefaultServiceInstance(
                            "iam-test-1", "flowdesk-iam-service", "127.0.0.1", port, false)));
                }
            };
        }
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
}
