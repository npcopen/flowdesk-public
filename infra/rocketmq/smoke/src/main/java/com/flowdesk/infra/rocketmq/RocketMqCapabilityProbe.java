package com.flowdesk.infra.rocketmq;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.rocketmq.acl.common.AclClientRPCHook;
import org.apache.rocketmq.acl.common.SessionCredentials;
import org.apache.rocketmq.client.consumer.DefaultMQPullConsumer;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.PullResult;
import org.apache.rocketmq.client.consumer.PullStatus;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
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

/**
 * Locked RocketMQ classic-client 5.3.1 capability probe for the 5.3.4 Broker.
 *
 * <p>{@code wire} is safe on the ACL-disabled M4 baseline and only uses the
 * dedicated host-smoke Topic/Group. {@code acl} requires an M5 ACL profile;
 * {@code acl} and {@code retry-dlq} both require an explicit exclusive flag
 * because they use the frozen business Topic and main consumer Group. The
 * retry probe also works without credentials on the ACL-disabled M4 baseline.
 * Secrets are read only from environment variables.</p>
 */
public final class RocketMqCapabilityProbe {

    static final String CLIENT_VERSION = "5.3.1";
    static final String BROKER_VERSION = "5.3.4";
    static final String DEFAULT_NAME_SERVER = "127.0.0.1:9876";
    static final String HOST_TOPIC = "flowdesk_host_smoke";
    static final String HOST_GROUP = "flowdesk_host_smoke_group";
    static final String BUSINESS_TOPIC = "flowdesk_workflow_start_v1";
    static final String PRODUCER_GROUP = "flowdesk_workflow_start_v1_ticket_pg";
    static final String MAIN_GROUP = "flowdesk_workflow_start_v1_workflow_cg";
    static final String DLQ_ACCEPTOR_GROUP =
            "flowdesk_workflow_start_v1_workflow_dlq_acceptor_cg";
    static final String MAIN_RETRY_TOPIC = "%RETRY%" + MAIN_GROUP;
    static final String MAIN_DLQ_TOPIC = "%DLQ%" + MAIN_GROUP;
    static final int MAX_RECONSUME_TIMES = 5;
    static final int TOTAL_DELIVERIES = 1 + MAX_RECONSUME_TIMES;
    static final long[] EXPECTED_RETRY_DELAYS_MILLIS = {10_000, 30_000, 60_000, 120_000, 180_000};
    private static final int SEND_TIMEOUT_MILLIS = 5_000;
    private static final long RETRY_DELAY_EARLY_TOLERANCE_MILLIS = 2_000;
    private static final long RETRY_DELAY_LATE_TOLERANCE_MILLIS = 15_000;
    private static final DateTimeFormatter MICROSECOND_INSTANT =
            new DateTimeFormatterBuilder().appendInstant(6).toFormatter();

    private RocketMqCapabilityProbe() {
    }

    public static void main(String[] args) throws Exception {
        String mode = setting("flowdesk.rocketmq.probeMode", null, "wire")
                .toLowerCase(Locale.ROOT);
        String nameServer = setting(
                "flowdesk.rocketmq.nameServer", "FLOWDESK_ROCKETMQ_NAME_SERVER", DEFAULT_NAME_SERVER);
        int timeoutSeconds = positiveIntSetting("flowdesk.rocketmq.timeoutSeconds", 30);

        switch (mode) {
            case "self-test" -> runSelfTest();
            case "wire" -> runWire(nameServer, timeoutSeconds);
            case "acl" -> runAclMatrix(nameServer, timeoutSeconds);
            case "retry-dlq" -> runRetryDlq(nameServer, timeoutSeconds);
            default -> throw new IllegalArgumentException(
                    "flowdesk.rocketmq.probeMode must be self-test, wire, acl, or retry-dlq");
        }
    }

