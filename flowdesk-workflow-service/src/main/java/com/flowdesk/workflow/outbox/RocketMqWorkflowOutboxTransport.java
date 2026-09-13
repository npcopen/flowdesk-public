package com.flowdesk.workflow.outbox;

import com.flowdesk.workflow.event.WorkflowReverseEventContract;
import org.apache.rocketmq.acl.common.AclClientRPCHook;
import org.apache.rocketmq.acl.common.SessionCredentials;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.remoting.RPCHook;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Two frozen producer groups behind one contract-selecting transport boundary. */
public final class RocketMqWorkflowOutboxTransport
        implements WorkflowOutboxTransport, AutoCloseable {
    private final DefaultMQProducer establishedProducer;
    private final DefaultMQProducer finalizedProducer;
    private final AtomicBoolean started = new AtomicBoolean();

    private RocketMqWorkflowOutboxTransport(DefaultMQProducer establishedProducer,
                                            DefaultMQProducer finalizedProducer) {
        this.establishedProducer = establishedProducer;
        this.finalizedProducer = finalizedProducer;
    }

    public static RocketMqWorkflowOutboxTransport create(
            WorkflowOutboxPublisherProperties properties) {
        properties.validateEnabled();
        RPCHook acl = new AclClientRPCHook(new SessionCredentials(
                properties.getAccessKey(), properties.getSecretKey()));
        return new RocketMqWorkflowOutboxTransport(
                producer(WorkflowReverseEventContract.InstanceEstablished.PRODUCER_GROUP,
                        "established", properties.getNameServer(), acl),
                producer(WorkflowReverseEventContract.ApprovalFinalized.PRODUCER_GROUP,
                        "finalized", properties.getNameServer(), acl));
    }

    static DefaultMQProducer producer(String group, String suffix,
                                      String nameServer, RPCHook acl) {
        DefaultMQProducer producer = new DefaultMQProducer(group, acl);
        producer.setNamesrvAddr(nameServer.trim());
        producer.setInstanceName("flowdesk-workflow-outbox-" + suffix + "-" + UUID.randomUUID());
        producer.setVipChannelEnabled(false);
        producer.setSendMsgTimeout(Math.toIntExact(
                WorkflowReverseEventContract.SEND_TIMEOUT.toMillis()));
        producer.setRetryTimesWhenSendFailed(WorkflowReverseEventContract.SDK_SEND_RETRIES);
        producer.setRetryTimesWhenSendAsyncFailed(WorkflowReverseEventContract.SDK_SEND_RETRIES);
        producer.setRetryAnotherBrokerWhenNotStoreOK(false);
        return producer;
    }

    public synchronized void start() throws Exception {
        if (started.get()) return;
        try {
            establishedProducer.start();
            finalizedProducer.start();
            started.set(true);
        }
        catch (Exception failure) {
            establishedProducer.shutdown();
            finalizedProducer.shutdown();
            throw failure;
        }
    }

    @Override
    public WorkflowOutboxSendResult send(WorkflowOutboxMessage outbox) throws Exception {
        if (!started.get()) throw new IllegalStateException("Workflow Outbox is not started");
        DefaultMQProducer producer;
        if (WorkflowReverseEventContract.InstanceEstablished.TOPIC.equals(outbox.topic())) {
            producer = establishedProducer;
        }
        else if (WorkflowReverseEventContract.ApprovalFinalized.TOPIC.equals(outbox.topic())) {
            producer = finalizedProducer;
        }
        else {
            throw new IllegalArgumentException("Unfrozen Workflow Outbox Topic");
        }
        Message message = new Message(outbox.topic(), outbox.body());
        message.setKeys(outbox.keys());
        message.setWaitStoreMsgOK(true);
        for (Map.Entry<String, String> property : outbox.userProperties().entrySet()) {
            message.putUserProperty(property.getKey(), property.getValue());
        }
        SendResult result = producer.send(message,
                WorkflowReverseEventContract.SEND_TIMEOUT.toMillis());
        if (result != null && result.getSendStatus() == SendStatus.SEND_OK) {
            return WorkflowOutboxSendResult.sendOk();
        }
        return WorkflowOutboxSendResult.notConfirmed("BROKER_SEND_NOT_CONFIRMED",
                result == null || result.getSendStatus() == null ? null
                        : "RocketMQ send status " + result.getSendStatus().name());
    }

    @Override
    public synchronized void close() {
        if (started.compareAndSet(true, false)) {
            establishedProducer.shutdown();
            finalizedProducer.shutdown();
        }
    }
}
