package com.flowdesk.infra.rocketmq;

import org.apache.rocketmq.acl.common.AclClientRPCHook;
import org.apache.rocketmq.acl.common.SessionCredentials;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

/** Publishes one persisted M7 recovery Envelope without accepting credentials as arguments. */
public final class RocketMqIncidentRecoveryPublisher {
    private static final String TOPIC = "flowdesk_incident_recovered_v1";
    private static final String PRODUCER_GROUP = "flowdesk_incident_recovered_v1_alert_pg";
    private static final String ACCESS_KEY = "flowdeskalert";
    private static final int SEND_TIMEOUT_MILLIS = 5_000;
    private static final Set<String> REQUIRED = Set.of(
            "topic", "producer_group", "keys", "body_base64",
            "fd_envelope_version", "fd_event_type", "fd_contract_ref",
            "fd_contract_version", "fd_producer", "fd_tenant_id", "fd_message_id",
            "fd_business_intent_key", "fd_aggregate_type", "fd_aggregate_id",
            "fd_correlation_id", "fd_occurred_at", "fd_body_sha256");
    private static final Set<String> ALLOWED;

    static {
        LinkedHashSet<String> allowed = new LinkedHashSet<>(REQUIRED);
        allowed.add("fd_causation_id");
        ALLOWED = Set.copyOf(allowed);
    }

    private RocketMqIncidentRecoveryPublisher() {
    }

    public static void main(String[] args) throws Exception {
        Path envelopePath = Path.of(requiredProperty("flowdesk.rocketmq.envelopeFile")).toRealPath();
        require(Files.isRegularFile(envelopePath), "Envelope properties path is not a file");
        require(Files.size(envelopePath) <= 256 * 1024, "Envelope properties file is too large");
        Properties source = new Properties();
        try (InputStream input = Files.newInputStream(envelopePath)) {
            source.load(input);
        }
        require(source.stringPropertyNames().equals(ALLOWED)
                        || source.stringPropertyNames().equals(REQUIRED),
                "Envelope properties must contain only the frozen fields");
        for (String name : REQUIRED) {
            require(source.getProperty(name) != null && !source.getProperty(name).isBlank(),
                    "Missing required Envelope property " + name);
        }

        require(TOPIC.equals(source.getProperty("topic")), "Unexpected recovery Topic");
        require(PRODUCER_GROUP.equals(source.getProperty("producer_group")),
                "Unexpected recovery producer group");
        fixed(source, "fd_envelope_version", "1");
        fixed(source, "fd_event_type", "flowdesk.incident.recovered");
        fixed(source, "fd_contract_ref", "flowdesk.incident.recovered.v1");
        fixed(source, "fd_contract_version", "1");
        fixed(source, "fd_producer", "flowdesk-alert-service");
        fixed(source, "fd_aggregate_type", "INCIDENT");
        canonicalUuid(source.getProperty("fd_tenant_id"), "fd_tenant_id");
        canonicalUuid(source.getProperty("fd_message_id"), "fd_message_id");
        canonicalUuid(source.getProperty("fd_aggregate_id"), "fd_aggregate_id");
        canonicalUuid(source.getProperty("fd_correlation_id"), "fd_correlation_id");
        if (source.containsKey("fd_causation_id")) {
            canonicalUuid(source.getProperty("fd_causation_id"), "fd_causation_id");
        }
        require(source.getProperty("keys").equals(source.getProperty("fd_message_id")),
                "RocketMQ KEYS must equal fd_message_id");
        lowerHex(source.getProperty("fd_business_intent_key"), "fd_business_intent_key");
        lowerHex(source.getProperty("fd_body_sha256"), "fd_body_sha256");
        require(source.getProperty("fd_occurred_at").matches(
                        "[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{6}Z"),
                "fd_occurred_at must use UTC with six fractional digits");

        byte[] body;
        try {
            body = Base64.getDecoder().decode(source.getProperty("body_base64"));
        }
        catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("body_base64 is invalid", invalid);
        }
        require(body.length > 0 && body.length <= 64 * 1024, "Body size is outside 1..65536 bytes");
        require(!startsWithUtf8Bom(body), "Body must not contain a UTF-8 BOM");
        String actualHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        require(MessageDigest.isEqual(actualHash.getBytes(),
                        source.getProperty("fd_body_sha256").getBytes()),
                "Body SHA-256 does not match fd_body_sha256");

