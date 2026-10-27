package com.flowdesk.alert.config;

import com.flowdesk.alert.mapper.*;
import com.flowdesk.alert.service.AlertCursorTokenService;
import com.flowdesk.alert.service.AlertQueryService;
import com.flowdesk.alert.service.impl.AlertQueryServiceImpl;
import com.flowdesk.alert.service.WebhookAcceptanceService;
import com.flowdesk.alert.service.impl.WebhookAcceptanceServiceImpl;
import com.flowdesk.alert.webhook.WebhookProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

/**
 * 组装告警核心业务：Mapper 与查询服务始终可用，Webhook 接纳服务受开关控制。
 *
 * <p>事务管理器交给接纳服务统一控制跨表提交，仓储自身不拆成多个事务。
 * MQ 发布、工单结果消费和人工恢复各有独立配置类，可从对应能力包继续阅读。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
    AlertCursorTokenService.CursorProperties.class,
    WebhookProperties.class
})
public class AlertConfiguration {
    /**
     * 向 Spring 容器提供 AlertQueryServiceImpl 组件，集中绑定持久化、认证配置或后台处理依赖，供对应业务入口复用。
     */
    @Bean
    AlertQueryService alertQueryService(
            AlertEventMapper events, IncidentMapper incidents, AlertMessageMapper messages, AlertCursorTokenService cursors) {
        return new AlertQueryServiceImpl(events, incidents, messages, cursors);
    }

    /**
     * 向 Spring 容器提供 WebhookAcceptanceServiceImpl 组件，集中绑定持久化、认证配置或后台处理依赖，供对应业务入口复用。
     */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            prefix = "flowdesk.alert.webhook",
            name = "enabled",
            havingValue = "true")
    WebhookAcceptanceService webhookAcceptanceService(
            AlertEventMapper events, IncidentMapper incidents, AlertMessageMapper messages, AlertAuditMapper audits,
            PlatformTransactionManager transactions,
            WebhookProperties properties,
            ObjectMapper json) {
        return new WebhookAcceptanceServiceImpl(events, incidents, messages, audits, transactions, properties, json);
    }
}
