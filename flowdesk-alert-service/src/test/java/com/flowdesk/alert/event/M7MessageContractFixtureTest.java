package com.flowdesk.alert.event;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证M7 冻结消息契约的行为约束；结合可控测试数据检查正常流程、失败边界或状态收敛，避免回归破坏既有契约。
 */
class M7MessageContractFixtureTest {
    /**
     * 验证冻结的三个消息契约绑定与线格式样例一致，防止 Topic、Tag 或契约引用意外漂移。
     */
    @Test void frozenBindingsMatchAllThreeWireFixtures() throws Exception {
        assertBinding(AlertEventContract.TICKET_CREATE_REQUESTED,
                "contracts/flowdesk-incident-ticket-create-requested-v1-wire.properties");
        assertBinding(AlertEventContract.TICKET_ESTABLISHED,
                "contracts/flowdesk-incident-ticket-established-v1-wire.properties");
        assertBinding(AlertEventContract.INCIDENT_RECOVERED,
                "contracts/flowdesk-incident-recovered-v1-wire.properties");
    }
    /**
     * 逐项比较消息契约绑定与样例中的生产者、Topic、Tag 和契约引用，定位线格式漂移。
     */
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
