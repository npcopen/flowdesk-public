package com.flowdesk.iam;

import org.junit.jupiter.api.Test;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证当前服务的 Spring Boot 启动配置的行为约束；结合可控测试数据检查正常流程、失败边界或状态收敛，避免回归破坏既有契约。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.cloud.service-registry.auto-registration.enabled=false",
                "spring.datasource.url=jdbc:h2:mem:iam-context;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.sql.init.mode=always",
                "spring.sql.init.schema-locations=classpath:schema-test.sql"
        }
)
class FlowDeskIamServiceApplicationTests {

    @Autowired
    private Environment environment;

    private static final KeyPair KEY_PAIR = newKeyPair();

    /**
     * 向测试环境注册 JWT 验签公钥，使服务上下文能验证测试令牌。
     */
    @DynamicPropertySource
    static void jwtVerificationKey(DynamicPropertyRegistry registry) {
        registry.add("flowdesk.security.jwt.private-key-pem", () -> privateKeyPem());
        registry.add("flowdesk.security.jwt.public-key-pem", () -> publicKeyPem());
        registry.add("flowdesk.security.cursor-token.hmac-key",
                () -> Base64.getEncoder().encodeToString(new byte[32]));
    }

    /**
     * 为测试生成 RSA 密钥对，签发器与验签配置共享同一对密钥。
     */
    private static KeyPair newKeyPair() {
        try {
            return KeyPairGenerator.getInstance("RSA").generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 将测试私钥编码为 PEM 文本，供与生产相同的密钥加载配置读取。
     */
    private static String privateKeyPem() {
        String nl = System.lineSeparator();
        String base64 = Base64.getEncoder().encodeToString(KEY_PAIR.getPrivate().getEncoded());
        return "-----BEGIN PRIVATE KEY-----" + nl + base64 + nl + "-----END PRIVATE KEY-----";
    }

    /**
     * 将测试公钥编码为 PEM 文本，使应用通过正常配置路径加载验签材料。
     */
    private static String publicKeyPem() {
        String nl = System.lineSeparator();
        String base64 = Base64.getEncoder().encodeToString(KEY_PAIR.getPublic().getEncoded());
        return "-----BEGIN PUBLIC KEY-----" + nl + base64 + nl + "-----END PUBLIC KEY-----";
    }

    /**
     * 验证 Spring 上下文可启动且应用名称符合当前服务约定。
     */
    @Test
    void startsWithExpectedApplicationName() {
        assertThat(environment.getProperty("spring.application.name"))
                .isEqualTo("flowdesk-iam-service");
    }
}