    private static void runSelfTest() {
        ProbeEnvelope envelope = ProbeEnvelope.create();
        validateFixture(envelope);
        Message message = envelope.toMessage(BUSINESS_TOPIC);
        validateWire(message, envelope, BUSINESS_TOPIC);
        require(PRODUCER_GROUP.equals(new DefaultMQProducer(PRODUCER_GROUP).getProducerGroup()),
                "Classic client did not retain the frozen producer group");
        require(MAIN_DLQ_TOPIC.equals("%DLQ%flowdesk_workflow_start_v1_workflow_cg"),
                "Fixed DLQ derivation changed");
        require(TOTAL_DELIVERIES == 6, "Retry delivery count changed");
        System.out.printf(
                "FLOWDESK_ROCKETMQ_PROBE_SELF_TEST_OK client=%s expectedBroker=%s producerGroup=%s "
                        + "mainGroup=%s dlq=%s deliveries=%d%n",
                CLIENT_VERSION, BROKER_VERSION, PRODUCER_GROUP, MAIN_GROUP,
                MAIN_DLQ_TOPIC, TOTAL_DELIVERIES);
    }

    private static void runWire(String nameServer, int timeoutSeconds) throws Exception {
        ProbeEnvelope envelope = ProbeEnvelope.create();
        CountDownLatch consumed = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        DefaultMQPushConsumer consumer = pushConsumer(HOST_GROUP, null, nameServer, "wire");
        DefaultMQProducer producer = producer(PRODUCER_GROUP, null, nameServer, "wire");
        try {
            registerSuccessListener(consumer, HOST_TOPIC, envelope, consumed, failure);
            consumer.start();
            producer.start();
            assertProducerGroup(producer);
            SendResult result = send(producer, envelope.toMessage(HOST_TOPIC));
            await(consumed, timeoutSeconds, "wire round trip");
            throwIfFailure(failure);
            System.out.printf(
                    "FLOWDESK_ROCKETMQ_WIRE_PROBE_OK client=%s expectedBroker=%s topic=%s key=%s "
                            + "producerGroup=%s msgId=%s bodyBytes=%d properties=%d%n",
                    CLIENT_VERSION, BROKER_VERSION, HOST_TOPIC, envelope.messageId(),
                    PRODUCER_GROUP, result.getMsgId(), envelope.body().length,
                    envelope.properties().size());
        }
        finally {
            producer.shutdown();
            consumer.shutdown();
        }
    }

    private static void runAclMatrix(String nameServer, int timeoutSeconds) throws Exception {
        requireM5Exclusive("acl");
        Credentials ticket = Credentials.required(
                "FLOWDESK_TICKET_ROCKETMQ_ACCESS_KEY",
                "FLOWDESK_TICKET_ROCKETMQ_SECRET_KEY", "flowdeskticket");
        Credentials workflow = Credentials.required(
                "FLOWDESK_WORKFLOW_ROCKETMQ_ACCESS_KEY",
                "FLOWDESK_WORKFLOW_ROCKETMQ_SECRET_KEY", "flowdeskworkflow");

        // Safe first check: on the M4 ACL-disabled baseline this writes only to
        // the dedicated host-smoke Topic, then fails closed before business use.
        expectDenied("uncredentialed publish", () -> sendOnce(
                nameServer, "flowdesk_m5_uncredentialed_probe_pg", null,
                ProbeEnvelope.create().toMessage(HOST_TOPIC), "acl-no-credentials"));

        ProbeEnvelope envelope = ProbeEnvelope.create();
        CountDownLatch consumed = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        DefaultMQPushConsumer consumer = pushConsumer(
                MAIN_GROUP, workflow.hook(), nameServer, "acl-positive");
        DefaultMQProducer producer = producer(
                PRODUCER_GROUP, ticket.hook(), nameServer, "acl-positive");
        SendResult positive;
        try {
            registerSuccessListener(consumer, BUSINESS_TOPIC, envelope, consumed, failure);
            consumer.start();
            producer.start();
            assertProducerGroup(producer);
            positive = send(producer, envelope.toMessage(BUSINESS_TOPIC));
            await(consumed, timeoutSeconds, "authenticated Ticket-to-Workflow round trip");
            throwIfFailure(failure);
        }
        finally {
            producer.shutdown();
            consumer.shutdown();
        }

        MessageQueue positiveQueue = positive.getMessageQueue();
        long positiveOffset = positive.getQueueOffset();
        expectPullDenied("Ticket Sub", ticket, MAIN_GROUP, nameServer,
                positiveQueue, positiveOffset, "acl-ticket-sub");
        expectPullDenied("Workflow other Group Sub", workflow, HOST_GROUP, nameServer,
                positiveQueue, positiveOffset, "acl-workflow-other-group");
        expectDenied("Workflow Pub", () -> sendOnce(
                nameServer, PRODUCER_GROUP, workflow.hook(),
                ProbeEnvelope.create().toMessage(BUSINESS_TOPIC), "acl-workflow-pub"));

        System.out.printf(
                "FLOWDESK_ROCKETMQ_ACL_PROBE_OK client=%s expectedBroker=%s topic=%s "
                        + "producerGroup=%s mainGroup=%s positive=TicketPub+WorkflowSub "
                        + "negative=NoCredentials+TicketSub+WorkflowPub+WorkflowOtherGroup%n",
                CLIENT_VERSION, BROKER_VERSION, BUSINESS_TOPIC, PRODUCER_GROUP, MAIN_GROUP);
    }

