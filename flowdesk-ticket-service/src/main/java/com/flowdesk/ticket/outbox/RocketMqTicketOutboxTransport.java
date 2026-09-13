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

/** Classic RocketMQ 5.3.1 synchronous adapter for the frozen Ticket Outbox contract. */
public final class RocketMqTicketOutboxTransport implements TicketOutboxTransport, AutoCloseable {

    static final String NOT_CONFIRMED = "BROKER_SEND_NOT_CONFIRMED";

    private final DefaultMQProducer producer;
    private final AtomicBoolean started = new AtomicBoolean();

    RocketMqTicketOutboxTransport(DefaultMQProducer producer) {
        this.producer = Objects.requireNonNull(producer, "producer");
    }

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

    @Override
    public synchronized void close() {
        if (started.compareAndSet(true, false)) {
            producer.shutdown();
        }
    }

    DefaultMQProducer producerForTest() {
        return producer;
    }
}
