package com.flowdesk.infra.rocketmq;

import org.apache.rocketmq.acl.common.AclClientRPCHook;
import org.apache.rocketmq.acl.common.SessionCredentials;
import org.apache.rocketmq.client.consumer.DefaultMQPullConsumer;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.exception.MQBrokerException;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.common.message.MessageQueue;
import org.apache.rocketmq.remoting.RPCHook;
import org.apache.rocketmq.remoting.protocol.ResponseCode;
import org.apache.rocketmq.remoting.protocol.heartbeat.MessageModel;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Real Broker ACL matrix for the three M7 Incident message directions. */
public final class RocketMqMilestone7AclProbe {
    private static final int SEND_TIMEOUT_MILLIS = 5_000;
    private static final Binding CREATE = new Binding(
            "flowdesk_incident_ticket_create_requested_v1",
            "flowdesk_incident_ticket_create_requested_v1_alert_pg",
            "flowdesk_incident_ticket_create_requested_v1_ticket_cg");
    private static final Binding ESTABLISHED = new Binding(
            "flowdesk_incident_ticket_established_v1",
            "flowdesk_incident_ticket_established_v1_ticket_pg",
            "flowdesk_incident_ticket_established_v1_alert_cg");
    private static final Binding RECOVERED = new Binding(
            "flowdesk_incident_recovered_v1",
            "flowdesk_incident_recovered_v1_alert_pg",
            "flowdesk_incident_recovered_v1_ticket_cg");

    private RocketMqMilestone7AclProbe() {
    }

    public static void main(String[] args) throws Exception {
        require(Boolean.parseBoolean(System.getProperty("flowdesk.rocketmq.m7Profile", "false")),
                "M7 ACL probe requires flowdesk.rocketmq.m7Profile=true");
        require(Boolean.parseBoolean(System.getProperty(
                        "flowdesk.rocketmq.allowMainGroupProbe", "false")),
                "M7 ACL probe requires exclusive ownership of all M7 main Groups");
        String nameServer = setting("FLOWDESK_ROCKETMQ_NAME_SERVER", "127.0.0.1:9876");
        int timeoutSeconds = Integer.parseInt(System.getProperty(
                "flowdesk.rocketmq.timeoutSeconds", "60"));
        require(timeoutSeconds >= 10 && timeoutSeconds <= 300, "Timeout must be 10..300 seconds");
        Credentials alert = Credentials.required(
                "FLOWDESK_ALERT_ROCKETMQ_ACCESS_KEY",
                "FLOWDESK_ALERT_ROCKETMQ_SECRET_KEY", "flowdeskalert");
        Credentials ticket = Credentials.required(
                "FLOWDESK_TICKET_ROCKETMQ_ACCESS_KEY",
                "FLOWDESK_TICKET_ROCKETMQ_SECRET_KEY", "flowdeskticket");

        expectDenied("uncredentialed create publish", () ->
                sendOnce(nameServer, CREATE, null, "no-credentials"));

        Delivery create = roundTrip(nameServer, CREATE, alert, ticket, timeoutSeconds, "create");
        Delivery established = roundTrip(
                nameServer, ESTABLISHED, ticket, alert, timeoutSeconds, "established");
        Delivery recovered = roundTrip(
                nameServer, RECOVERED, alert, ticket, timeoutSeconds, "recovered");

        expectDenied("Ticket create publish", () ->
                sendOnce(nameServer, CREATE, ticket.hook(), "ticket-create"));
        expectDenied("Alert established publish", () ->
                sendOnce(nameServer, ESTABLISHED, alert.hook(), "alert-established"));
        expectDenied("Ticket recovered publish", () ->
                sendOnce(nameServer, RECOVERED, ticket.hook(), "ticket-recovered"));
        expectPullDenied("Alert create subscription", alert, CREATE.consumerGroup(),
                nameServer, create.queue(), create.offset(), "alert-create-sub");
        expectPullDenied("Ticket established subscription", ticket, ESTABLISHED.consumerGroup(),
                nameServer, established.queue(), established.offset(), "ticket-established-sub");
        expectPullDenied("Alert recovered subscription", alert, RECOVERED.consumerGroup(),
                nameServer, recovered.queue(), recovered.offset(), "alert-recovered-sub");

        System.out.printf(Locale.ROOT,
                "FLOWDESK_ROCKETMQ_M7_ACL_PROBE_OK "
                        + "positive=AlertCreatePub+TicketCreateSub,TicketEstablishedPub+AlertEstablishedSub,"
                        + "AlertRecoveredPub+TicketRecoveredSub "
                        + "negative=NoCredentialsPub,TicketCreatePub,AlertEstablishedPub,"
                        + "TicketRecoveredPub,AlertCreateSub,TicketEstablishedSub,AlertRecoveredSub%n");
    }

