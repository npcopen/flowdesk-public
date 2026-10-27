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
 * IAM 专属的 JWT 签发配置；私钥保留在 IAM 部署单元内。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({LoginSecurityProperties.class, CursorTokenProperties.class, ServiceJwtBootstrapProperties.class})
public class IamSecurityConfiguration {

    /**
     * IAM 业务时间统一来自同一个 UTC 时钟。
     */
    @Bean
    public Clock flowDeskIamClock() {
        return Clock.systemUTC();
    }

    /**
     * 向 Spring 容器提供 JwtIssuer 组件，集中绑定持久化、认证配置或后台处理依赖，供对应业务入口复用。
     */
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
