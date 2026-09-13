package com.flowdesk.ticket;

import com.flowdesk.common.security.IssuedJwt;
import com.flowdesk.common.security.JwtIssuer;
import com.flowdesk.common.tenant.TenantContextHolder;
import com.flowdesk.common.web.HumanJwtAuthenticationFilter;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.cloud.service-registry.auto-registration.enabled=false",
                "spring.datasource.url=jdbc:h2:mem:ticket-app;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.sql.init.mode=never",
                "FLOWDESK_TICKET_DB_PASSWORD="
        }
)
class FlowDeskTicketServiceApplicationTests {

    @Autowired
    private Environment environment;
    @Autowired
    private ApplicationContext applicationContext;

    private static final KeyPair KEY_PAIR = newKeyPair();
    private static final JwtIssuer ISSUER = new JwtIssuer(
            KEY_PAIR.getPrivate(), "flowdesk-iam-service",
            "flowdesk-api", "flowdesk-internal", 900, 300);

    @DynamicPropertySource
    static void jwtVerificationKey(DynamicPropertyRegistry registry) {
        registry.add("flowdesk.security.jwt.public-key-pem", () -> publicKeyPem());
        registry.add("flowdesk.security.cursor-token.hmac-key", () -> java.util.Base64.getEncoder()
                .encodeToString("0123456789abcdef0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private static KeyPair newKeyPair() {
        try {
            return KeyPairGenerator.getInstance("RSA").generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String publicKeyPem() {
        String nl = System.lineSeparator();
        String base64 = Base64.getEncoder().encodeToString(KEY_PAIR.getPublic().getEncoded());
        return "-----BEGIN PUBLIC KEY-----" + nl + base64 + nl + "-----END PUBLIC KEY-----";
    }

    @Test
    void startsWithExpectedApplicationName() {
        assertThat(environment.getProperty("spring.application.name"))
                .isEqualTo("flowdesk-ticket-service");
    }

    @Test
    void targetServiceLocallyReverifiesHumanJwtAndCleansContext() throws Exception {
        FilterRegistrationBean<?> registration = applicationContext.getBean(
                "humanJwtAuthenticationFilterRegistration", FilterRegistrationBean.class);
        HumanJwtAuthenticationFilter filter = (HumanJwtAuthenticationFilter) registration.getFilter();

        MockHttpServletRequest missing = new MockHttpServletRequest("GET", "/api/v1/tickets");
        MockHttpServletResponse missingResponse = new MockHttpServletResponse();
        MockFilterChain missingChain = new MockFilterChain();
        filter.doFilter(missing, missingResponse, missingChain);
        assertThat(missingResponse.getStatus()).isEqualTo(401);
        assertThat(missingResponse.getContentAsString()).contains("AUTHENTICATION_REQUIRED");
        assertThat(missingChain.getRequest()).isNull();

        UUID tenant = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID user = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        IssuedJwt jwt = ISSUER.issueHuman(tenant, user, Set.of(), Set.of());
        MockHttpServletRequest valid = new MockHttpServletRequest("GET", "/api/v1/tickets");
        valid.addHeader("Authorization", "Bearer " + jwt.token());
        MockHttpServletResponse validResponse = new MockHttpServletResponse();
        MockFilterChain validChain = new MockFilterChain();
        filter.doFilter(valid, validResponse, validChain);
        assertThat(validResponse.getStatus()).isEqualTo(200);
        assertThat(validChain.getRequest()).isNotNull();
        assertThat(TenantContextHolder.currentOrNull()).isNull();
    }
}
