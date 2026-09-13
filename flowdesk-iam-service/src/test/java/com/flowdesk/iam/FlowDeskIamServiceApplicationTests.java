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

    @DynamicPropertySource
    static void jwtVerificationKey(DynamicPropertyRegistry registry) {
        registry.add("flowdesk.security.jwt.private-key-pem", () -> privateKeyPem());
        registry.add("flowdesk.security.jwt.public-key-pem", () -> publicKeyPem());
        registry.add("flowdesk.security.cursor-token.hmac-key",
                () -> Base64.getEncoder().encodeToString(new byte[32]));
    }

    private static KeyPair newKeyPair() {
        try {
            return KeyPairGenerator.getInstance("RSA").generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String privateKeyPem() {
        String nl = System.lineSeparator();
        String base64 = Base64.getEncoder().encodeToString(KEY_PAIR.getPrivate().getEncoded());
        return "-----BEGIN PRIVATE KEY-----" + nl + base64 + nl + "-----END PRIVATE KEY-----";
    }

    private static String publicKeyPem() {
        String nl = System.lineSeparator();
        String base64 = Base64.getEncoder().encodeToString(KEY_PAIR.getPublic().getEncoded());
        return "-----BEGIN PUBLIC KEY-----" + nl + base64 + nl + "-----END PUBLIC KEY-----";
    }

    @Test
    void startsWithExpectedApplicationName() {
        assertThat(environment.getProperty("spring.application.name"))
                .isEqualTo("flowdesk-iam-service");
    }
}
