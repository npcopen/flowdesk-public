package com.flowdesk.ticket.incident;

import org.apache.rocketmq.acl.common.AclClientRPCHook;
import org.apache.rocketmq.acl.common.SessionCredentials;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Incident 建单结果的同步 RocketMQ 适配器，仅将 Broker 明确确认视为发送成功。 */
final class IncidentResultRocketMqTransport implements AutoCloseable {
    private final DefaultMQProducer producer;
    private final AtomicBoolean started = new AtomicBoolean();
    /**
     * 构造告警建单组件并保存调用依赖。
     */
    private IncidentResultRocketMqTransport(DefaultMQProducer producer) { this.producer = producer; }
    /**
     * 校验告警建单创建请求并保存事实，按接口约定处理幂等及版本条件。
     */
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
    /**
     * 启动后台组件并注册消费或轮询任务，通过运行标记避免重复启动。
     */
    public synchronized void start() throws Exception {
        if (started.compareAndSet(false, true)) try { producer.start(); }
        catch (Exception failure) { started.set(false); producer.shutdown(); throw failure; }
    }
    /**
     * 发送已保存的发布内容，按传输确认结果推进或保留发件箱状态。
     */
    boolean send(IncidentResultMessage m) throws Exception {
        if (!started.get()) throw new IllegalStateException("Incident result producer is not started");
        if (!IncidentMessageContract.ESTABLISHED_TOPIC.equals(m.topic())) throw new IllegalArgumentException("Unexpected Topic");
        Message wire = new Message(m.topic(), m.body());
        wire.setKeys(m.key()); wire.setWaitStoreMsgOK(true);
        m.properties().forEach(wire::putUserProperty);
        SendResult result = producer.send(wire, IncidentMessageContract.SEND_TIMEOUT.toMillis());
        return result != null && result.getSendStatus() == SendStatus.SEND_OK;
    }
    /**
     * 关闭组件持有的资源并终止后续处理。
     */
    @Override public synchronized void close() { if (started.compareAndSet(true, false)) producer.shutdown(); }
}
