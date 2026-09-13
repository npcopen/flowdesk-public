package com.flowdesk.ticket.incident;

import com.flowdesk.ticket.sla.SlaIndexProjection;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods=false)
@EnableConfigurationProperties(IncidentConsumerProperties.class)
@ConditionalOnProperty(prefix="flowdesk.ticket.incident-entry",name="enabled",havingValue="true")
public class IncidentConsumerConfiguration{
    @Bean IncidentEnvelopeGuard incidentEnvelopeGuard(){return new IncidentEnvelopeGuard();}
    @Bean IncidentInboxRepository incidentInboxRepository(JdbcTemplate jdbc){return new IncidentInboxRepository(jdbc);}
    @Bean IncidentMessageConsumer incidentMessageConsumer(IncidentEnvelopeGuard guard,IncidentInboxRepository repository,
            PlatformTransactionManager tx,ObjectMapper json,java.time.Clock clock,SlaIndexProjection slaIndexProjection){
        return new IncidentMessageConsumer(guard,repository,tx,json,clock,slaIndexProjection);}
    @Bean IncidentDlqAcceptor incidentDlqAcceptor(IncidentEnvelopeGuard guard,IncidentInboxRepository repository,
            PlatformTransactionManager tx){return new IncidentDlqAcceptor(guard,repository,tx);}
    @Bean @ConditionalOnProperty(prefix="flowdesk.ticket.incident-consumer",name="enabled",havingValue="true")
    IncidentRocketMqConsumers incidentRocketMqConsumers(IncidentConsumerProperties p,IncidentMessageConsumer m,IncidentDlqAcceptor d){
        return new IncidentRocketMqConsumers(p,m,d);}
}