    private static Delivery roundTrip(String nameServer, Binding binding,
                                      Credentials producerCredentials,
                                      Credentials consumerCredentials,
                                      int timeoutSeconds, String suffix) throws Exception {
        String probe = UUID.randomUUID().toString();
        byte[] body = ("{\"m7AclProbe\":\"" + probe + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        CountDownLatch consumed = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        DefaultMQPushConsumer consumer = pushConsumer(
                binding.consumerGroup(), consumerCredentials.hook(), nameServer, suffix);
        DefaultMQProducer producer = producer(
                binding.producerGroup(), producerCredentials.hook(), nameServer, suffix);
        SendResult sent;
        try {
            consumer.subscribe(binding.topic(), "*");
            consumer.registerMessageListener((org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently)
                    (messages, context) -> {
                        for (MessageExt message : messages) {
                            if (!probe.equals(message.getKeys())) continue;
                            try {
                                require(java.util.Arrays.equals(body, message.getBody()),
                                        "ACL probe body changed in Broker round trip");
                            }
                            catch (Throwable invalid) {
                                failure.compareAndSet(null, invalid);
                            }
                            consumed.countDown();
                        }
                        return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
                    });
            consumer.start();
            producer.start();
            Message message = new Message(binding.topic(), body);
            message.setKeys(probe);
            sent = producer.send(message, SEND_TIMEOUT_MILLIS);
            require(sent.getSendStatus() == SendStatus.SEND_OK,
                    "Broker returned " + sent.getSendStatus());
            require(consumed.await(timeoutSeconds, TimeUnit.SECONDS),
                    "Timed out waiting for ACL positive round trip on " + binding.topic());
            if (failure.get() != null) throw new IllegalStateException(failure.get());
        }
        finally {
            producer.shutdown();
            consumer.shutdown();
        }
        return new Delivery(sent.getMessageQueue(), sent.getQueueOffset());
    }

    private static SendResult sendOnce(String nameServer, Binding binding,
                                       RPCHook hook, String suffix) throws Exception {
        DefaultMQProducer producer = producer(
                binding.producerGroup(), hook, nameServer, "negative-" + suffix);
        try {
            producer.start();
            Message message = new Message(binding.topic(), "{}".getBytes(StandardCharsets.UTF_8));
            message.setKeys(UUID.randomUUID().toString());
            return producer.send(message, SEND_TIMEOUT_MILLIS);
        }
        finally {
            producer.shutdown();
        }
    }

    private static void expectPullDenied(String label, Credentials credentials, String group,
                                         String nameServer, MessageQueue queue, long offset,
                                         String suffix) throws Exception {
        DefaultMQPullConsumer consumer = new DefaultMQPullConsumer(group, credentials.hook());
        consumer.setNamesrvAddr(nameServer);
        consumer.setInstanceName("flowdesk-m7-acl-negative-" + suffix + "-" + UUID.randomUUID());
        consumer.setVipChannelEnabled(false);
        try {
            expectDenied(label, () -> {
                consumer.start();
                consumer.pull(queue, "*", offset, 1, SEND_TIMEOUT_MILLIS);
            });
        }
        finally {
            consumer.shutdown();
        }
    }

    private static DefaultMQPushConsumer pushConsumer(String group, RPCHook hook,
                                                       String nameServer, String suffix) {
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer(group, hook);
        consumer.setNamesrvAddr(nameServer);
        consumer.setInstanceName("flowdesk-m7-acl-consumer-" + suffix + "-" + UUID.randomUUID());
        consumer.setVipChannelEnabled(false);
        consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_LAST_OFFSET);
        consumer.setMessageModel(MessageModel.CLUSTERING);
        consumer.setConsumeThreadMin(1);
        consumer.setConsumeThreadMax(1);
        consumer.setPullBatchSize(1);
        consumer.setConsumeMessageBatchMaxSize(1);
        return consumer;
    }

    private static DefaultMQProducer producer(String group, RPCHook hook,
                                               String nameServer, String suffix) {
        DefaultMQProducer producer = new DefaultMQProducer(group, hook);
        producer.setNamesrvAddr(nameServer);
        producer.setInstanceName("flowdesk-m7-acl-producer-" + suffix + "-" + UUID.randomUUID());
        producer.setVipChannelEnabled(false);
        producer.setSendMsgTimeout(SEND_TIMEOUT_MILLIS);
        producer.setRetryTimesWhenSendFailed(0);
        producer.setRetryTimesWhenSendAsyncFailed(0);
        producer.setRetryAnotherBrokerWhenNotStoreOK(false);
        return producer;
    }

    private static void expectDenied(String label, CheckedOperation operation) throws Exception {
        try {
            operation.run();
        }
        catch (Throwable failure) {
            if (isAuthorizationFailure(failure)) return;
            throw new IllegalStateException(label + " failed for a non-ACL reason", failure);
        }
        throw new IllegalStateException(label + " unexpectedly succeeded");
    }

    private static boolean isAuthorizationFailure(Throwable throwable) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current instanceof MQBrokerException broker
                    && broker.getResponseCode() == ResponseCode.NO_PERMISSION) return true;
            String message = current.getMessage();
            if (message != null && (message.contains("NO_PERMISSION")
                    || message.toLowerCase(Locale.ROOT).contains("no permission")
                    || message.toLowerCase(Locale.ROOT).contains("authorization"))) return true;
        }
        return false;
    }

    private static String setting(String environment, String fallback) {
        String value = System.getenv(environment);
        if (value == null || value.isBlank()) value = fallback;
        require(value != null && !value.isBlank(), environment + " is required");
        return value.trim();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private record Binding(String topic, String producerGroup, String consumerGroup) {
    }

    private record Delivery(MessageQueue queue, long offset) {
    }

    private record Credentials(String accessKey, String secretKey) {
        private static Credentials required(String accessEnvironment, String secretEnvironment,
                                            String expectedAccessKey) {
            String access = setting(accessEnvironment, null);
            String secret = setting(secretEnvironment, null);
            require(expectedAccessKey.equals(access), accessEnvironment + " is not frozen");
            require(secret.matches("[A-Za-z0-9]{16,}"),
                    secretEnvironment + " must be at least 16 ASCII alphanumeric characters");
            return new Credentials(access, secret);
        }

        private RPCHook hook() {
            return new AclClientRPCHook(new SessionCredentials(accessKey, secretKey));
        }
    }

    @FunctionalInterface
    private interface CheckedOperation {
        void run() throws Exception;
    }
}
