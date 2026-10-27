package com.flowdesk.workflow.contract;

import com.flowdesk.workflow.start.WorkflowStartContract;
import com.flowdesk.workflow.start.WorkflowStartTransportMessage;

import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Properties;

/**
 * 冻结测试样例或传输快照，保留原始身份、字节和摘要供正常及错误变体场景复用。
 */
final class WorkflowStartV1WireFixture {

    private static final String RESOURCE =
            "contracts/flowdesk-workflow-start-v1-wire.properties";

    /**
     * 初始化组件依赖与运行参数，使后续调用使用一致的协作对象。
     */
    private WorkflowStartV1WireFixture() {
    }

    /**
     * 读取冻结消息资源及属性，为后续传输和摘要断言提供固定输入。
     */
    static WireMessage load() {
        Properties source = new Properties();
        try (InputStream input = WorkflowStartV1WireFixture.class
                .getClassLoader().getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Missing test fixture " + RESOURCE);
            }
            source.load(input);
        }
        catch (Exception ex) {
            throw new IllegalStateException("Cannot load test fixture " + RESOURCE, ex);
        }

        Map<String, String> userProperties = new HashMap<>();
        for (String name : source.stringPropertyNames()) {
            if (name.startsWith("fd_")) {
                userProperties.put(name, source.getProperty(name));
            }
        }
        return new WireMessage(
                required(source, "topic"),
                WorkflowStartContract.CONSUMER_GROUP,
                required(source, "keys"),
                null,
                Base64.getDecoder().decode(required(source, "body.base64")),
                userProperties);
    }

    /**
     * 读取样例必填属性，缺失时立即使测试失败，避免构造不完整基线。
     */
    private static String required(Properties source, String name) {
        String value = source.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Fixture property is missing: " + name);
        }
        return value;
    }

    /**
     * 冻结测试样例或传输快照，保留原始身份、字节和摘要供正常及错误变体场景复用。
     */
    record WireMessage(String topic,
                       String consumerGroup,
                       String keys,
                       String tags,
                       byte[] body,
                       Map<String, String> properties) {

        /**
         * 构造不可变数据快照并复制传入集合，防止调用方后续修改影响返回结果。
         */
        WireMessage {
            body = body == null ? null : Arrays.copyOf(body, body.length);
            properties = Map.copyOf(properties);
        }

        /**
         * 返回消息体字节的防御性副本，避免调用方通过数组修改已保存事实。
         */
        @Override
        public byte[] body() {
            return body == null ? null : Arrays.copyOf(body, body.length);
        }

        /**
         * 将冻结样例转换为消费入口投递对象，保留原身份、属性及载荷字节。
         */
        WorkflowStartTransportMessage transportMessage() {
            return new WorkflowStartTransportMessage(
                    topic, consumerGroup, keys, tags, body, properties);
        }

        /**
         * 复制消息属性并替换单个字段，避免错误变体修改原始基线。
         */
        WireMessage withProperty(String name, String value) {
            Map<String, String> changed = new HashMap<>(properties);
            changed.put(name, value);
            return new WireMessage(topic, consumerGroup, keys, tags, body, changed);
        }

        /**
         * 复制信封并移除指定字段，验证必填属性缺失的契约拒绝。
         */
        WireMessage withoutProperty(String name) {
            Map<String, String> changed = new HashMap<>(properties);
            changed.remove(name);
            return new WireMessage(topic, consumerGroup, keys, tags, body, changed);
        }

        /**
         * 替换原始载荷并同步摘要，单独验证字段结构和类型契约。
         */
        WireMessage withBodyAndMatchingHash(byte[] changedBody) {
            Map<String, String> changed = new HashMap<>(properties);
            changed.put("fd_body_sha256", sha256Hex(changedBody));
            return new WireMessage(topic, consumerGroup, keys, tags, changedBody, changed);
        }

        /**
         * 复制消息并替换身份，构造消息身份冲突或重复投递场景。
         */
        WireMessage withMessageId(String changedMessageId) {
            Map<String, String> changed = new HashMap<>(properties);
            changed.put("fd_message_id", changedMessageId);
            return new WireMessage(
                    topic, consumerGroup, changedMessageId, tags, body, changed);
        }

        /**
         * 复制消息并替换 Broker 消息键，验证键与冻结身份绑定。
         */
        WireMessage withKeys(String changedKeys) {
            return new WireMessage(
                    topic, consumerGroup, changedKeys, tags, body, properties);
        }

        /**
         * 复制消息并替换消费组，验证错误消费方向被拒绝。
         */
        WireMessage withConsumerGroup(String changedGroup) {
            return new WireMessage(topic, changedGroup, keys, tags, body, properties);
        }

        /**
         * 复制消息并替换 Tag，验证冻结 Topic 的标签约束。
         */
        WireMessage withTags(String changedTags) {
            return new WireMessage(
                    topic, consumerGroup, keys, changedTags, body, properties);
        }
    }

    /**
     * 计算字节 SHA-256 并编码十六进制，供契约样例和变体摘要使用。
     */
    static String sha256Hex(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        }
        catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
