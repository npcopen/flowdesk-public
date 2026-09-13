package com.flowdesk.ticket.incident;

import org.apache.rocketmq.acl.common.AclClientRPCHook;
import org.apache.rocketmq.acl.common.SessionCredentials;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

final class IncidentResultRocketMqTransport implements AutoCloseable {
    private final DefaultMQProducer producer;
    private final AtomicBoolean started = new AtomicBoolean();
    private IncidentResultRocketMqTransport(DefaultMQProducer producer) { this.producer = producer; }
    static IncidentResultRocketMqTransport create(IncidentResultPublisherProperties p) {
        p.validate();
        DefaultMQProducer producer = new DefaultMQProducer(
                IncidentMessageContract.ESTABLISHED_PRODUCER_GROUP,
                new AclClientRPCHook(new SessionCredentials(p.getAccessKey(), p.getSecretKey())));
        producer.setNamesrvAddr(p.getNameServer().trim());
        producer.setInstanceName("flowdesk-ticket-incident-result-" + UUID.randomUUID());
        producer.setVipChannelEnabled(false);
        producer.setSendMsgTimeout(Math.toIntExact(IncidentMessageContract.SEND_TIMEOUT.toMillis()));
        producer.setRetryTimesWhenSendFailed(IncidentMessageContract.SDK_SEND_RETRIES);
        producer.setRetryTimesWhenSendAsyncFailed(IncidentMessageContract.SDK_SEND_RETRIES);
        producer.setRetryAnotherBrokerWhenNotStoreOK(false);
        return new IncidentResultRocketMqTransport(producer);
    }
    public synchronized void start() throws Exception {
        if (started.compareAndSet(false, true)) try { producer.start(); }
        catch (Exception failure) { started.set(false); producer.shutdown(); throw failure; }
    }
    boolean send(IncidentResultMessage m) throws Exception {
        if (!started.get()) throw new IllegalStateException("Incident result producer is not started");
        if (!IncidentMessageContract.ESTABLISHED_TOPIC.equals(m.topic())) throw new IllegalArgumentException("Unexpected Topic");
        Message wire = new Message(m.topic(), m.body());
        wire.setKeys(m.key()); wire.setWaitStoreMsgOK(true);
        m.properties().forEach(wire::putUserProperty);
        SendResult result = producer.send(wire, IncidentMessageContract.SEND_TIMEOUT.toMillis());
        return result != null && result.getSendStatus() == SendStatus.SEND_OK;
    }
    @Override public synchronized void close() { if (started.compareAndSet(true, false)) producer.shutdown(); }
}
