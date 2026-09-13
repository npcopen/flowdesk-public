package com.flowdesk.ticket.incident;

import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.common.message.MessageQueue;
import org.apache.rocketmq.remoting.protocol.heartbeat.MessageModel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IncidentRocketMqBindingTest {
    @Test
    void bothBindingsUseIndependentUnorderedSingleMessageMainAndAcceptorGroups()
            throws Exception {
        for (var binding : List.of(IncidentMessageContract.CREATE_REQUESTED,
                IncidentMessageContract.RECOVERED)) {
            DefaultMQPushConsumer main = IncidentRocketMqConsumers.createConsumer(
                    binding, false, null, "127.0.0.1:9876",
                    delivery -> IncidentConsumeResult.ack("ACK"));
            DefaultMQPushConsumer acceptor = IncidentRocketMqConsumers.createConsumer(
                    binding, true, null, "127.0.0.1:9876",
                    delivery -> IncidentConsumeResult.ack("ACK"));
            assertFrozen(main, binding.consumerGroup(),
                    IncidentMessageContract.MAX_RECONSUME_TIMES);
            assertFrozen(acceptor, binding.dlqAcceptorGroup(),
                    IncidentMessageContract.DLQ_ACCEPTOR_MAX_RECONSUME_TIMES);
        }
    }

    @Test
    void controlledMainRetryDelayDoesNotChangeTheDlqAcceptorDelay() {
        var binding = IncidentMessageContract.RECOVERED;
        MessageExt message = new MessageExt();
        message.setTopic(binding.topic());
        message.setBody(new byte[]{1});
        ConsumeConcurrentlyContext mainContext = new ConsumeConcurrentlyContext(
                new MessageQueue(binding.topic(), "broker-a", 0));

        ConsumeConcurrentlyStatus main = IncidentRocketMqConsumers.handle(
                binding, binding.consumerGroup(), false, 2,
                delivery -> IncidentConsumeResult.retry("DEPENDENCY"),
                List.of(message), mainContext);

        assertThat(main).isEqualTo(ConsumeConcurrentlyStatus.RECONSUME_LATER);
        assertThat(mainContext.getDelayLevelWhenNextConsume()).isEqualTo(2);

        ConsumeConcurrentlyContext acceptorContext = new ConsumeConcurrentlyContext(
                new MessageQueue(binding.acceptorRetryTopic(), "broker-a", 0));
        ConsumeConcurrentlyStatus acceptor = IncidentRocketMqConsumers.handle(
                binding, binding.dlqAcceptorGroup(), true, 18,
                delivery -> IncidentConsumeResult.retry("DATABASE"),
                List.of(message), acceptorContext);
        assertThat(acceptor).isEqualTo(ConsumeConcurrentlyStatus.RECONSUME_LATER);
        assertThat(acceptorContext.getDelayLevelWhenNextConsume())
                .isEqualTo(IncidentMessageContract.DLQ_ACCEPTOR_RETRY_DELAY_LEVEL);
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
