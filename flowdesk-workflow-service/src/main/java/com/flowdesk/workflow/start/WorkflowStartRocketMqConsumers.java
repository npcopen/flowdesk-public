package com.flowdesk.workflow.start;

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

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Classic-client bindings for the main and independent DLQ-acceptor groups.
 * The acceptor starts first; neither listener performs an in-process retry.
 */
public final class WorkflowStartRocketMqConsumers implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(WorkflowStartRocketMqConsumers.class);

    private final WorkflowStartRocketMqProperties properties;
    private final Function<WorkflowStartTransportMessage, WorkflowStartConsumeResult> mainHandler;
    private final Function<WorkflowStartTransportMessage, WorkflowStartConsumeResult> dlqHandler;
    private final AtomicBoolean running = new AtomicBoolean();
    private DefaultMQPushConsumer mainConsumer;
    private DefaultMQPushConsumer dlqConsumer;

    WorkflowStartRocketMqConsumers(WorkflowStartRocketMqProperties properties,
                                   WorkflowStartConsumer mainHandler,
                                   WorkflowStartDlqAcceptor dlqHandler) {
        this(properties, mainHandler::consume, dlqHandler::accept);
    }

    /** Acceptance seam used to observe actual Broker deliveries without changing disposition. */
    WorkflowStartRocketMqConsumers(
            WorkflowStartRocketMqProperties properties,
            Function<WorkflowStartTransportMessage, WorkflowStartConsumeResult> mainHandler,
            Function<WorkflowStartTransportMessage, WorkflowStartConsumeResult> dlqHandler) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.mainHandler = Objects.requireNonNull(mainHandler, "mainHandler");
        this.dlqHandler = Objects.requireNonNull(dlqHandler, "dlqHandler");
    }

    @Override
    public synchronized void start() {
        if (running.get()) {
            return;
        }
        properties.validateEnabledConfiguration();
        RPCHook acl = new AclClientRPCHook(new SessionCredentials(
                properties.getAccessKey(), properties.getSecretKey()));
        try {
            dlqConsumer = createConsumer(
                    WorkflowStartContract.DLQ_ACCEPTOR_GROUP,
                    WorkflowStartContract.DLQ_TOPIC,
                    "dlq-acceptor",
                    acl,
                    properties.getNameServer(),
                    dlqHandler);
            mainConsumer = createConsumer(
                    WorkflowStartContract.CONSUMER_GROUP,
                    WorkflowStartContract.TOPIC,
                    "main",
                    acl,
                    properties.getNameServer(),
                    mainHandler);

            dlqConsumer.start();
            mainConsumer.start();
            running.set(true);
            LOG.info("Workflow-start main consumer and DLQ acceptor are ready");
        }
        catch (Exception startupFailure) {
            shutdown(mainConsumer);
            shutdown(dlqConsumer);
            mainConsumer = null;
            dlqConsumer = null;
            throw new IllegalStateException(
                    "Workflow-start RocketMQ consumers failed closed during startup",
                    startupFailure);
        }
    }

    static DefaultMQPushConsumer createConsumer(
            String group,
            String topic,
            String instanceSuffix,
            RPCHook acl,
            String nameServer,
            Function<WorkflowStartTransportMessage, WorkflowStartConsumeResult> handler)
            throws Exception {
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer(group, acl);
        consumer.setNamesrvAddr(nameServer.trim());
        consumer.setInstanceName("flowdesk-workflow-start-" + instanceSuffix + "-" + UUID.randomUUID());
        consumer.setVipChannelEnabled(false);
        consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_LAST_OFFSET);
        consumer.setMessageModel(MessageModel.CLUSTERING);
        consumer.setConsumeThreadMin(1);
        consumer.setConsumeThreadMax(1);
        consumer.setConsumeMessageBatchMaxSize(1);
        consumer.setPullBatchSize(1);
        consumer.setMaxReconsumeTimes(WorkflowStartContract.DLQ_ACCEPTOR_GROUP.equals(group)
                ? WorkflowStartContract.DLQ_ACCEPTOR_MAX_RECONSUME_TIMES
                : WorkflowStartContract.MAX_RECONSUME_TIMES);
        consumer.subscribe(topic, "*");
        consumer.registerMessageListener((MessageListenerConcurrently) (messages, context) ->
                handleOne(group, handler, messages, context));
        return consumer;
    }

    static ConsumeConcurrentlyStatus handleOne(
            String group,
            Function<WorkflowStartTransportMessage, WorkflowStartConsumeResult> handler,
            List<MessageExt> messages) {
        return handleOne(group, handler, messages, null);
    }

    static ConsumeConcurrentlyStatus handleOne(
            String group,
            Function<WorkflowStartTransportMessage, WorkflowStartConsumeResult> handler,
            List<MessageExt> messages,
            ConsumeConcurrentlyContext context) {
        if (messages == null || messages.size() != 1) {
            return brokerRetry(group, context);
        }
        try {
            MessageExt message = messages.get(0);
            Map<String, String> properties = message.getProperties() == null
                    ? Map.of()
                    : message.getProperties();
            String transportTopic = logicalTransportTopic(group, message, context);
            WorkflowStartConsumeResult result = handler.apply(new WorkflowStartTransportMessage(
                    transportTopic, group, message.getKeys(), message.getTags(),
                    message.getBody(), properties));
            return result.disposition() == WorkflowStartConsumeResult.BrokerDisposition.ACK
                    ? ConsumeConcurrentlyStatus.CONSUME_SUCCESS
                    : brokerRetry(group, context);
        }
        catch (RuntimeException unexpectedFailure) {
            LOG.error("Workflow-start listener returned delivery to Broker after an unexpected failure");
            return brokerRetry(group, context);
        }
    }

    private static String logicalTransportTopic(
            String group, MessageExt message, ConsumeConcurrentlyContext context) {
        if (WorkflowStartContract.DLQ_ACCEPTOR_GROUP.equals(group)
                && context != null
                && context.getMessageQueue() != null
                && WorkflowStartContract.DLQ_ACCEPTOR_RETRY_TOPIC.equals(
                        context.getMessageQueue().getTopic())) {
            // RocketMQ retries the acceptor from its internal retry topic while MessageExt can
            // still expose the main group's earlier retry origin.  The exact physical queue
            // proves this is the acceptor's Broker retry of a message subscribed from MAIN_DLQ.
            return WorkflowStartContract.DLQ_TOPIC;
        }
        return message.getTopic();
    }

    private static ConsumeConcurrentlyStatus brokerRetry(
            String group, ConsumeConcurrentlyContext context) {
        if (WorkflowStartContract.DLQ_ACCEPTOR_GROUP.equals(group) && context != null) {
            // The DLQ message inherits the main group's retry count.  Use a fresh short Broker
            // delay for the acceptor instead of inheriting a high delay level from that count.
            context.setDelayLevelWhenNextConsume(
                    WorkflowStartContract.DLQ_ACCEPTOR_RETRY_DELAY_LEVEL);
        }
        return ConsumeConcurrentlyStatus.RECONSUME_LATER;
    }

    @Override
    public synchronized void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        shutdown(mainConsumer);
        shutdown(dlqConsumer);
        mainConsumer = null;
        dlqConsumer = null;
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        return Integer.MIN_VALUE + 200;
    }

    private static void shutdown(DefaultMQPushConsumer consumer) {
        if (consumer != null) {
            consumer.shutdown();
        }
    }
}