    private static void runRetryDlq(String nameServer, int timeoutSeconds) throws Exception {
        requireBoolean("flowdesk.rocketmq.allowMainGroupProbe",
                "retry-dlq requires exclusive ownership of the frozen business Topic/main Group");
        requireBoolean("flowdesk.rocketmq.allowRetryDlqProbe",
                "retry-dlq intentionally performs six deliveries in the frozen main Group");
        require(timeoutSeconds >= 450,
                "retry-dlq timeout must be at least 450 seconds for 10s/30s/1m/2m/3m delays");
        Credentials ticket = Credentials.optional(
                "FLOWDESK_TICKET_ROCKETMQ_ACCESS_KEY",
                "FLOWDESK_TICKET_ROCKETMQ_SECRET_KEY", "flowdeskticket");
        Credentials workflow = Credentials.optional(
                "FLOWDESK_WORKFLOW_ROCKETMQ_ACCESS_KEY",
                "FLOWDESK_WORKFLOW_ROCKETMQ_SECRET_KEY", "flowdeskworkflow");

        ProbeEnvelope envelope = ProbeEnvelope.create();
        CountDownLatch sixthDelivery = new CountDownLatch(1);
        AtomicInteger deliveries = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        long[] deliveryNanos = new long[TOTAL_DELIVERIES];
        DefaultMQPushConsumer mainConsumer = pushConsumer(
                MAIN_GROUP, hook(workflow), nameServer, "retry-main");
        DefaultMQProducer producer = producer(
                PRODUCER_GROUP, hook(ticket), nameServer, "retry-main");
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        long[] observedRetryDelaysMillis;
        try {
            mainConsumer.setMaxReconsumeTimes(MAX_RECONSUME_TIMES);
            mainConsumer.subscribe(BUSINESS_TOPIC, "*");
            mainConsumer.registerMessageListener((MessageListenerConcurrently) (messages, context) -> {
                for (MessageExt message : messages) {
                    if (!envelope.messageId().equals(message.getKeys())) {
                        // The explicit exclusive flag promises there is no real Workflow
                        // consumer or unrelated traffic while this probe owns the Group.
                        continue;
                    }
                    int delivery = deliveries.incrementAndGet();
                    try {
                        validateWire(message, envelope, BUSINESS_TOPIC);
                        require(message.getReconsumeTimes() == delivery - 1,
                                "Delivery " + delivery + " had reconsumeTimes="
                                        + message.getReconsumeTimes());
                        require(delivery <= TOTAL_DELIVERIES,
                                "Broker delivered the probe more than six times");
                        deliveryNanos[delivery - 1] = System.nanoTime();
                    }
                    catch (Throwable ex) {
                        failure.compareAndSet(null, ex);
                        sixthDelivery.countDown();
                        return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
                    }
                    if (delivery == TOTAL_DELIVERIES) {
                        sixthDelivery.countDown();
                    }
                    return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                }
                return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            });
            mainConsumer.start();
            producer.start();
            assertProducerGroup(producer);
            send(producer, envelope.toMessage(BUSINESS_TOPIC));
            awaitUntil(sixthDelivery, deadlineNanos, "six main-Group deliveries");
            throwIfFailure(failure);
            observedRetryDelaysMillis = validateRetryDelays(deliveryNanos);

            // Let the sixth listener result reach the Broker before stopping the
            // main consumer. The DLQ is inspected by manual pull under the separate
            // acceptor Group, so this probe can never recurse into its secondary DLQ.
            TimeUnit.SECONDS.sleep(2);
        }
        finally {
            producer.shutdown();
            mainConsumer.shutdown();
        }

        MessageExt dlqMessage = pullFromDlq(
                nameServer, workflow, envelope, deadlineNanos);
        validateWire(dlqMessage, envelope, MAIN_DLQ_TOPIC);
        require(deliveries.get() == TOTAL_DELIVERIES,
                "Expected exactly six deliveries but observed " + deliveries.get());
        System.out.printf(
                "FLOWDESK_ROCKETMQ_RETRY_DLQ_PROBE_OK client=%s expectedBroker=%s topic=%s "
                        + "mainGroup=%s deliveries=%d reconsumeTimes=0..5 retryTopic=%s "
                        + "observedRetryDelaysMillis=%s dlq=%s dlqAcceptorGroup=%s dlqMsgId=%s%n",
                CLIENT_VERSION, BROKER_VERSION, BUSINESS_TOPIC, MAIN_GROUP,
                deliveries.get(), MAIN_RETRY_TOPIC, Arrays.toString(observedRetryDelaysMillis), MAIN_DLQ_TOPIC,
                DLQ_ACCEPTOR_GROUP, dlqMessage.getMsgId());
    }

