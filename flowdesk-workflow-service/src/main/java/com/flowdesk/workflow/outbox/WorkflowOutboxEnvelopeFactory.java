package com.flowdesk.workflow.outbox;

import com.flowdesk.workflow.event.WorkflowReverseEventContract;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Creates either frozen reverse envelope directly from the persisted first bytes. */
final class WorkflowOutboxEnvelopeFactory {
    private static final DateTimeFormatter MICROS = new DateTimeFormatterBuilder()
            .appendInstant(6).toFormatter(Locale.ROOT);

    private WorkflowOutboxEnvelopeFactory() {
    }

    static WorkflowOutboxMessage create(WorkflowOutboxRepository.Claimed row) {
        Binding binding = Binding.of(row.contractRef());
        if (row.contractVersion() != WorkflowReverseEventContract.CONTRACT_VERSION
                || !WorkflowReverseEventContract.AGGREGATE_TYPE.equals(row.aggregateType())) {
            throw new InvalidContract("Outbox contract identity is not frozen M6 v1");
        }
        byte[] body = row.bodyBytes();
        if (body.length == 0 || body.length > WorkflowReverseEventContract.MAX_BODY_BYTES) {
            throw new InvalidContract("Outbox body size is invalid");
        }
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(body));
        }
        catch (Exception invalid) {
            throw new InvalidContract("Outbox body is not UTF-8");
        }
        byte[] hash = sha256(body);
        if (!MessageDigest.isEqual(hash, row.bodySha256())
                || row.businessIntentKey().length != 32
                || row.publicationKey().length != 32) {
            throw new InvalidContract("Outbox persisted digests are invalid");
        }

        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("fd_envelope_version", WorkflowReverseEventContract.ENVELOPE_VERSION);
        properties.put("fd_event_type", binding.eventType());
        properties.put("fd_contract_ref", row.contractRef());
        properties.put("fd_contract_version", Integer.toString(row.contractVersion()));
        properties.put("fd_producer", WorkflowReverseEventContract.PRODUCER_SERVICE);
        properties.put("fd_tenant_id", row.tenantId().toString());
        properties.put("fd_message_id", row.messageId().toString());
        properties.put("fd_business_intent_key", HexFormat.of().formatHex(row.businessIntentKey()));
        properties.put("fd_aggregate_type", row.aggregateType());
        properties.put("fd_aggregate_id", row.aggregateId().toString());
        properties.put("fd_correlation_id", row.correlationId().toString());
        if (row.causationId() != null) {
            properties.put("fd_causation_id", row.causationId().toString());
        }
        properties.put("fd_occurred_at", MICROS.format(row.createdAt()));
        properties.put("fd_body_sha256", HexFormat.of().formatHex(hash));
        return new WorkflowOutboxMessage(
                binding.topic(), row.messageId().toString(), body, properties);
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        }
        catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record Binding(String contractRef, String eventType, String topic) {
        static Binding of(String contractRef) {
            if (WorkflowReverseEventContract.InstanceEstablished.CONTRACT_REF.equals(contractRef)) {
                return new Binding(contractRef,
                        WorkflowReverseEventContract.InstanceEstablished.EVENT_TYPE,
                        WorkflowReverseEventContract.InstanceEstablished.TOPIC);
            }
            if (WorkflowReverseEventContract.ApprovalFinalized.CONTRACT_REF.equals(contractRef)) {
                return new Binding(contractRef,
                        WorkflowReverseEventContract.ApprovalFinalized.EVENT_TYPE,
                        WorkflowReverseEventContract.ApprovalFinalized.TOPIC);
            }
            throw new InvalidContract("Outbox contract is not an activated M6 reverse event");
        }
    }

    static final class InvalidContract extends RuntimeException {
        InvalidContract(String message) {
            super(message);
        }
    }
}
