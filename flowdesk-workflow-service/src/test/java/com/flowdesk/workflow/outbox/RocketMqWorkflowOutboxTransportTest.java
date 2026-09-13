package com.flowdesk.workflow.outbox;

import com.flowdesk.workflow.event.WorkflowReverseEventContract;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RocketMqWorkflowOutboxTransportTest {
    @Test
    void eachEventUsesItsOwnFrozenProducerGroupAndNoSdkRetry() {
        assertProducer(RocketMqWorkflowOutboxTransport.producer(
                WorkflowReverseEventContract.InstanceEstablished.PRODUCER_GROUP,
                "established", "127.0.0.1:9876", null),
                WorkflowReverseEventContract.InstanceEstablished.PRODUCER_GROUP);
        assertProducer(RocketMqWorkflowOutboxTransport.producer(
                WorkflowReverseEventContract.ApprovalFinalized.PRODUCER_GROUP,
                "finalized", "127.0.0.1:9876", null),
                WorkflowReverseEventContract.ApprovalFinalized.PRODUCER_GROUP);
    }

    @Test
    void workflowPublisherIdentityFailsClosedBeforeAProducerStarts() {
        WorkflowOutboxPublisherProperties properties = new WorkflowOutboxPublisherProperties();
        properties.setEnabled(true);
        properties.setAccessKey("flowdeskworkflow");
        properties.setSecretKey("WorkflowSecret1234");
        properties.validateEnabled();

        properties.setAccessKey("flowdeskticket");
        assertThatThrownBy(properties::validateEnabled).isInstanceOf(IllegalStateException.class);
    }

    private static void assertProducer(DefaultMQProducer producer, String group) {
        assertThat(producer.getProducerGroup()).isEqualTo(group);
        assertThat(producer.getNamesrvAddr()).isEqualTo("127.0.0.1:9876");
        assertThat(producer.getSendMsgTimeout()).isEqualTo(5000);
        assertThat(producer.getRetryTimesWhenSendFailed()).isZero();
        assertThat(producer.getRetryTimesWhenSendAsyncFailed()).isZero();
        assertThat(producer.isRetryAnotherBrokerWhenNotStoreOK()).isFalse();
        assertThat(producer.isVipChannelEnabled()).isFalse();
    }
}
