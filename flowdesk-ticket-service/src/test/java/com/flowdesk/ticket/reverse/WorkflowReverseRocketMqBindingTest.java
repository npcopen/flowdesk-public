package com.flowdesk.ticket.reverse;

import com.flowdesk.ticket.recovery.TicketRecoveryController;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.common.message.MessageQueue;
import org.apache.rocketmq.remoting.protocol.heartbeat.MessageModel;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowReverseRocketMqBindingTest {
    @Test
    void bothBindingsUseIndependentUnorderedSingleMessageMainAndAcceptorGroups()
            throws Exception {
        for (var binding : List.of(WorkflowReverseContract.INSTANCE_ESTABLISHED,
                WorkflowReverseContract.APPROVAL_FINALIZED)) {
            DefaultMQPushConsumer main = consumer(binding, false);
            DefaultMQPushConsumer acceptor = consumer(binding, true);
            assertFrozen(main, binding.consumerGroup(),
                    WorkflowReverseContract.MAX_RECONSUME_TIMES);
            assertFrozen(acceptor, binding.dlqAcceptorGroup(),
                    WorkflowReverseContract.DLQ_ACCEPTOR_MAX_RECONSUME_TIMES);
        }
    }

    @Test
    void acceptorRetryRestoresTheLogicalDlqTopicAndUsesAFreshShortDelay() {
        var binding = WorkflowReverseContract.INSTANCE_ESTABLISHED;
        MessageExt message = new MessageExt();
        message.setTopic(binding.dlqTopic());
        message.setBody(new byte[]{1});
        AtomicReference<WorkflowReverseTransportMessage> captured = new AtomicReference<>();
        ConsumeConcurrentlyContext context = new ConsumeConcurrentlyContext(new MessageQueue(
                binding.acceptorRetryTopic(), "broker-a", 0));

        ConsumeConcurrentlyStatus status = WorkflowReverseRocketMqConsumers.handleOne(
                binding, binding.dlqAcceptorGroup(), true, delivery -> {
                    captured.set(delivery);
                    return WorkflowReverseConsumeResult.retry("DATABASE_TEMPORARY");
                }, List.of(message), context);

        assertThat(status).isEqualTo(ConsumeConcurrentlyStatus.RECONSUME_LATER);
        assertThat(captured.get().topic()).isEqualTo(binding.dlqTopic());
        assertThat(context.getDelayLevelWhenNextConsume())
                .isEqualTo(WorkflowReverseContract.DLQ_ACCEPTOR_RETRY_DELAY_LEVEL);
    }

    @Test
    void ticketAclIdentityAndRecoveryBodyFailClosed() throws Exception {
        WorkflowReverseRocketMqProperties properties = new WorkflowReverseRocketMqProperties();
        properties.setEnabled(true);
        properties.setAccessKey("flowdeskticket");
        properties.setSecretKey("TicketSecret12345");
        properties.validateEnabled();

        properties.setAccessKey("flowdeskworkflow");
        assertThatThrownBy(properties::validateEnabled).isInstanceOf(IllegalStateException.class);
        assertThat(TicketRecoveryController.ReplayCommand.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("reason");
        assertThatThrownBy(() -> new ObjectMapper().readValue(
                "{\"reason\":\"retry\",\"topic\":\"forbidden\"}",
                TicketRecoveryController.ReplayCommand.class))
                .hasMessageContaining("topic");
    }

    private static DefaultMQPushConsumer consumer(WorkflowReverseContract.Binding binding,
                                                   boolean acceptor) throws Exception {
        return WorkflowReverseRocketMqConsumers.createConsumer(
                acceptor ? binding.dlqAcceptorGroup() : binding.consumerGroup(),
                acceptor ? binding.dlqTopic() : binding.topic(), binding, acceptor,
                null, "127.0.0.1:9876", delivery -> WorkflowReverseConsumeResult.ack("ACK"));
    }

    private static void assertFrozen(DefaultMQPushConsumer consumer, String group,
                                     int maxReconsumeTimes) {
        assertThat(consumer.getConsumerGroup()).isEqualTo(group);
        assertThat(consumer.getMessageModel()).isEqualTo(MessageModel.CLUSTERING);
        assertThat(consumer.getConsumeFromWhere()).isEqualTo(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
        assertThat(consumer.getConsumeMessageBatchMaxSize()).isEqualTo(1);
        assertThat(consumer.getPullBatchSize()).isEqualTo(1);
        assertThat(consumer.getMaxReconsumeTimes()).isEqualTo(maxReconsumeTimes);
    }
}
