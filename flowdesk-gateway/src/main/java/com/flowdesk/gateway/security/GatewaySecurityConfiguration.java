package com.flowdesk.gateway.security;

import com.flowdesk.common.security.FlowDeskJwtProperties;
import com.flowdesk.common.security.JwtVerifier;
import com.flowdesk.common.security.PemKeyMaterial;
import com.flowdesk.common.security.PemKeyReader;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * Gateway only holds public JWT verification material; it can never sign tokens.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FlowDeskJwtProperties.class)
public class GatewaySecurityConfiguration {

    @Bean
    public JwtVerifier gatewayJwtVerifier(FlowDeskJwtProperties properties,
                                           ResourceLoader resourceLoader) {
        String publicPem = PemKeyMaterial.resolve(
                properties.getPublicKeyPem(), properties.getPublicKeyLocation(), resourceLoader);
        if (publicPem.isBlank()) {
            throw new IllegalStateException(
                    "Gateway JWT public key is not configured (flowdesk.security.jwt.public-key-pem/public-key-location)");
        }
        return new JwtVerifier(
                PemKeyReader.readPublicKey(publicPem),
                properties.getIssuer(),
                properties.getHumanAudience(),
                properties.getServiceAudience(),
                properties.getClockSkew().toSeconds());
    }

    /** Dedicated bounded scheduler so RSA verification never starves Netty event loops. */
    @Bean(destroyMethod = "dispose")
    public Scheduler gatewayJwtVerificationScheduler() {
        return Schedulers.newBoundedElastic(2, 256, "flowdesk-gateway-jwt-verify");
    }
}
