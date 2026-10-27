package com.flowdesk.ticket.outbox;

import com.flowdesk.ticket.event.TicketEventContract;
import org.apache.rocketmq.acl.common.AclClientRPCHook;
import org.apache.rocketmq.acl.common.SessionCredentials;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.remoting.RPCHook;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** 固定工单流程启动契约的 RocketMQ 5.3.1 同步适配器，禁用 SDK 自动重试以统一计算尝试次数。 */
public final class RocketMqTicketOutboxTransport implements TicketOutboxTransport, AutoCloseable {

    static final String NOT_CONFIRMED = "BROKER_SEND_NOT_CONFIRMED";

    private final DefaultMQProducer producer;
    private final AtomicBoolean started = new AtomicBoolean();

    /**
     * 构造工作流启动发布组件并保存调用依赖。
     */
    RocketMqTicketOutboxTransport(DefaultMQProducer producer) {
        this.producer = Objects.requireNonNull(producer, "producer");
    }

    /**
     * 校验工作流启动发布创建请求并保存事实，按接口约定处理幂等及版本条件。
     */
    public static RocketMqTicketOutboxTransport create(TicketOutboxPublisherProperties properties) {
        properties.validateEnabledConfiguration();
        RPCHook acl = new AclClientRPCHook(new SessionCredentials(
                properties.getAccessKey(), properties.getSecretKey()));
        DefaultMQProducer producer = new DefaultMQProducer(
                TicketEventContract.WorkflowStartRequested.PRODUCER_GROUP, acl);
        producer.setNamesrvAddr(properties.getNameServer().trim());
        producer.setInstanceName("flowdesk-ticket-outbox-" + UUID.randomUUID());
        producer.setVipChannelEnabled(false);
        producer.setSendMsgTimeout(Math.toIntExact(
                TicketEventContract.WorkflowStartRequested.SEND_TIMEOUT.toMillis()));
        producer.setRetryTimesWhenSendFailed(
                TicketEventContract.WorkflowStartRequested.SYNCHRONOUS_SEND_RETRIES);
        producer.setRetryTimesWhenSendAsyncFailed(
                TicketEventContract.WorkflowStartRequested.ASYNCHRONOUS_SEND_RETRIES);
        producer.setRetryAnotherBrokerWhenNotStoreOK(false);
        return new RocketMqTicketOutboxTransport(producer);
    }

    /**
     * 启动后台组件并注册消费或轮询任务，通过运行标记避免重复启动。
     */
    public synchronized void start() throws MQClientException {
        if (started.get()) {
            return;
        }
        try {
            producer.start();
            started.set(true);
        }
        catch (MQClientException | RuntimeException startupFailure) {
            producer.shutdown();
            throw startupFailure;
        }
    }

    /**
     * 发送已保存的发布内容，按传输确认结果推进或保留发件箱状态。
     */
    @Override
    public TicketOutboxSendResult send(TicketOutboxMessage outboxMessage) throws Exception {
        if (!started.get()) {
            throw new IllegalStateException("Ticket Outbox RocketMQ producer is not started");
        }
        if (!TicketEventContract.WorkflowStartRequested.TOPIC.equals(outboxMessage.topic())) {
            throw new IllegalArgumentException("Ticket Outbox transport only accepts the frozen Topic");
        }

        Message message = new Message(outboxMessage.topic(), outboxMessage.body());
        message.setKeys(outboxMessage.keys());
        message.setWaitStoreMsgOK(true);
        for (Map.Entry<String, String> property : outboxMessage.userProperties().entrySet()) {
            message.putUserProperty(property.getKey(), property.getValue());
        }

        SendResult result = producer.send(message,
                TicketEventContract.WorkflowStartRequested.SEND_TIMEOUT.toMillis());
        if (result != null && result.getSendStatus() == SendStatus.SEND_OK) {
            return TicketOutboxSendResult.sendOk();
        }
        String safeSummary = result == null || result.getSendStatus() == null
                ? null
                : "RocketMQ send status " + result.getSendStatus().name();
        return TicketOutboxSendResult.notConfirmed(NOT_CONFIRMED, safeSummary);
    }

    /**
     * 关闭组件持有的资源并终止后续处理。
     */
    @Override
    public synchronized void close() {
        if (started.compareAndSet(true, false)) {
            producer.shutdown();
        }
    }

    /**
     * 提供生产者测试入口，供验证实际传输参数及发送结果映射。
     */
    DefaultMQProducer producerForTest() {
        return producer;
    }
}
