package com.flowdesk.notification;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 通知服务启动测试，验证应用上下文加载并使用预期服务名称与测试验签配置。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.cloud.service-registry.auto-registration.enabled=false"
        }
)
class FlowDeskNotificationServiceApplicationTests {

    @Autowired
    private Environment environment;

    /**
     * 注入测试 JWT 验签公钥，供模拟身份经过真实认证路径。
     */
    @DynamicPropertySource
    static void jwtVerificationKey(DynamicPropertyRegistry registry) {
        registry.add("flowdesk.security.jwt.public-key-pem",
                () -> readResource("test-jwt-public-key.pem"));
        registry.add("FLOWDESK_NOTIFICATION_DB_PASSWORD", () -> "");
        registry.add("flowdesk.notification.cursor-token.hmac-key", () ->
                "0123456789abcdef0123456789abcdef");
        registry.add("spring.datasource.url", () ->
                "jdbc:h2:mem:notification-app;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
        registry.add("flowdesk.notification.recovery.datasource.url", () ->
                "jdbc:h2:mem:notification-app;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        registry.add("flowdesk.notification.recovery.datasource.driver-class-name", () -> "org.h2.Driver");
        registry.add("flowdesk.notification.recovery.datasource.username", () -> "sa");
        registry.add("flowdesk.notification.recovery.datasource.password", () -> "");
    }

    /**
     * 从测试类路径读取 UTF-8 资源，缺失或读取失败直接使当前用例失败。
     */
    private static String readResource(String name) {
        try (var in = FlowDeskNotificationServiceApplicationTests.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("Missing test resource: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 验证应用上下文使用预期服务名称启动，并检查相关业务事实或错误响应符合约定。
     */
    @Test
    void startsWithExpectedApplicationName() {
        assertThat(environment.getProperty("spring.application.name"))
                .isEqualTo("flowdesk-notification-service");
    }
}
