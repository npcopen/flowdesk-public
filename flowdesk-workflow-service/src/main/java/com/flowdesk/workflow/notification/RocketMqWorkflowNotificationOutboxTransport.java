package com.flowdesk.workflow.notification;

import com.flowdesk.common.notification.NotificationEventContract;
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

/**
 * 审批通知的同步 RocketMQ 发送适配器，将 Broker 发送状态转换为发布器可判断的结果。
 */
public final class RocketMqWorkflowNotificationOutboxTransport
        implements WorkflowNotificationOutboxTransport, AutoCloseable {
    static final String NOT_CONFIRMED = "BROKER_SEND_NOT_CONFIRMED";
    private final DefaultMQProducer producer;
    private final AtomicBoolean started = new AtomicBoolean();

    /**
     * 初始化组件依赖与运行参数，使后续调用使用一致的协作对象。
     */
    RocketMqWorkflowNotificationOutboxTransport(DefaultMQProducer producer) {
        this.producer = Objects.requireNonNull(producer, "producer");
    }

    /**
     * 使用已校验的代理地址和固定访问身份创建传输组件，保持冻结生产组配置一致。
     */
    public static RocketMqWorkflowNotificationOutboxTransport create(
            WorkflowNotificationOutboxPublisherProperties properties) {
        properties.validateEnabledConfiguration();
        RPCHook hook = new AclClientRPCHook(new SessionCredentials(
                properties.getAccessKey(), properties.getSecretKey()));
        DefaultMQProducer producer = new DefaultMQProducer(
                NotificationEventContract.WORKFLOW.producerGroup(), hook);
        producer.setNamesrvAddr(properties.getNameServer().trim());
        producer.setInstanceName("flowdesk-workflow-notification-outbox-" + UUID.randomUUID());
        producer.setVipChannelEnabled(false);
        producer.setSendMsgTimeout(Math.toIntExact(NotificationEventContract.SEND_TIMEOUT.toMillis()));
        producer.setRetryTimesWhenSendFailed(NotificationEventContract.SDK_SEND_RETRIES);
        producer.setRetryTimesWhenSendAsyncFailed(NotificationEventContract.SDK_SEND_RETRIES);
        producer.setRetryAnotherBrokerWhenNotStoreOK(false);
        return new RocketMqWorkflowNotificationOutboxTransport(producer);
    }

    /**
     * 启动当前组件并建立消息连接或调度资源，准备处理生命周期内的任务。
     */
    public synchronized void start() throws MQClientException {
        if (started.get()) return;
        try { producer.start(); started.set(true); }
        catch (MQClientException | RuntimeException failure) { producer.shutdown(); throw failure; }
    }

    /**
     * 发送当前消息并返回代理确认结果，重复发送保留原身份与载荷。
     */
    @Override public WorkflowNotificationOutboxSendResult send(WorkflowNotificationOutboxMessage value)
            throws Exception {
        if (!started.get()) throw new IllegalStateException("Workflow Notification producer is not started");
        if (!NotificationEventContract.WORKFLOW.topic().equals(value.topic())) {
            throw new IllegalArgumentException("Workflow Notification topic is not frozen");
        }
        Message message = new Message(value.topic(), value.body());
        message.setKeys(value.keys()); message.setWaitStoreMsgOK(true);
        for (Map.Entry<String, String> entry : value.userProperties().entrySet()) {
            message.putUserProperty(entry.getKey(), entry.getValue());
        }
        SendResult result = producer.send(message, NotificationEventContract.SEND_TIMEOUT.toMillis());
        if (result != null && result.getSendStatus() == SendStatus.SEND_OK) {
            return WorkflowNotificationOutboxSendResult.sendOk();
        }
        String summary = result == null || result.getSendStatus() == null ? null
                : "RocketMQ send status " + result.getSendStatus().name();
        return WorkflowNotificationOutboxSendResult.notConfirmed(NOT_CONFIRMED, summary);
    }

    /**
     * 关闭持有的消息代理资源并释放连接，结束传输对象生命周期。
     */
    @Override public synchronized void close() {
        if (started.compareAndSet(true, false)) producer.shutdown();
    }

    /**
     * 提供当前生产者，供测试核验冻结配置与真实发送行为。
     */
    DefaultMQProducer producerForTest() { return producer; }
}
