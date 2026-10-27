package com.flowdesk.ticket.incident;

import com.flowdesk.ticket.sla.SlaIndexProjection;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

/** 按部署开关装配 Incident 消费入口、Inbox 仓储和独立死信接收器。 */
@Configuration(proxyBeanMethods=false)
@EnableConfigurationProperties(IncidentConsumerProperties.class)
@ConditionalOnProperty(prefix="flowdesk.ticket.incident-entry",name="enabled",havingValue="true")
public class IncidentConsumerConfiguration{
    /**
     * 装配告警建单的 incidentEnvelopeGuard 组件，将配置与依赖交由 Spring 管理。
     */
    @Bean IncidentEnvelopeGuard incidentEnvelopeGuard(){return new IncidentEnvelopeGuard();}
    /**
     * 装配告警建单的 incidentInboxService 组件，将配置与依赖交由 Spring 管理。
     */
    @Bean IncidentInboxService incidentInboxService(IncidentInboxPersistenceMapper mapper){return new com.flowdesk.ticket.incident.impl.IncidentInboxServiceImpl(mapper);}
    /**
     * 装配告警建单的 incidentMessageConsumer 组件，将配置与依赖交由 Spring 管理。
     */
    @Bean IncidentMessageConsumer incidentMessageConsumer(IncidentEnvelopeGuard guard,IncidentInboxService repository,
            PlatformTransactionManager tx,ObjectMapper json,java.time.Clock clock,SlaIndexProjection slaIndexProjection){
        return new IncidentMessageConsumer(guard,repository,tx,json,clock,slaIndexProjection);}
    /**
     * 装配告警建单的 incidentDlqAcceptor 组件，将配置与依赖交由 Spring 管理。
     */
    @Bean IncidentDlqAcceptor incidentDlqAcceptor(IncidentEnvelopeGuard guard,IncidentInboxService repository,
            PlatformTransactionManager tx){return new IncidentDlqAcceptor(guard,repository,tx);}
    /**
     * 装配告警建单的 incidentRocketMqConsumers 组件，将配置与依赖交由 Spring 管理。
     */
    @Bean @ConditionalOnProperty(prefix="flowdesk.ticket.incident-consumer",name="enabled",havingValue="true")
    IncidentRocketMqConsumers incidentRocketMqConsumers(IncidentConsumerProperties p,IncidentMessageConsumer m,IncidentDlqAcceptor d){
        return new IncidentRocketMqConsumers(p,m,d);}
}