    private static long[] validateRetryDelays(long[] deliveryNanos) {
        require(deliveryNanos.length == TOTAL_DELIVERIES,
                "Retry probe did not allocate one timestamp per delivery");
        long[] observedMillis = new long[EXPECTED_RETRY_DELAYS_MILLIS.length];
        for (int index = 0; index < EXPECTED_RETRY_DELAYS_MILLIS.length; index++) {
            require(deliveryNanos[index] > 0 && deliveryNanos[index + 1] > deliveryNanos[index],
                    "Retry delivery timestamps were missing or non-monotonic at index " + index);
            long observed = TimeUnit.NANOSECONDS.toMillis(
                    deliveryNanos[index + 1] - deliveryNanos[index]);
            long expected = EXPECTED_RETRY_DELAYS_MILLIS[index];
            long minimum = expected - RETRY_DELAY_EARLY_TOLERANCE_MILLIS;
            long maximum = expected + RETRY_DELAY_LATE_TOLERANCE_MILLIS;
            require(observed >= minimum && observed <= maximum,
                    "Retry delay " + (index + 1) + " was " + observed
                            + "ms; expected " + expected + "ms within ["
                            + minimum + "," + maximum + "]ms");
            observedMillis[index] = observed;
        }
        return observedMillis;
    }

