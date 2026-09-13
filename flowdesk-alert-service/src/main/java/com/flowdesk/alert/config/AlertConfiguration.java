package com.flowdesk.alert.config;

import com.flowdesk.alert.repository.AlertRepository;
import com.flowdesk.alert.service.AlertCursorTokenService;
import com.flowdesk.alert.service.AlertQueryService;
import com.flowdesk.alert.service.WebhookAcceptanceService;
import com.flowdesk.alert.webhook.WebhookProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods=false)
@EnableConfigurationProperties({AlertCursorTokenService.CursorProperties.class,
        WebhookProperties.class})
public class AlertConfiguration {
    @Bean AlertRepository alertRepository(JdbcTemplate jdbc){return new AlertRepository(jdbc);}
    @Bean AlertQueryService alertQueryService(AlertRepository repository,AlertCursorTokenService cursors){
        return new AlertQueryService(repository,cursors);
    }
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            prefix="flowdesk.alert.webhook",name="enabled",havingValue="true")
    WebhookAcceptanceService webhookAcceptanceService(AlertRepository repository,
            PlatformTransactionManager transactions,WebhookProperties properties,ObjectMapper json){
        return new WebhookAcceptanceService(repository,transactions,properties,json);
    }
}
