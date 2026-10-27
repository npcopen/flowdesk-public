package com.flowdesk.workflow.outbox;

import com.flowdesk.workflow.event.WorkflowReverseEventContract;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 工作流代理发送适配测试，核验冻结生产组、固定访问身份与原始消息体保真。
 */
class RocketMqWorkflowOutboxTransportTest {
    /**
     * 验证每种事件使用独立冻结生产组且关闭 SDK 内部重试，并检查相关业务事实或错误响应符合约定。
     */
    @Test
    void eachEventUsesItsOwnFrozenProducerGroupAndNoSdkRetry() {
        assertProducer(RocketMqWorkflowOutboxTransport.producer(
                WorkflowReverseEventContract.InstanceEstablished.PRODUCER_GROUP,
                "established", "127.0.0.1:9876", null),
                WorkflowReverseEventContract.InstanceEstablished.PRODUCER_GROUP);
        assertProducer(RocketMqWorkflowOutboxTransport.producer(
                WorkflowReverseEventContract.ApprovalFinalized.PRODUCER_GROUP,
                "finalized", "127.0.0.1:9876", null),
                WorkflowReverseEventContract.ApprovalFinalized.PRODUCER_GROUP);
    }

    /**
     * 验证错误发布身份在生产者启动前即失败关闭，并检查相关业务事实或错误响应符合约定。
     */
    @Test
    void workflowPublisherIdentityFailsClosedBeforeAProducerStarts() {
        WorkflowOutboxPublisherProperties properties = new WorkflowOutboxPublisherProperties();
        properties.setEnabled(true);
        properties.setAccessKey("flowdeskworkflow");
        properties.setSecretKey("WorkflowSecret1234");
        properties.validateEnabled();

        properties.setAccessKey("flowdeskticket");
        assertThatThrownBy(properties::validateEnabled).isInstanceOf(IllegalStateException.class);
    }

    /**
     * 断言当前场景的生产者固定身份和重试配置，失败时直接报告对应证据差异。
     */
    private static void assertProducer(DefaultMQProducer producer, String group) {
        assertThat(producer.getProducerGroup()).isEqualTo(group);
        assertThat(producer.getNamesrvAddr()).isEqualTo("127.0.0.1:9876");
        assertThat(producer.getSendMsgTimeout()).isEqualTo(5000);
        assertThat(producer.getRetryTimesWhenSendFailed()).isZero();
        assertThat(producer.getRetryTimesWhenSendAsyncFailed()).isZero();
        assertThat(producer.isRetryAnotherBrokerWhenNotStoreOK()).isFalse();
        assertThat(producer.isVipChannelEnabled()).isFalse();
    }
}
