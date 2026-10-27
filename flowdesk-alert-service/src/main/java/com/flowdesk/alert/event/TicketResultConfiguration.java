package com.flowdesk.alert.event;
import com.flowdesk.alert.service.impl.TicketResultProcessingServiceImpl;
import com.flowdesk.alert.service.TicketResultProcessingService;
import com.flowdesk.alert.mapper.IncidentMapper;
import com.flowdesk.alert.mapper.AlertMessageMapper;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.flowdesk.alert.mapper.TicketResultMapper;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 按入口开关组装 Ticket 关联结果的校验器、Inbox 仓储、业务消费者与死信接纳器。
 *
 * <p>Broker 订阅另受 consumer.enabled 控制：业务入口和持久化恢复能力可以独立于实时订阅装配。
 * 所有消费写入共用 Alert 的数据源与事务管理器，确保关联、Inbox、恢复记录及审计能够一起提交。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TicketResultProperties.class)
@ConditionalOnProperty(
        prefix = "flowdesk.alert.ticket-result.entry",
        name = "enabled",
        havingValue = "true")
class TicketResultConfiguration {
    /**
     * 向 Spring 容器提供 TicketResultEnvelopeGuard 组件，集中绑定持久化、认证配置或后台处理依赖，供对应业务入口复用。
     */
    @Bean
    TicketResultEnvelopeGuard ticketResultEnvelopeGuard() {
        return new TicketResultEnvelopeGuard();
    }

    /**
     * 向 Spring 容器提供 TicketResultProcessingServiceImpl 组件，集中绑定持久化、认证配置或后台处理依赖，供对应业务入口复用。
     */
    @Bean
    TicketResultProcessingService ticketResultProcessingService(TicketResultMapper mapper, IncidentMapper incidents,
            AlertMessageMapper messages, PlatformTransactionManager tx) {
        return new TicketResultProcessingServiceImpl(mapper, incidents, messages, tx);
    }

    /**
     * 向 Spring 容器提供 TicketResultConsumer 组件，集中绑定持久化、认证配置或后台处理依赖，供对应业务入口复用。
     */
    @Bean
    TicketResultConsumer ticketResultConsumer(
            TicketResultEnvelopeGuard g,
            TicketResultProcessingService r) {
        return new TicketResultConsumer(g, r);
    }

    /**
     * 向 Spring 容器提供 TicketResultDlqAcceptor 组件，集中绑定持久化、认证配置或后台处理依赖，供对应业务入口复用。
     */
    @Bean
    TicketResultDlqAcceptor ticketResultDlqAcceptor(
            TicketResultEnvelopeGuard g, TicketResultProcessingService r) {
        return new TicketResultDlqAcceptor(g, r);
    }

    /**
     * 向 Spring 容器提供 TicketResultRocketMqConsumers 组件，集中绑定持久化、认证配置或后台处理依赖，供对应业务入口复用。
     */
    @Bean
    @ConditionalOnProperty(
            prefix = "flowdesk.alert.ticket-result.consumer",
            name = "enabled",
            havingValue = "true")
    TicketResultRocketMqConsumers ticketResultRocketMqConsumers(
            TicketResultProperties p, TicketResultConsumer m, TicketResultDlqAcceptor d) {
        return new TicketResultRocketMqConsumers(p, m, d);
    }
}
