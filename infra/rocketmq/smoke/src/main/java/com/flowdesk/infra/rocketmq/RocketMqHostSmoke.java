package com.flowdesk.infra.rocketmq;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageExt;

/**
 * Host-network producer/consumer smoke for the local Docker RocketMQ topology.
 *
 * <p>The fixed topic and group are created only by {@code infra/rocketmq/verify.sh};
 * Broker auto-creation remains disabled. A unique key makes repeated executions safe.</p>
 */
public final class RocketMqHostSmoke {

    private static final String DEFAULT_NAME_SERVER = "127.0.0.1:9876";
    private static final String DEFAULT_TOPIC = "flowdesk_host_smoke";
    private static final String DEFAULT_CONSUMER_GROUP = "flowdesk_host_smoke_group";
    private static final String EVENT_TYPE = "flowdesk.infrastructure.host-smoke";
    private static final String EVENT_VERSION = "1";

    private RocketMqHostSmoke() {
    }

    public static void main(String[] args) throws Exception {
        String nameServer = setting(
                "flowdesk.rocketmq.nameServer", "FLOWDESK_ROCKETMQ_NAME_SERVER", DEFAULT_NAME_SERVER);
        String topic = setting("flowdesk.rocketmq.topic", null, DEFAULT_TOPIC);
        String consumerGroup = setting(
                "flowdesk.rocketmq.consumerGroup", null, DEFAULT_CONSUMER_GROUP);
        int timeoutSeconds = positiveIntSetting("flowdesk.rocketmq.timeoutSeconds", 30);

        String probeId = UUID.randomUUID().toString();
        String messageKey = "flowdesk-host-smoke-" + probeId;
        String payload = "{\"probeId\":\"" + probeId
                + "\",\"source\":\"host-jvm\",\"occurredAt\":\"" + Instant.now() + "\"}";
        String body = "{\"eventType\":\"" + EVENT_TYPE
                + "\",\"eventVersion\":" + EVENT_VERSION
                + ",\"payload\":" + payload + "}";

        CountDownLatch consumed = new CountDownLatch(1);
        AtomicReference<String> validationFailure = new AtomicReference<>();
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer(consumerGroup);
        DefaultMQProducer producer = new DefaultMQProducer("flowdesk_host_smoke_producer");

        try {
            configureConsumer(consumer, nameServer, topic, probeId, messageKey, payload, body,
                    consumed, validationFailure);
            consumer.start();

            producer.setNamesrvAddr(nameServer);
            producer.setInstanceName("flowdesk-host-smoke-producer-" + probeId);
            producer.setVipChannelEnabled(false);
            producer.start();

            Message message = new Message(topic, "HOST_SMOKE", body.getBytes(StandardCharsets.UTF_8));
            message.setKeys(messageKey);
            message.putUserProperty("eventType", EVENT_TYPE);
            message.putUserProperty("eventVersion", EVENT_VERSION);

            SendResult sendResult = producer.send(message, TimeUnit.SECONDS.toMillis(timeoutSeconds));
            if (sendResult.getSendStatus() != SendStatus.SEND_OK) {
                throw new IllegalStateException("RocketMQ producer returned " + sendResult.getSendStatus());
            }
            if (!consumed.await(timeoutSeconds, TimeUnit.SECONDS)) {
                throw new IllegalStateException(
                        "Timed out waiting for the host consumer to receive key " + messageKey);
            }
            if (validationFailure.get() != null) {
                throw new IllegalStateException(validationFailure.get());
            }

            System.out.printf(
                    "FLOWDESK_ROCKETMQ_HOST_SMOKE_OK topic=%s key=%s broker=%s msgId=%s%n",
                    topic,
                    messageKey,
                    sendResult.getMessageQueue().getBrokerName(),
                    sendResult.getMsgId());
        }
        finally {
            producer.shutdown();
            consumer.shutdown();
        }
    }

    private static void configureConsumer(
            DefaultMQPushConsumer consumer,
            String nameServer,
            String topic,
            String probeId,
            String expectedKey,
            String expectedPayload,
            String expectedBody,
            CountDownLatch consumed,
            AtomicReference<String> validationFailure) throws Exception {
        consumer.setNamesrvAddr(nameServer);
        consumer.setInstanceName("flowdesk-host-smoke-consumer-" + probeId);
        consumer.setVipChannelEnabled(false);
        consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_LAST_OFFSET);
        consumer.setConsumeThreadMin(1);
        consumer.setConsumeThreadMax(1);
        consumer.setPullBatchSize(1);
        consumer.subscribe(topic, "*");
        consumer.registerMessageListener((MessageListenerConcurrently) (messages, context) -> {
            for (MessageExt message : messages) {
                if (!expectedKey.equals(message.getKeys())) {
                    continue;
                }
                String actualBody = new String(message.getBody(), StandardCharsets.UTF_8);
                String failure = validateMessage(message, expectedPayload, expectedBody, actualBody);
                validationFailure.compareAndSet(null, failure);
                consumed.countDown();
            }
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        });
    }

    private static String validateMessage(
            MessageExt message,
            String expectedPayload,
            String expectedBody,
            String actualBody) {
        if (!EVENT_TYPE.equals(message.getUserProperty("eventType"))) {
            return "Consumed message eventType property did not match " + EVENT_TYPE;
        }
        if (!EVENT_VERSION.equals(message.getUserProperty("eventVersion"))) {
            return "Consumed message eventVersion property did not match " + EVENT_VERSION;
        }
        if (!expectedBody.equals(actualBody)) {
            return "Consumed message body did not match the sent event envelope";
        }
        if (!actualBody.contains("\"payload\":" + expectedPayload)) {
            return "Consumed message body did not contain the expected payload";
        }
        return null;
    }

    private static String setting(String propertyName, String environmentName, String defaultValue) {
        String value = System.getProperty(propertyName);
        if ((value == null || value.isBlank()) && environmentName != null) {
            value = System.getenv(environmentName);
        }
        if (value == null || value.isBlank()) {
            value = defaultValue;
        }
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(propertyName + " must not be blank");
        }
        return value.trim();
    }

    private static int positiveIntSetting(String propertyName, int defaultValue) {
        String raw = System.getProperty(propertyName);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        int value = Integer.parseInt(raw);
        if (value <= 0) {
            throw new IllegalArgumentException(propertyName + " must be positive");
        }
        return value;
    }
}
