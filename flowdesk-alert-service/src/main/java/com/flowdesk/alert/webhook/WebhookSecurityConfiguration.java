package com.flowdesk.alert.webhook;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WebhookProperties.class)
@ConditionalOnProperty(prefix = "flowdesk.alert.webhook", name = "enabled", havingValue = "true")
public class WebhookSecurityConfiguration {
    @Bean WebhookSecretRegistry webhookSecretRegistry(WebhookProperties properties) {
        return new WebhookSecretRegistry(properties);
    }
    @Bean WebhookIntegrationRepository webhookIntegrationRepository(JdbcTemplate jdbc) {
        return new WebhookIntegrationRepository(jdbc);
    }
    @Bean FilterRegistrationBean<WebhookAuthenticationFilter> webhookAuthenticationFilter(
            WebhookIntegrationRepository integrations, WebhookSecretRegistry secrets,
            WebhookProperties properties, ObjectMapper json) {
        var registration = new FilterRegistrationBean<WebhookAuthenticationFilter>();
        registration.setFilter(new WebhookAuthenticationFilter(integrations, secrets, properties, json));
        registration.addUrlPatterns(WebhookAuthenticationFilter.PATH);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 18);
        return registration;
    }
}
