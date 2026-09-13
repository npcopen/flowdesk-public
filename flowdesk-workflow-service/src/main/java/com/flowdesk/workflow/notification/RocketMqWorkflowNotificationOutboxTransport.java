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

/** Synchronous RocketMQ adapter for the Workflow Notification binding. */
public final class RocketMqWorkflowNotificationOutboxTransport
        implements WorkflowNotificationOutboxTransport, AutoCloseable {
    static final String NOT_CONFIRMED = "BROKER_SEND_NOT_CONFIRMED";
    private final DefaultMQProducer producer;
    private final AtomicBoolean started = new AtomicBoolean();

    RocketMqWorkflowNotificationOutboxTransport(DefaultMQProducer producer) {
        this.producer = Objects.requireNonNull(producer, "producer");
    }

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

    public synchronized void start() throws MQClientException {
        if (started.get()) return;
        try { producer.start(); started.set(true); }
        catch (MQClientException | RuntimeException failure) { producer.shutdown(); throw failure; }
    }

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

    @Override public synchronized void close() {
        if (started.compareAndSet(true, false)) producer.shutdown();
    }

    DefaultMQProducer producerForTest() { return producer; }
}
