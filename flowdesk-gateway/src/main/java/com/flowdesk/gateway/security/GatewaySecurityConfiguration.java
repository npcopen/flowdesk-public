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
 * 网关 JWT 验签配置：仅持有公钥，因此无法签发令牌。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FlowDeskJwtProperties.class)
public class GatewaySecurityConfiguration {

    /**
     * 加载网关 RSA 公钥并创建校验器，未配置公钥时阻止启动，确保受保护请求经过验签。
     */
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

    /** 使用独立的有界调度器执行 RSA 验签，避免耗尽 Netty 事件循环的处理时间。 */
    @Bean(destroyMethod = "dispose")
    public Scheduler gatewayJwtVerificationScheduler() {
        return Schedulers.newBoundedElastic(2, 256, "flowdesk-gateway-jwt-verify");
    }
}
