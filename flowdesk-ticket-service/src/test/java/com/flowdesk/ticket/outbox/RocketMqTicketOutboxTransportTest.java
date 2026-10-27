package com.flowdesk.ticket.outbox;

import com.flowdesk.ticket.event.TicketEventContract;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 工单模块回归测试，覆盖该组件的正常流程、边界条件与关键一致性约束。
 */
class RocketMqTicketOutboxTransportTest {

    /**
     * 验证生产者固定组、超时与零 SDK 自动重试配置。
     */
    @Test
    void configuredProducerLocksFrozenGroupTimeoutAndZeroSdkRetries() {
        TicketOutboxPublisherProperties properties = enabledProperties();
        properties.setNameServer(" 127.0.0.1:19876 ");

        RocketMqTicketOutboxTransport transport =
                RocketMqTicketOutboxTransport.create(properties);
        DefaultMQProducer producer = transport.producerForTest();

        assertThat(producer.getProducerGroup())
                .isEqualTo(TicketEventContract.WorkflowStartRequested.PRODUCER_GROUP);
        assertThat(producer.getNamesrvAddr()).isEqualTo("127.0.0.1:19876");
        assertThat(producer.getInstanceName()).startsWith("flowdesk-ticket-outbox-");
        assertThat(producer.isVipChannelEnabled()).isFalse();
        assertThat(producer.getSendMsgTimeout()).isEqualTo(5_000);
        assertThat(producer.getRetryTimesWhenSendFailed()).isZero();
        assertThat(producer.getRetryTimesWhenSendAsyncFailed()).isZero();
        assertThat(producer.isRetryAnotherBrokerWhenNotStoreOK()).isFalse();
    }

    /**
     * 验证同步发送保留原传输内容，且仅 SEND_OK 被认定成功。
     */
    @Test
    void synchronousSendCopiesWireAndOnlyRocketMqSendOkConfirms() throws Exception {
        CapturingProducer producer = new CapturingProducer();
        RocketMqTicketOutboxTransport transport = new RocketMqTicketOutboxTransport(producer);
        TicketOutboxMessage outboxMessage = new TicketOutboxMessage(
                TicketEventContract.WorkflowStartRequested.TOPIC,
                "018f6f2d-9b57-7c31-a69f-4db0d84db106",
                "exact-body".getBytes(StandardCharsets.UTF_8),
                Map.of("fd_envelope_version", "1", "fd_event_type", "flowdesk.workflow.start"));

        assertThatThrownBy(() -> transport.send(outboxMessage))
                .isInstanceOf(IllegalStateException.class);
        transport.start();

        producer.nextResult = result(SendStatus.SEND_OK);
        assertThat(transport.send(outboxMessage)).isEqualTo(TicketOutboxSendResult.sendOk());
        assertThat(producer.captured.getTopic()).isEqualTo(outboxMessage.topic());
        assertThat(producer.captured.getKeys()).isEqualTo(outboxMessage.keys());
        assertThat(producer.captured.getBody()).containsExactly(outboxMessage.body());
        assertThat(producer.captured.isWaitStoreMsgOK()).isTrue();
        assertThat(producer.captured.getUserProperty("fd_envelope_version")).isEqualTo("1");
        assertThat(producer.captured.getUserProperty("fd_event_type"))
                .isEqualTo("flowdesk.workflow.start");
        assertThat(producer.timeoutMillis).isEqualTo(5_000L);

        producer.nextResult = result(SendStatus.FLUSH_DISK_TIMEOUT);
        TicketOutboxSendResult unconfirmed = transport.send(outboxMessage);
        assertThat(unconfirmed.status()).isEqualTo(TicketOutboxSendResult.Status.NOT_CONFIRMED);
        assertThat(unconfirmed.errorCode()).isEqualTo(RocketMqTicketOutboxTransport.NOT_CONFIRMED);
        assertThat(unconfirmed.safeErrorSummary()).isEqualTo(
                "RocketMQ send status FLUSH_DISK_TIMEOUT");

        transport.close();
        assertThat(producer.startCount).isEqualTo(1);
        assertThat(producer.shutdownCount).isEqualTo(1);
        assertThatThrownBy(() -> transport.send(outboxMessage))
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * 验证启用发送时缺少规定 ACL 身份或密钥会拒绝启动。
     */
    @Test
    void optInFailsClosedWithoutFrozenAclIdentityAndSecret() {
        TicketOutboxPublisherProperties disabled = new TicketOutboxPublisherProperties();
        assertThat(disabled.isEnabled()).isFalse();
        assertThatThrownBy(() -> RocketMqTicketOutboxTransport.create(disabled))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("disabled");

        TicketOutboxPublisherProperties wrongIdentity = enabledProperties();
        wrongIdentity.setAccessKey("another-service");
        assertThatThrownBy(() -> RocketMqTicketOutboxTransport.create(wrongIdentity))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("frozen Ticket identity");

        TicketOutboxPublisherProperties missingSecret = enabledProperties();
        missingSecret.setSecretKey("");
        assertThatThrownBy(() -> RocketMqTicketOutboxTransport.create(missingSecret))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("secretKey");
    }

    /**
     * 构造显式启用的测试配置，供验证身份和生产者参数。
     */
    private static TicketOutboxPublisherProperties enabledProperties() {
        TicketOutboxPublisherProperties properties = new TicketOutboxPublisherProperties();
        properties.setEnabled(true);
        properties.setAccessKey(TicketOutboxPublisherProperties.EXPECTED_ACCESS_KEY);
        properties.setSecretKey("TicketSecretKey123456");
        return properties;
    }

    /**
     * 创建本场景需要的发送或发布统计结果，精确控制调度分支。
     */
    private static SendResult result(SendStatus status) {
        SendResult result = new SendResult();
        result.setSendStatus(status);
        return result;
    }

    /**
     * 用于该测试的内部替身，提供可控制的时钟、调度或依赖行为。
     */
    private static final class CapturingProducer extends DefaultMQProducer {
        private Message captured;
        private long timeoutMillis;
        private SendResult nextResult;
        private int startCount;
        private int shutdownCount;

        /**
         * 仅累加测试生产者启动次数，不连接实际 Broker。
         */
        @Override
        public void start() {
            startCount++;
        }

        /**
         * 捕获消息和超时参数并返回预设结果，供断言传输内容与成功映射。
         */
        @Override
        public SendResult send(Message message, long timeout) {
            captured = message;
            timeoutMillis = timeout;
            return nextResult;
        }

        /**
         * 关闭测试生产者替身并记录资源释放行为。
         */
        @Override
        public void shutdown() {
            shutdownCount++;
        }
    }
}
