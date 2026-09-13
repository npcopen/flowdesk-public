package com.flowdesk.alert.event;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class M7MessageContractFixtureTest {
    @Test void frozenBindingsMatchAllThreeWireFixtures() throws Exception {
        assertBinding(AlertEventContract.TICKET_CREATE_REQUESTED,
                "contracts/flowdesk-incident-ticket-create-requested-v1-wire.properties");
        assertBinding(AlertEventContract.TICKET_ESTABLISHED,
                "contracts/flowdesk-incident-ticket-established-v1-wire.properties");
        assertBinding(AlertEventContract.INCIDENT_RECOVERED,
                "contracts/flowdesk-incident-recovered-v1-wire.properties");
    }
    private static void assertBinding(AlertEventContract.Binding binding,String resource)throws Exception{
        Properties p=new Properties();try(InputStream in=M7MessageContractFixtureTest.class.getClassLoader().getResourceAsStream(resource)){p.load(in);}
        assertThat(binding.eventType()).isEqualTo(p.getProperty("eventType"));
        assertThat(binding.contractRef()).isEqualTo(p.getProperty("contractRef"));
        assertThat(binding.topic()).isEqualTo(p.getProperty("topic"));
        assertThat(binding.producer()).isEqualTo(p.getProperty("producer"));
        assertThat(binding.producerGroup()).isEqualTo(p.getProperty("producerGroup"));
        assertThat(binding.consumerGroup()).isEqualTo(p.getProperty("consumerGroup"));
        assertThat(binding.dlqAcceptorGroup()).isEqualTo(p.getProperty("dlqAcceptorGroup"));
        assertThat(binding.intentNamespace()).isEqualTo(p.getProperty("intentNamespace"));
    }
}
