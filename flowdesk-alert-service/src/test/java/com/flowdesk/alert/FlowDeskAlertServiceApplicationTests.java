package com.flowdesk.alert;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import javax.sql.DataSource;
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
                "spring.datasource.url=jdbc:h2:mem:alert-app;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.sql.init.mode=never",
                "FLOWDESK_ALERT_DB_PASSWORD="
        }
)
class FlowDeskAlertServiceApplicationTests {

    @Autowired
    private Environment environment;

    @Autowired
    private DataSource dataSource;

    @DynamicPropertySource
    static void jwtVerificationKey(DynamicPropertyRegistry registry) {
        registry.add("flowdesk.security.jwt.public-key-pem",
                () -> readResource("test-jwt-public-key.pem"));
        registry.add("flowdesk.security.cursor-token.hmac-key", () -> java.util.Base64.getEncoder()
                .encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)));
    }

    private static String readResource(String name) {
        try (var in = FlowDeskAlertServiceApplicationTests.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("Missing test resource: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void startsWithExpectedApplicationName() {
        assertThat(environment.getProperty("spring.application.name"))
                .isEqualTo("flowdesk-alert-service");
    }

    @Test
    void sizesWebhookPoolAndPinsReadCommittedAtTheConnectionBoundary() {
        assertThat(dataSource).isInstanceOf(HikariDataSource.class);
        HikariDataSource hikari = (HikariDataSource) dataSource;
        assertThat(hikari.getMaximumPoolSize()).isEqualTo(64);
        assertThat(hikari.getMinimumIdle()).isEqualTo(16);
        assertThat(hikari.getTransactionIsolation()).isEqualTo("TRANSACTION_READ_COMMITTED");
    }
}
