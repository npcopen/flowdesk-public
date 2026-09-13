package com.flowdesk.ticket.reverse;

import org.apache.rocketmq.acl.common.AclClientRPCHook;
import org.apache.rocketmq.acl.common.SessionCredentials;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.remoting.RPCHook;
import org.apache.rocketmq.remoting.protocol.heartbeat.MessageModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/** Four independent groups: a main and acceptor for each frozen reverse event. */
public final class WorkflowReverseRocketMqConsumers implements SmartLifecycle {
    private static final Logger LOG = LoggerFactory.getLogger(
            WorkflowReverseRocketMqConsumers.class);
    private final WorkflowReverseRocketMqProperties properties;
    private final Function<WorkflowReverseTransportMessage, WorkflowReverseConsumeResult> main;
    private final Function<WorkflowReverseTransportMessage, WorkflowReverseConsumeResult> dlq;
    private final AtomicBoolean running = new AtomicBoolean();
    private final List<DefaultMQPushConsumer> consumers = new ArrayList<>();

    WorkflowReverseRocketMqConsumers(WorkflowReverseRocketMqProperties properties,
                                     WorkflowReverseConsumer main,
                                     WorkflowReverseDlqAcceptor dlq) {
        this.properties = properties; this.main = main::consume; this.dlq = dlq::accept;
    }

    @Override public synchronized void start() {
        if (running.get()) return;
        properties.validateEnabled();
        RPCHook acl = new AclClientRPCHook(new SessionCredentials(
                properties.getAccessKey(), properties.getSecretKey()));
        try {
            for (var binding : bindings()) {
                consumers.add(create(binding.dlqAcceptorGroup(), binding.dlqTopic(),
                        binding, true, acl, dlq));
            }
            for (var binding : bindings()) {
                consumers.add(create(binding.consumerGroup(), binding.topic(),
                        binding, false, acl, main));
            }
            for (DefaultMQPushConsumer consumer : consumers) consumer.start();
            running.set(true);
            LOG.info("Workflow reverse main consumers and DLQ acceptors are ready");
        }
        catch (Exception failure) {
            for (DefaultMQPushConsumer consumer : consumers) consumer.shutdown();
            consumers.clear();
            throw new IllegalStateException("Ticket reverse consumers failed closed", failure);
        }
    }

    private DefaultMQPushConsumer create(String group, String topic,
                                         WorkflowReverseContract.Binding binding,
                                         boolean acceptor, RPCHook acl,
                                         Function<WorkflowReverseTransportMessage,
                                                 WorkflowReverseConsumeResult> handler)
            throws Exception {
        return createConsumer(group, topic, binding, acceptor, acl,
                properties.getNameServer(), handler);
    }

    static DefaultMQPushConsumer createConsumer(
            String group, String topic, WorkflowReverseContract.Binding binding,
            boolean acceptor, RPCHook acl, String nameServer,
            Function<WorkflowReverseTransportMessage, WorkflowReverseConsumeResult> handler)
            throws Exception {
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer(group, acl);
        consumer.setNamesrvAddr(nameServer.trim());
        consumer.setInstanceName("flowdesk-ticket-reverse-" + binding.kind().name().toLowerCase()
                + (acceptor ? "-dlq-" : "-main-") + UUID.randomUUID());
        consumer.setVipChannelEnabled(false);
        // A newly provisioned M6 group must drain reverse facts that may have
        // been published while Ticket was starting. Existing groups continue
        // from their committed offset.
        consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
        consumer.setMessageModel(MessageModel.CLUSTERING);
        consumer.setConsumeThreadMin(1); consumer.setConsumeThreadMax(1);
        consumer.setConsumeMessageBatchMaxSize(1); consumer.setPullBatchSize(1);
        consumer.setMaxReconsumeTimes(acceptor
                ? WorkflowReverseContract.DLQ_ACCEPTOR_MAX_RECONSUME_TIMES
                : WorkflowReverseContract.MAX_RECONSUME_TIMES);
        consumer.subscribe(topic, "*");
        consumer.registerMessageListener((MessageListenerConcurrently) (messages, context) ->
                handleOne(binding, group, acceptor, handler, messages, context));
        return consumer;
    }

    static ConsumeConcurrentlyStatus handleOne(
            WorkflowReverseContract.Binding binding, String group, boolean acceptor,
            Function<WorkflowReverseTransportMessage, WorkflowReverseConsumeResult> handler,
            List<MessageExt> messages, ConsumeConcurrentlyContext context) {
        if (messages == null || messages.size() != 1) return retry(acceptor, context);
        try {
            MessageExt message = messages.get(0);
            String logicalTopic = message.getTopic();
            if (acceptor && context != null && context.getMessageQueue() != null
                    && binding.acceptorRetryTopic().equals(context.getMessageQueue().getTopic())) {
                logicalTopic = binding.dlqTopic();
            }
            WorkflowReverseConsumeResult result = handler.apply(new WorkflowReverseTransportMessage(
                    logicalTopic, group, message.getKeys(), message.getTags(), message.getBody(),
                    message.getProperties() == null ? Map.of() : message.getProperties()));
            return result.disposition() == WorkflowReverseConsumeResult.Disposition.ACK
                    ? ConsumeConcurrentlyStatus.CONSUME_SUCCESS : retry(acceptor, context);
        }
        catch (RuntimeException failure) { return retry(acceptor, context); }
    }

    private static ConsumeConcurrentlyStatus retry(boolean acceptor,
                                                    ConsumeConcurrentlyContext context) {
        if (acceptor && context != null) {
            context.setDelayLevelWhenNextConsume(
                    WorkflowReverseContract.DLQ_ACCEPTOR_RETRY_DELAY_LEVEL);
        }
        return ConsumeConcurrentlyStatus.RECONSUME_LATER;
    }
    private static WorkflowReverseContract.Binding[] bindings() {
        return new WorkflowReverseContract.Binding[]{WorkflowReverseContract.INSTANCE_ESTABLISHED,
                WorkflowReverseContract.APPROVAL_FINALIZED};
    }
    @Override public synchronized void stop() {
        running.set(false);
        for (DefaultMQPushConsumer consumer : consumers) consumer.shutdown();
        consumers.clear();
    }
    @Override public boolean isRunning() { return running.get(); }
    @Override public int getPhase() { return Integer.MIN_VALUE + 200; }
}
