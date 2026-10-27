package com.flowdesk.alert.webhook;
import com.flowdesk.alert.mapper.WebhookIntegrationMapper;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import tools.jackson.databind.ObjectMapper;

/**
 * 在 Webhook 开关启用时组装集成候选查询、运行时密钥仓和专用认证过滤器。
 *
 * <p>过滤器只绑定监控告警入口。它位于公共关联编号、请求体限制之后，并排在人类 JWT 过滤器之前；
 * Webhook 路径由独立的 API key 与 HMAC 认证，公共 JWT 配置将该路径排除在登录身份认证之外。
 * 验签通过后由控制器解析报文、接纳服务执行本地事务，本配置不提前建立业务租户上下文。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WebhookProperties.class)
@ConditionalOnProperty(prefix = "flowdesk.alert.webhook", name = "enabled", havingValue = "true")
public class WebhookSecurityConfiguration {
    /**
     * 向 Spring 容器提供 WebhookSecretRegistry 组件，集中绑定持久化、认证配置或后台处理依赖，供对应业务入口复用。
     */
    @Bean
    WebhookSecretRegistry webhookSecretRegistry(WebhookProperties properties) {
        // Bean 创建时就校验配置和密钥文件，避免服务启动成功后才发现验签材料不可用。
        return new WebhookSecretRegistry(properties);
    }

/**
 * 向 Spring 容器提供 FilterRegistrationBean<WebhookAuthenticationFilter> 组件，集中绑定持久化、认证配置或后台处理依赖，供对应业务入口复用。
 */
@Bean
    FilterRegistrationBean<WebhookAuthenticationFilter> webhookAuthenticationFilter(
            WebhookIntegrationMapper integrations,
            WebhookSecretRegistry secrets,
            WebhookProperties properties,
            ObjectMapper json) {
        var registration = new FilterRegistrationBean<WebhookAuthenticationFilter>();
        registration.setFilter(
                new WebhookAuthenticationFilter(integrations, secrets, properties, json));
        registration.addUrlPatterns(WebhookAuthenticationFilter.PATH);
        // 公共过滤器依次为关联编号 +10、请求体策略 +15、人类 JWT +20；+18 保证先限制请求体大小再验签。
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 18);
        return registration;
    }
}
