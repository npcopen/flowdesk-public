package com.flowdesk.iam.security;

import com.flowdesk.common.security.FlowDeskJwtProperties;
import com.flowdesk.common.security.JwtIssuer;
import com.flowdesk.common.security.PemKeyMaterial;
import com.flowdesk.common.security.PemKeyReader;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;

import java.time.Clock;

/**
 * IAM-only signing configuration. The private key never leaves this deployment unit.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({LoginSecurityProperties.class, CursorTokenProperties.class, ServiceJwtBootstrapProperties.class})
public class IamSecurityConfiguration {

    /** All IAM business timestamps are derived from one UTC wall clock. */
    @Bean
    public Clock flowDeskIamClock() {
        return Clock.systemUTC();
    }

    @Bean
    public JwtIssuer iamJwtIssuer(FlowDeskJwtProperties properties, ResourceLoader resourceLoader) {
        String privatePem = PemKeyMaterial.resolve(
                properties.getPrivateKeyPem(), properties.getPrivateKeyLocation(), resourceLoader);
        if (privatePem.isBlank()) {
            throw new IllegalStateException(
                    "IAM JWT signing key is not configured (flowdesk.security.jwt.private-key-pem/private-key-location)");
        }
        return new JwtIssuer(
                PemKeyReader.readPrivateKey(privatePem),
                properties.getIssuer(),
                properties.getHumanAudience(),
                properties.getServiceAudience(),
                properties.getAccessTokenTtl().toSeconds(),
                properties.getServiceTokenTtl().toSeconds());
    }
}
