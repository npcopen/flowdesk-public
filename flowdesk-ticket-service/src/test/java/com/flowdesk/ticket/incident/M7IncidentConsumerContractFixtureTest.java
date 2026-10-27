package com.flowdesk.ticket.incident;

import org.junit.jupiter.api.Test;
import java.io.InputStream;import java.util.Properties;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工单模块回归测试，覆盖该组件的正常流程、边界条件与关键一致性约束。
 */
class M7IncidentConsumerContractFixtureTest{
    /**
     * 验证输入绑定与权威建单结果符合冻结契约夹具。
     */
    @Test void incomingBindingsAndAuthoritativeResultMatchFixtures()throws Exception{assertIncoming(IncidentMessageContract.CREATE_REQUESTED,"contracts/flowdesk-incident-ticket-create-requested-v1-wire.properties");assertIncoming(IncidentMessageContract.RECOVERED,"contracts/flowdesk-incident-recovered-v1-wire.properties");Properties p=load("contracts/flowdesk-incident-ticket-established-v1-wire.properties");assertThat(IncidentMessageContract.ESTABLISHED_EVENT_TYPE).isEqualTo(p.getProperty("eventType"));assertThat(IncidentMessageContract.ESTABLISHED_CONTRACT_REF).isEqualTo(p.getProperty("contractRef"));assertThat(IncidentMessageContract.ESTABLISHED_TOPIC).isEqualTo(p.getProperty("topic"));assertThat(IncidentMessageContract.ESTABLISHED_PRODUCER_GROUP).isEqualTo(p.getProperty("producerGroup"));assertThat(IncidentMessageContract.ESTABLISHED_INTENT_NAMESPACE).isEqualTo(p.getProperty("intentNamespace"));}
    /**
     * 断言输入消息绑定与指定契约夹具的主题、类型和版本一致。
     */
    private static void assertIncoming(IncidentMessageContract.Binding b,String resource)throws Exception{Properties p=load(resource);assertThat(b.eventType()).isEqualTo(p.getProperty("eventType"));assertThat(b.contractRef()).isEqualTo(p.getProperty("contractRef"));assertThat(b.topic()).isEqualTo(p.getProperty("topic"));assertThat(b.consumerGroup()).isEqualTo(p.getProperty("consumerGroup"));assertThat(b.dlqAcceptorGroup()).isEqualTo(p.getProperty("dlqAcceptorGroup"));assertThat(b.intentNamespace()).isEqualTo(p.getProperty("intentNamespace"));}
    /**
     * 读取冻结契约资源，供独立消费守卫验证消息兼容性。
     */
    private static Properties load(String resource)throws Exception{Properties p=new Properties();try(InputStream in=M7IncidentConsumerContractFixtureTest.class.getClassLoader().getResourceAsStream(resource)){p.load(in);}return p;}
}
