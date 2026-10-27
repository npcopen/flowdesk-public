package com.flowdesk.gateway;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证网关 Spring 上下文在测试验签配置下可启动，并使用预期应用名称。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.cloud.service-registry.auto-registration.enabled=false"
)
class FlowDeskGatewayApplicationTests {

    @Autowired
    private Environment environment;

    /**
     * 向测试环境注册验签公钥，使服务沿正常安全配置路径加载测试密钥。
     */
    @DynamicPropertySource
    static void jwtVerificationKey(DynamicPropertyRegistry registry) {
        registry.add("flowdesk.security.jwt.public-key-pem",
                () -> readResource("test-jwt-public-key.pem"));
    }

    /**
     * 将类路径中的测试密钥读取为 UTF-8 文本，缺失或读取失败时终止测试初始化。
     */
    private static String readResource(String name) {
        try (var in = FlowDeskGatewayApplicationTests.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("Missing test resource: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 验证 Spring 上下文可启动且应用名称符合当前网关模块约定。
     */
    @Test
    void startsWithExpectedApplicationName() {
        assertThat(environment.getProperty("spring.application.name"))
                .isEqualTo("flowdesk-gateway");
    }
}
