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
 * 主消费组及独立死信接收组的 RocketMQ 经典客户端绑定；先启动死信接收器，监听器不在进程内自行重试。
 */
public final class WorkflowStartRocketMqConsumers implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(WorkflowStartRocketMqConsumers.class);

    private final WorkflowStartRocketMqProperties properties;
    private final Function<WorkflowStartTransportMessage, WorkflowStartConsumeResult> mainHandler;
    private final Function<WorkflowStartTransportMessage, WorkflowStartConsumeResult> dlqHandler;
    private final AtomicBoolean running = new AtomicBoolean();
    private DefaultMQPushConsumer mainConsumer;
    private DefaultMQPushConsumer dlqConsumer;

    /**
     * 绑定冻结队列配置及主队列、死信两个处理函数，供生命周期启动时装配明确的代理重投边界。
     */
    WorkflowStartRocketMqConsumers(WorkflowStartRocketMqProperties properties,
                                   WorkflowStartConsumer mainHandler,
                                   WorkflowStartDlqAcceptor dlqHandler) {
        this(properties, mainHandler::consume, dlqHandler::accept);
    }

    /**
     * 绑定冻结队列配置及主队列、死信两个处理函数，供生命周期启动时装配明确的代理重投边界。
     */
    WorkflowStartRocketMqConsumers(
            WorkflowStartRocketMqProperties properties,
            Function<WorkflowStartTransportMessage, WorkflowStartConsumeResult> mainHandler,
            Function<WorkflowStartTransportMessage, WorkflowStartConsumeResult> dlqHandler) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.mainHandler = Objects.requireNonNull(mainHandler, "mainHandler");
        this.dlqHandler = Objects.requireNonNull(dlqHandler, "dlqHandler");
    }

    /**
     * 启动当前组件并建立消息连接或调度资源，准备处理生命周期内的任务。
     */
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

    /**
     * 按主队列或死信绑定创建消费者，装配明确的监听与重试策略。
     */
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

    /**
     * 处理一次代理投递并直接映射确认或重试，避免监听器内部嵌套重试。
     */
    static ConsumeConcurrentlyStatus handleOne(
            String group,
            Function<WorkflowStartTransportMessage, WorkflowStartConsumeResult> handler,
            List<MessageExt> messages) {
        return handleOne(group, handler, messages, null);
    }

    /**
     * 处理一次代理投递并直接映射确认或重试，避免监听器内部嵌套重试。
     */
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

    /**
     * 将代理内部投递路由还原为冻结逻辑 Topic，供严格信封校验使用。
     */
    private static String logicalTransportTopic(
            String group, MessageExt message, ConsumeConcurrentlyContext context) {
        if (WorkflowStartContract.DLQ_ACCEPTOR_GROUP.equals(group)
                && context != null
                && context.getMessageQueue() != null
                && WorkflowStartContract.DLQ_ACCEPTOR_RETRY_TOPIC.equals(
                        context.getMessageQueue().getTopic())) {
            // 接收器从自己的内部重试 Topic 收到重投，但 MessageExt 可能仍携带
            // still expose the main group's earlier retry origin.  The exact physical queue
            // 接收器对 MAIN_DLQ 订阅消息的 Broker 重试。
            return WorkflowStartContract.DLQ_TOPIC;
        }
        return message.getTopic();
    }

    /**
     * 将暂时性故障映射为代理重投，遵守已约定的有界重试边界。
     */
    private static ConsumeConcurrentlyStatus brokerRetry(
            String group, ConsumeConcurrentlyContext context) {
        if (WorkflowStartContract.DLQ_ACCEPTOR_GROUP.equals(group) && context != null) {
            // The DLQ message inherits the main group's retry count.  Use a fresh short Broker
            // 避免继承该计数对应的较高延迟级别。
            context.setDelayLevelWhenNextConsume(
                    WorkflowStartContract.DLQ_ACCEPTOR_RETRY_DELAY_LEVEL);
        }
        return ConsumeConcurrentlyStatus.RECONSUME_LATER;
    }

    /**
     * 停止组件并释放连接或调度资源，阻止关闭后继续领取任务。
     */
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

    /**
     * 返回组件运行状态，供 Spring 生命周期管理决定启动和停止行为。
     */
    @Override
    public boolean isRunning() {
        return running.get();
    }

    /**
     * 返回生命周期阶段，使组件按约定顺序启动并反序停止。
     */
    @Override
    public int getPhase() {
        return Integer.MIN_VALUE + 200;
    }

    /**
     * 关闭已创建消费者，供正常停止和部分启动失败时清理资源。
     */
    private static void shutdown(DefaultMQPushConsumer consumer) {
        if (consumer != null) {
            consumer.shutdown();
        }
    }
}