    private static MessageExt pullFromDlq(
            String nameServer,
            Credentials workflow,
            ProbeEnvelope envelope,
            long deadlineNanos) throws Exception {
        DefaultMQPullConsumer acceptor = new DefaultMQPullConsumer(
                DLQ_ACCEPTOR_GROUP, hook(workflow));
        acceptor.setNamesrvAddr(nameServer);
        acceptor.setInstanceName("flowdesk-m5-dlq-pull-" + envelope.messageId());
        acceptor.setVipChannelEnabled(false);
        try {
            acceptor.start();
            while (System.nanoTime() < deadlineNanos) {
                Set<MessageQueue> queues;
                try {
                    queues = acceptor.fetchSubscribeMessageQueues(MAIN_DLQ_TOPIC);
                }
                catch (Exception ex) {
                    if (isAuthorizationFailure(ex)) {
                        throw new IllegalStateException(
                                "Workflow ACL cannot Sub the fixed main DLQ with the DLQ acceptor Group", ex);
                    }
                    queues = Set.of();
                }
                for (MessageQueue queue : queues) {
                    long storedOffset = acceptor.fetchConsumeOffset(queue, true);
                    long offset = storedOffset >= 0 ? storedOffset : acceptor.minOffset(queue);
                    while (System.nanoTime() < deadlineNanos) {
                        PullResult result = acceptor.pull(queue, "*", offset, 32, SEND_TIMEOUT_MILLIS);
                        if (result.getPullStatus() == PullStatus.FOUND) {
                            List<MessageExt> messages = result.getMsgFoundList();
                            for (MessageExt message : messages) {
                                if (envelope.messageId().equals(message.getKeys())) {
                                    validateWire(message, envelope, MAIN_DLQ_TOPIC);
                                    acceptor.updateConsumeOffset(queue, result.getNextBeginOffset());
                                    acceptor.persist(queue);
                                    return message;
                                }
                            }
                        }
                        long next = result.getNextBeginOffset();
                        if (next <= offset || result.getPullStatus() == PullStatus.NO_NEW_MSG) {
                            break;
                        }
                        offset = next;
                    }
                }
                TimeUnit.SECONDS.sleep(1);
            }
            throw new IllegalStateException(
                    "Timed out waiting for key " + envelope.messageId() + " in " + MAIN_DLQ_TOPIC);
        }
        finally {
            acceptor.shutdown();
        }
    }

    private static void registerSuccessListener(
            DefaultMQPushConsumer consumer,
            String topic,
            ProbeEnvelope envelope,
            CountDownLatch consumed,
            AtomicReference<Throwable> failure) throws Exception {
        consumer.subscribe(topic, "*");
        consumer.registerMessageListener((MessageListenerConcurrently) (messages, context) -> {
            for (MessageExt message : messages) {
                if (!envelope.messageId().equals(message.getKeys())) {
                    continue;
                }
                try {
                    validateWire(message, envelope, topic);
                }
                catch (Throwable ex) {
                    failure.compareAndSet(null, ex);
                }
                consumed.countDown();
            }
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        });
    }

