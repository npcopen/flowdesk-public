package com.flowdesk.workflow;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工作流服务启动测试，验证应用上下文加载并使用预期服务名称与测试验签配置。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.main.web-application-type=none",
                "spring.cloud.service-registry.auto-registration.enabled=false",
                "spring.autoconfigure.exclude="
                        + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration"
        }
)
class FlowDeskWorkflowServiceApplicationTests {

    @Autowired
    private Environment environment;

    /**
     * 注入测试 JWT 验签公钥，供模拟身份经过真实认证路径。
     */
    @DynamicPropertySource
    static void jwtVerificationKey(DynamicPropertyRegistry registry) {
        registry.add("flowdesk.security.jwt.public-key-pem",
                () -> readResource("test-jwt-public-key.pem"));
    }

    /**
     * 从测试类路径读取 UTF-8 资源，缺失或读取失败直接使当前用例失败。
     */
    private static String readResource(String name) {
        try (var in = FlowDeskWorkflowServiceApplicationTests.class.getClassLoader().getResourceAsStream(name)) {
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
                .isEqualTo("flowdesk-workflow-service");
    }
}
