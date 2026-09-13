package com.flowdesk.workflow.start;

import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.common.message.MessageQueue;
import org.apache.rocketmq.remoting.protocol.heartbeat.MessageModel;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowStartRocketMqBindingTest {

    @Test
    void mainGroupHasFiveReconsumesAndDlqAcceptorCompensatesInheritedCount()
            throws Exception {
        DefaultMQPushConsumer main = WorkflowStartRocketMqConsumers.createConsumer(
                WorkflowStartContract.CONSUMER_GROUP,
                WorkflowStartContract.TOPIC,
                "test-main",
                null,
                "127.0.0.1:9876",
                message -> WorkflowStartConsumeResult.ack("ACK", null));
        DefaultMQPushConsumer acceptor = WorkflowStartRocketMqConsumers.createConsumer(
                WorkflowStartContract.DLQ_ACCEPTOR_GROUP,
                WorkflowStartContract.DLQ_TOPIC,
                "test-dlq",
                null,
                "127.0.0.1:9876",
                message -> WorkflowStartConsumeResult.ack("ACK", null));

        assertFrozen(main, WorkflowStartContract.CONSUMER_GROUP,
                WorkflowStartContract.MAX_RECONSUME_TIMES);
        assertFrozen(acceptor, WorkflowStartContract.DLQ_ACCEPTOR_GROUP,
                WorkflowStartContract.DLQ_ACCEPTOR_MAX_RECONSUME_TIMES);
        assertThat(WorkflowStartContract.DLQ_ACCEPTOR_MAX_RECONSUME_TIMES).isEqualTo(11);
        assertThat(WorkflowStartContract.TOPIC).isEqualTo("flowdesk_workflow_start_v1");
        assertThat(WorkflowStartContract.DLQ_TOPIC)
                .isEqualTo("%DLQ%flowdesk_workflow_start_v1_workflow_cg");
    }

    @Test
    void dlqAcceptorTransientFailureUsesFreshShortBrokerDelay() {
        MessageExt message = new MessageExt();
        message.setTopic(WorkflowStartContract.TOPIC);
        message.setKeys("018f6f2d-9b57-7c31-a69f-4db0d84db107");
        message.setBody(new byte[]{1});
        AtomicReference<WorkflowStartTransportMessage> captured = new AtomicReference<>();
        ConsumeConcurrentlyContext context = new ConsumeConcurrentlyContext(new MessageQueue(
                WorkflowStartContract.DLQ_ACCEPTOR_RETRY_TOPIC, "broker-a", 0));

        ConsumeConcurrentlyStatus result = WorkflowStartRocketMqConsumers.handleOne(
                WorkflowStartContract.DLQ_ACCEPTOR_GROUP,
                delivery -> {
                    captured.set(delivery);
                    return WorkflowStartConsumeResult.retry("DATABASE_TEMPORARY");
                },
                List.of(message), context);

        assertThat(result).isEqualTo(ConsumeConcurrentlyStatus.RECONSUME_LATER);
        assertThat(captured.get().topic()).isEqualTo(WorkflowStartContract.DLQ_TOPIC);
        assertThat(context.getDelayLevelWhenNextConsume())
                .isEqualTo(WorkflowStartContract.DLQ_ACCEPTOR_RETRY_DELAY_LEVEL);
    }

    @Test
    void listenerMapsOneResultDirectlyToBrokerWithoutInnerRetry() {
        MessageExt message = new MessageExt();
        message.setTopic(WorkflowStartContract.TOPIC);
        message.setKeys("018f6f2d-9b57-7c31-a69f-4db0d84db106");
        message.setBody(new byte[]{1});
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<WorkflowStartTransportMessage> captured = new AtomicReference<>();

        ConsumeConcurrentlyStatus retry = WorkflowStartRocketMqConsumers.handleOne(
                WorkflowStartContract.CONSUMER_GROUP,
                delivery -> {
                    calls.incrementAndGet();
                    captured.set(delivery);
                    return WorkflowStartConsumeResult.retry("TEMPORARY");
                }, List.of(message));
        ConsumeConcurrentlyStatus malformedBatch = WorkflowStartRocketMqConsumers.handleOne(
                WorkflowStartContract.CONSUMER_GROUP,
                delivery -> {
                    calls.incrementAndGet();
                    return WorkflowStartConsumeResult.ack("ACK", null);
                }, List.of(message, message));

        assertThat(retry).isEqualTo(ConsumeConcurrentlyStatus.RECONSUME_LATER);
        assertThat(malformedBatch).isEqualTo(ConsumeConcurrentlyStatus.RECONSUME_LATER);
        assertThat(calls.get()).isEqualTo(1);
        assertThat(captured.get().consumerGroup())
                .isEqualTo(WorkflowStartContract.CONSUMER_GROUP);
    }

    @Test
    void aclIdentityFailsClosedAndReplayBodyHasOnlyReason() {
        WorkflowStartRocketMqProperties valid = new WorkflowStartRocketMqProperties();
        valid.setEnabled(true);
        valid.setAccessKey("flowdeskworkflow");
        valid.setSecretKey("WorkflowSecret1234");
        valid.validateEnabledConfiguration();

        valid.setAccessKey("flowdeskticket");
        assertThatThrownBy(valid::validateEnabledConfiguration)
                .isInstanceOf(IllegalStateException.class);
        assertThat(WorkflowRecoveryController.ReplayCommand.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("reason");
        assertThatThrownBy(() -> new ObjectMapper().readValue(
                "{\"reason\":\"retry\",\"messageId\":\"forbidden\"}",
                WorkflowRecoveryController.ReplayCommand.class))
                .hasMessageContaining("messageId");
        assertThatThrownBy(() -> WorkflowRecoverySupport.requireReason("   "))
                .isInstanceOfSatisfying(com.flowdesk.common.web.ApiException.class,
                        error -> assertThat(error.code())
                                .isEqualTo("RECOVERY_REASON_REQUIRED"));
    }

    private static void assertFrozen(
            DefaultMQPushConsumer consumer, String group, int maxReconsumeTimes) {
        assertThat(consumer.getConsumerGroup()).isEqualTo(group);
        assertThat(consumer.getMessageModel()).isEqualTo(MessageModel.CLUSTERING);
        assertThat(consumer.getConsumeMessageBatchMaxSize()).isEqualTo(1);
        assertThat(consumer.getPullBatchSize()).isEqualTo(1);
        assertThat(consumer.getMaxReconsumeTimes()).isEqualTo(maxReconsumeTimes);
    }
}
