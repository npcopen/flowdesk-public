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

/**
 * 依据契约选择两个独立生产者组，发送实例建立及审批完成事件。
 */
public final class RocketMqWorkflowOutboxTransport
        implements WorkflowOutboxTransport, AutoCloseable {
    private final DefaultMQProducer establishedProducer;
    private final DefaultMQProducer finalizedProducer;
    private final AtomicBoolean started = new AtomicBoolean();

    /**
     * 初始化组件依赖与运行参数，使后续调用使用一致的协作对象。
     */
    private RocketMqWorkflowOutboxTransport(DefaultMQProducer establishedProducer,
                                            DefaultMQProducer finalizedProducer) {
        this.establishedProducer = establishedProducer;
        this.finalizedProducer = finalizedProducer;
    }

    /**
     * 使用已校验的代理地址和固定访问身份创建传输组件，保持冻结生产组配置一致。
     */
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

    /**
     * 按指定身份及生产组构造生产者，为不同冻结事件绑定各自发送配置。
     */
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

    /**
     * 启动当前组件并建立消息连接或调度资源，准备处理生命周期内的任务。
     */
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

    /**
     * 发送当前消息并返回代理确认结果，重复发送保留原身份与载荷。
     */
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

    /**
     * 关闭持有的消息代理资源并释放连接，结束传输对象生命周期。
     */
    @Override
    public synchronized void close() {
        if (started.compareAndSet(true, false)) {
            establishedProducer.shutdown();
            finalizedProducer.shutdown();
        }
    }
}