        String nameServer = setting("FLOWDESK_ROCKETMQ_NAME_SERVER", "127.0.0.1:9876");
        String accessKey = setting("FLOWDESK_ALERT_ROCKETMQ_ACCESS_KEY", null);
        String secretKey = setting("FLOWDESK_ALERT_ROCKETMQ_SECRET_KEY", null);
        require(ACCESS_KEY.equals(accessKey), "Alert AccessKey is not the frozen identity");
        require(secretKey.matches("[A-Za-z0-9]{16,}"),
                "Alert SecretKey must be at least 16 ASCII alphanumeric characters");

        DefaultMQProducer producer = new DefaultMQProducer(PRODUCER_GROUP,
                new AclClientRPCHook(new SessionCredentials(accessKey, secretKey)));
        producer.setNamesrvAddr(nameServer);
        producer.setInstanceName("flowdesk-m7-late-recovery-" + UUID.randomUUID());
        producer.setVipChannelEnabled(false);
        producer.setSendMsgTimeout(SEND_TIMEOUT_MILLIS);
        producer.setRetryTimesWhenSendFailed(0);
        producer.setRetryTimesWhenSendAsyncFailed(0);
        producer.setRetryAnotherBrokerWhenNotStoreOK(false);
        try {
            producer.start();
            Message message = new Message(TOPIC, body);
            message.setKeys(source.getProperty("keys"));
            for (String name : source.stringPropertyNames()) {
                if (name.startsWith("fd_")) message.putUserProperty(name, source.getProperty(name));
            }
            SendResult result = producer.send(message, SEND_TIMEOUT_MILLIS);
            require(result.getSendStatus() == SendStatus.SEND_OK,
                    "Broker returned " + result.getSendStatus());
            System.out.printf(Locale.ROOT,
                    "FLOWDESK_ROCKETMQ_M7_LATE_RECOVERY_PUBLISHED "
                            + "topic=%s key=%s broker=%s queueId=%d queueOffset=%d msgId=%s%n",
                    TOPIC, source.getProperty("keys"), result.getMessageQueue().getBrokerName(),
                    result.getMessageQueue().getQueueId(), result.getQueueOffset(), result.getMsgId());
        }
        finally {
            producer.shutdown();
        }
    }

    private static void fixed(Properties source, String name, String expected) {
        require(expected.equals(source.getProperty(name)), name + " has an unexpected value");
    }

    private static void canonicalUuid(String value, String name) {
        try {
            require(UUID.fromString(value).toString().equals(value), name + " must be canonical lowercase UUID");
        }
        catch (RuntimeException invalid) {
            throw new IllegalArgumentException(name + " must be canonical lowercase UUID", invalid);
        }
    }

    private static void lowerHex(String value, String name) {
        require(value.matches("[0-9a-f]{64}"), name + " must be 64 lowercase hex characters");
    }

    private static boolean startsWithUtf8Bom(byte[] body) {
        return body.length >= 3 && body[0] == (byte) 0xef
                && body[1] == (byte) 0xbb && body[2] == (byte) 0xbf;
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        require(value != null && !value.isBlank(), name + " is required");
        return value;
    }

    private static String setting(String environment, String fallback) {
        String value = System.getenv(environment);
        if (value == null || value.isBlank()) value = fallback;
        require(value != null && !value.isBlank(), environment + " is required");
        return value.trim();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
