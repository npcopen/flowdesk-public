package com.flowdesk.alert.event;

import org.apache.rocketmq.acl.common.AclClientRPCHook;
import org.apache.rocketmq.acl.common.SessionCredentials;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.remoting.protocol.heartbeat.MessageModel;
import org.springframework.context.SmartLifecycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * RocketMQ 与工单结果业务处理器之间的传输适配层，统一管理主消费和独立死信接纳器。
 *
 * <p>负责连接、订阅、客户端生命周期以及投递元数据转换；关联更新和恢复落库交给对应处理器。
 * 只有处理器返回 ACK 才映射为 CONSUME_SUCCESS，RETRY 或运行时异常均要求重新投递，
 * 使事务失败不会被 Broker 当作业务已完成。
 */
final class TicketResultRocketMqConsumers implements SmartLifecycle {
    private static final Logger LOG = LoggerFactory.getLogger(TicketResultRocketMqConsumers.class);
    private final TicketResultProperties properties;
    private final TicketResultConsumer main;
    private final TicketResultDlqAcceptor dlq;
    private final List<DefaultMQPushConsumer> consumers = new ArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean();

    /**
     * 保存消息订阅配置及主队列、死信处理器，由生命周期回调负责创建和停止 RocketMQ 消费者。
     */
    TicketResultRocketMqConsumers(
            TicketResultProperties p, TicketResultConsumer m, TicketResultDlqAcceptor d) {
        properties = p;
        main = m;
        dlq = d;
    }

    /** 先准备死信接纳，再启动主消费；任一消费者启动失败时关闭全部，避免留下不完整的消费链路。 */
    public synchronized void start() {
        if (running.get()) return;
        properties.validate();
        var hook =
                new AclClientRPCHook(
                        new SessionCredentials(
                                properties.getAccessKey(), properties.getSecretKey()));
        try {
            consumers.add(create(true, hook, dlq::accept));
            consumers.add(create(false, hook, main::consume));
            for (var c : consumers) c.start();
            running.set(true);
            LOG.info("Ticket result main consumer and DLQ acceptor are ready");
        } catch (Exception e) {
            consumers.forEach(DefaultMQPushConsumer::shutdown);
            consumers.clear();
            throw new IllegalStateException("Alert Ticket result consumers failed closed", e);
        }
    }

    /** 两个方向使用独立组和重试预算；每批限制一条，使本次返回的 ACK / RETRY 对应一份本地事务结果。 */
    private DefaultMQPushConsumer create(
            boolean acceptor,
            AclClientRPCHook hook,
            Function<TicketResultTransportMessage, TicketResultConsumer.Result> handler)
            throws Exception {
        var b = AlertEventContract.TICKET_ESTABLISHED;
        String group = acceptor ? b.dlqAcceptorGroup() : b.consumerGroup(),
                topic = acceptor ? b.dlqTopic() : b.topic();
        var c = new DefaultMQPushConsumer(group, hook);
        c.setNamesrvAddr(properties.getNameServer().trim());
        c.setInstanceName(
                "flowdesk-alert-ticket-result-"
                        + (acceptor ? "dlq-" : "main-")
                        + UUID.randomUUID());
        c.setVipChannelEnabled(false);
        c.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
        c.setMessageModel(MessageModel.CLUSTERING);
        c.setConsumeThreadMin(1);
        c.setConsumeThreadMax(1);
        c.setPullBatchSize(1);
        c.setConsumeMessageBatchMaxSize(1);
        c.setMaxReconsumeTimes(
                acceptor
                        ? AlertEventContract.DLQ_ACCEPTOR_MAX_RECONSUME_TIMES
                        : AlertEventContract.MAX_RECONSUME_TIMES);
        c.subscribe(topic, "*");
        c.registerMessageListener(
                (MessageListenerConcurrently)
                        (messages, context) -> handle(group, acceptor, handler, messages, context));
        return c;
    }

    /** 将 SDK 消息转换为独立传输快照，业务处理器不需要依赖或修改 MessageExt。 */
    private static ConsumeConcurrentlyStatus handle(
            String group,
            boolean acceptor,
            Function<TicketResultTransportMessage, TicketResultConsumer.Result> handler,
            List<MessageExt> messages,
            ConsumeConcurrentlyContext context) {
        if (messages == null || messages.size() != 1) return retry(acceptor, context);
        try {
            var b = AlertEventContract.TICKET_ESTABLISHED;
            MessageExt m = messages.get(0);
            String topic = m.getTopic();
            // 死信接纳器自身重试会进入它的 RETRY 主题；还原逻辑死信方向后，再执行原契约校验。
            if (acceptor
                    && context != null
                    && context.getMessageQueue() != null
                    && b.acceptorRetryTopic().equals(context.getMessageQueue().getTopic()))
                topic = b.dlqTopic();
            var result =
                    handler.apply(
                            new TicketResultTransportMessage(
                                    topic,
                                    group,
                                    m.getKeys(),
                                    m.getTags(),
                                    m.getBody(),
                                    m.getProperties() == null ? Map.of() : m.getProperties()));
            return result == TicketResultConsumer.Result.ACK
                    ? ConsumeConcurrentlyStatus.CONSUME_SUCCESS
                    : retry(acceptor, context);
        } catch (RuntimeException e) {
            return retry(acceptor, context);
        }
    }

    /** 死信接纳失败使用固定延迟级别，保留再次落库机会；主消费沿用 Broker 的普通重投安排。 */
    private static ConsumeConcurrentlyStatus retry(boolean acceptor, ConsumeConcurrentlyContext c) {
        if (acceptor && c != null)
            c.setDelayLevelWhenNextConsume(AlertEventContract.DLQ_ACCEPTOR_RETRY_DELAY_LEVEL);
        return ConsumeConcurrentlyStatus.RECONSUME_LATER;
    }

    /**
     * 停止当前后台消费或调度组件并释放资源，使应用关闭时不再领取新的任务。
     */
    public synchronized void stop() {
        running.set(false);
        consumers.forEach(DefaultMQPushConsumer::shutdown);
        consumers.clear();
    }

    /**
     * 返回组件当前运行标志，供 Spring 生命周期协调启动和停止。
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * 返回生命周期阶段，供 Spring 确定消息消费者、调度器与依赖资源的启停顺序。
     */
    public int getPhase() {
        return Integer.MIN_VALUE + 100;
    }
}