    private static DefaultMQPushConsumer pushConsumer(
            String group, RPCHook hook, String nameServer, String instanceSuffix) {
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer(group, hook);
        consumer.setNamesrvAddr(nameServer);
        consumer.setInstanceName("flowdesk-m5-" + instanceSuffix + "-" + UUID.randomUUID());
        consumer.setVipChannelEnabled(false);
        consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_LAST_OFFSET);
        consumer.setMessageModel(MessageModel.CLUSTERING);
        consumer.setConsumeThreadMin(1);
        consumer.setConsumeThreadMax(1);
        consumer.setConsumeMessageBatchMaxSize(1);
        consumer.setPullBatchSize(1);
        return consumer;
    }

    private static DefaultMQProducer producer(
            String group, RPCHook hook, String nameServer, String instanceSuffix) {
        DefaultMQProducer producer = new DefaultMQProducer(group, hook);
        producer.setNamesrvAddr(nameServer);
        producer.setInstanceName("flowdesk-m5-" + instanceSuffix + "-" + UUID.randomUUID());
        producer.setVipChannelEnabled(false);
        producer.setSendMsgTimeout(SEND_TIMEOUT_MILLIS);
        producer.setRetryTimesWhenSendFailed(0);
        producer.setRetryTimesWhenSendAsyncFailed(0);
        producer.setRetryAnotherBrokerWhenNotStoreOK(false);
        return producer;
    }

    private static SendResult send(DefaultMQProducer producer, Message message) throws Exception {
        SendResult result = producer.send(message, SEND_TIMEOUT_MILLIS);
        require(result.getSendStatus() == SendStatus.SEND_OK,
                "Broker returned " + result.getSendStatus());
        return result;
    }

    private static SendResult sendOnce(
            String nameServer,
            String group,
            RPCHook hook,
            Message message,
            String instanceSuffix) throws Exception {
        DefaultMQProducer producer = producer(group, hook, nameServer, instanceSuffix);
        try {
            producer.start();
            return send(producer, message);
        }
        finally {
            producer.shutdown();
        }
    }

    private static void expectPullDenied(
            String label,
            Credentials credentials,
            String group,
            String nameServer,
            MessageQueue queue,
            long offset,
            String instanceSuffix) throws Exception {
        DefaultMQPullConsumer consumer = new DefaultMQPullConsumer(group, credentials.hook());
        consumer.setNamesrvAddr(nameServer);
        consumer.setInstanceName("flowdesk-m5-" + instanceSuffix + "-" + UUID.randomUUID());
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

    private static void expectDenied(String label, CheckedOperation operation) throws Exception {
        try {
            operation.run();
        }
        catch (Throwable ex) {
            if (isAuthorizationFailure(ex)) {
                return;
            }
            throw new IllegalStateException(
                    label + " failed for a non-ACL reason; topology/health is not proven", ex);
        }
        throw new IllegalStateException(
                "FLOWDESK_ROCKETMQ_ACL_EXPECTATION_FAILED: " + label
                        + " unexpectedly succeeded; ACL 2.0 is disabled or over-permissive");
    }

    private static boolean isAuthorizationFailure(Throwable throwable) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current instanceof MQBrokerException brokerException
                    && brokerException.getResponseCode() == ResponseCode.NO_PERMISSION) {
                return true;
            }
            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase(Locale.ROOT);
                if (normalized.contains("no permission")
                        || normalized.contains("not authorized")
                        || normalized.contains("authorization")
                        || normalized.contains("authentication")
                        || normalized.contains("unauthorized")
                        || normalized.contains("signature")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void validateWire(
            Message message, ProbeEnvelope expected, String expectedTopic) {
        require(expectedTopic.equals(message.getTopic()),
                "Topic changed: expected " + expectedTopic + " but got " + message.getTopic());
        require(expected.messageId().equals(message.getKeys()),
                "KEYS did not equal canonical fd_message_id");
        require(Arrays.equals(expected.body(), message.getBody()),
                "Body bytes changed during Broker storage/delivery");
        require(MessageDigest.isEqual(
                        hexToBytes(expected.properties().get("fd_body_sha256")),
                        sha256(message.getBody())),
                "fd_body_sha256 did not match the received raw body");
        for (Map.Entry<String, String> property : expected.properties().entrySet()) {
            require(property.getValue().equals(message.getUserProperty(property.getKey())),
                    "User property changed or disappeared: " + property.getKey());
        }
        require(message.getUserProperty("fd_causation_id") == null,
                "Null causation must remain an absent property");
        for (String propertyName : message.getProperties().keySet()) {
            if (propertyName.startsWith("fd_") && !expected.properties().containsKey(propertyName)) {
                throw new IllegalStateException("Unexpected reserved user property: " + propertyName);
            }
        }
    }

    private static void validateFixture(ProbeEnvelope envelope) {
        String body = new String(envelope.body(), StandardCharsets.UTF_8);
        require(body.startsWith("{") && body.endsWith("}"),
                "Probe payload must be one flat JSON object");
        require(body.substring(1, body.length() - 1).split(",", -1).length == 7,
                "Probe payload must contain exactly the seven frozen v1 fields");
        require(body.contains("\"schemaVersion\":\"1\""),
                "Probe payload schemaVersion must be the string \"1\"");
        require(body.contains("\"ticketId\":\"" + envelope.ticketId() + "\""),
                "Probe payload ticketId did not match the aggregate");
        require(body.contains("\"workflowDefinitionId\":\""),
                "Probe payload omitted workflowDefinitionId");
        require(body.contains("\"workflowVersionId\":\""),
                "Probe payload omitted workflowVersionId");
        require(body.contains("\"versionNo\":1"),
                "Probe payload versionNo must be an integer");
        require(body.contains("\"serviceRiskLevel\":\"HIGH\""),
                "Probe payload serviceRiskLevel must be HIGH");
        require(body.contains("\"requesterUserId\":\""),
                "Probe payload omitted requesterUserId");
        require(!body.contains("workflowVersionNo")
                        && !body.contains("requestedByUserId")
                        && !body.contains("requestedAt"),
                "Probe payload used a non-contract field name");
        String expectedIntentKey = sha256Hex(frame(
                "FD-WORKFLOW-START-V1",
                UUID.fromString(envelope.tenantId()),
                UUID.fromString(envelope.ticketId())));
        require(expectedIntentKey.equals(
                        envelope.properties().get("fd_business_intent_key")),
                "Probe business intent key did not use Ticket's frozen binary framing");
    }

    private static void assertProducerGroup(DefaultMQProducer producer) {
        require(PRODUCER_GROUP.equals(producer.getProducerGroup()),
                "Producer group is not the frozen value " + PRODUCER_GROUP);
    }

    private static void requireM5Exclusive(String mode) {
        requireBoolean("flowdesk.rocketmq.m5Profile",
                mode + " requires an ACL 2.0 M5 Broker profile");
        requireBoolean("flowdesk.rocketmq.allowMainGroupProbe",
                mode + " requires exclusive ownership of the frozen business Topic/main Group");
    }

    private static RPCHook hook(Credentials credentials) {
        return credentials == null ? null : credentials.hook();
    }

    private static void requireBoolean(String propertyName, String reason) {
        if (!Boolean.parseBoolean(System.getProperty(propertyName, "false"))) {
            throw new IllegalStateException(
                    "FLOWDESK_ROCKETMQ_M5_PROFILE_REQUIRED: set -D" + propertyName
                            + "=true; " + reason);
        }
    }

    private static void await(CountDownLatch latch, int timeoutSeconds, String label)
            throws InterruptedException {
        if (!latch.await(timeoutSeconds, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Timed out waiting for " + label);
        }
    }

    private static void awaitUntil(CountDownLatch latch, long deadlineNanos, String label)
            throws InterruptedException {
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0 || !latch.await(remaining, TimeUnit.NANOSECONDS)) {
            throw new IllegalStateException("Timed out waiting for " + label);
        }
    }

    private static void throwIfFailure(AtomicReference<Throwable> failure) {
        Throwable throwable = failure.get();
        if (throwable != null) {
            throw new IllegalStateException("Consumed message validation failed", throwable);
        }
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

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JDK does not provide SHA-256", ex);
        }
    }

    private static String sha256Hex(byte[] value) {
        return java.util.HexFormat.of().formatHex(sha256(value));
    }

    private static byte[] hexToBytes(String value) {
        return java.util.HexFormat.of().parseHex(value);
    }

    private static byte[] frame(Object... fields) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Object field : fields) {
            byte[] bytes;
            if (field instanceof UUID uuid) {
                bytes = uuidBytes(uuid);
            }
            else if (field instanceof String text) {
                bytes = text.getBytes(StandardCharsets.UTF_8);
            }
            else if (field instanceof byte[] raw) {
                bytes = raw;
            }
            else {
                bytes = field.toString().getBytes(StandardCharsets.UTF_8);
            }
            out.writeBytes(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
            out.writeBytes(bytes);
        }
        return out.toByteArray();
    }

    private static byte[] uuidBytes(UUID uuid) {
        return ByteBuffer.allocate(16)
                .putLong(uuid.getMostSignificantBits())
                .putLong(uuid.getLeastSignificantBits())
                .array();
    }

    private record Credentials(String accessKey, String secretKey) {

        private static Credentials required(
                String accessEnvironment,
                String secretEnvironment,
                String expectedAccessKey) {
            String accessKey = setting(accessEnvironment, accessEnvironment, null);
            String secretKey = setting(secretEnvironment, secretEnvironment, null);
            require(expectedAccessKey.equals(accessKey),
                    accessEnvironment + " must be frozen as " + expectedAccessKey);
            require(secretKey.matches("[A-Za-z0-9]{16,}"),
                    secretEnvironment + " must be at least 16 ASCII alphanumeric characters");
            return new Credentials(accessKey, secretKey);
        }

        private static Credentials optional(
                String accessEnvironment,
                String secretEnvironment,
                String expectedAccessKey) {
            String accessKey = System.getenv(accessEnvironment);
            String secretKey = System.getenv(secretEnvironment);
            if ((accessKey == null || accessKey.isBlank())
                    && (secretKey == null || secretKey.isBlank())) {
                return null;
            }
            require(accessKey != null && !accessKey.isBlank()
                            && secretKey != null && !secretKey.isBlank(),
                    accessEnvironment + " and " + secretEnvironment
                            + " must either both be set or both be absent");
            require(expectedAccessKey.equals(accessKey.trim()),
                    accessEnvironment + " must be frozen as " + expectedAccessKey);
            require(secretKey.trim().matches("[A-Za-z0-9]{16,}"),
                    secretEnvironment + " must be at least 16 ASCII alphanumeric characters");
            return new Credentials(accessKey.trim(), secretKey.trim());
        }

        private RPCHook hook() {
            return new AclClientRPCHook(new SessionCredentials(accessKey, secretKey));
        }
    }

    private record ProbeEnvelope(
            String messageId,
            String tenantId,
            String ticketId,
            byte[] body,
            Map<String, String> properties) {

        private static ProbeEnvelope create() {
            String messageId = UUID.randomUUID().toString().toLowerCase(Locale.ROOT);
            String tenantId = UUID.randomUUID().toString().toLowerCase(Locale.ROOT);
            String ticketId = UUID.randomUUID().toString().toLowerCase(Locale.ROOT);
            String workflowDefinitionId = UUID.randomUUID().toString().toLowerCase(Locale.ROOT);
            String workflowVersionId = UUID.randomUUID().toString().toLowerCase(Locale.ROOT);
            String requesterId = UUID.randomUUID().toString().toLowerCase(Locale.ROOT);
            String correlationId = UUID.randomUUID().toString().toLowerCase(Locale.ROOT);
            String occurredAt = MICROSECOND_INSTANT.format(Instant.now());
            // Ticket currently serializes a TreeMap, so the probe fixture uses
            // the same stable lexicographic order while preserving JSON types.
            String bodyJson = "{\"requesterUserId\":\"" + requesterId
                    + "\",\"schemaVersion\":\"1\",\"serviceRiskLevel\":\"HIGH\""
                    + ",\"ticketId\":\"" + ticketId
                    + "\",\"versionNo\":1,\"workflowDefinitionId\":\"" + workflowDefinitionId
                    + "\",\"workflowVersionId\":\"" + workflowVersionId + "\"}";
            byte[] body = bodyJson.getBytes(StandardCharsets.UTF_8);
            Map<String, String> properties = new LinkedHashMap<>();
            properties.put("fd_envelope_version", "1");
            properties.put("fd_event_type", "flowdesk.workflow.start");
            properties.put("fd_contract_ref", "flowdesk.workflow.start.v1");
            properties.put("fd_contract_version", "1");
            properties.put("fd_producer", "flowdesk-ticket-service");
            properties.put("fd_tenant_id", tenantId);
            properties.put("fd_message_id", messageId);
            properties.put("fd_business_intent_key", sha256Hex(frame(
                    "FD-WORKFLOW-START-V1",
                    UUID.fromString(tenantId),
                    UUID.fromString(ticketId))));
            properties.put("fd_aggregate_type", "TICKET");
            properties.put("fd_aggregate_id", ticketId);
            properties.put("fd_correlation_id", correlationId);
            properties.put("fd_occurred_at", occurredAt);
            properties.put("fd_body_sha256", sha256Hex(body));
            return new ProbeEnvelope(messageId, tenantId, ticketId, body, Map.copyOf(properties));
        }

        private Message toMessage(String topic) {
            Message message = new Message(topic, Arrays.copyOf(body, body.length));
            message.setKeys(messageId);
            for (Map.Entry<String, String> property : properties.entrySet()) {
                message.putUserProperty(property.getKey(), property.getValue());
            }
            return message;
        }
    }

    @FunctionalInterface
    private interface CheckedOperation {
        void run() throws Exception;
    }
}
