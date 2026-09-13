package com.flowdesk.ticket.outbox;

import com.flowdesk.ticket.event.TicketEventContract;

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

/** Builds the frozen v1 transport envelope without reserializing persisted body bytes. */
final class WorkflowStartV1EnvelopeFactory {

    private static final int MAX_BODY_BYTES = 64 * 1024;
    private static final DateTimeFormatter SIX_DIGIT_UTC = new DateTimeFormatterBuilder()
            .appendInstant(6)
            .toFormatter(Locale.ROOT);

    private WorkflowStartV1EnvelopeFactory() {
    }

    static TicketOutboxMessage create(TicketOutboxRepository.ClaimedOutbox outbox) {
        requireFrozenContract(outbox);
        byte[] body = outbox.bodyBytes();
        if (body.length == 0 || body.length > MAX_BODY_BYTES) {
            throw new InvalidOutboxContractException("body size is outside the frozen contract");
        }
        requireUtf8(body);
        byte[] actualHash = sha256(body);
        if (!MessageDigest.isEqual(actualHash, outbox.bodySha256())) {
            throw new InvalidOutboxContractException("persisted body hash does not match body bytes");
        }
        if (outbox.businessIntentKey().length != 32 || outbox.publicationKey().length != 32) {
            throw new InvalidOutboxContractException("persisted Outbox keys must be SHA-256 values");
        }

        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("fd_envelope_version",
                TicketEventContract.WorkflowStartRequested.ENVELOPE_VERSION);
        properties.put("fd_event_type", TicketEventContract.WorkflowStartRequested.EVENT_TYPE);
        properties.put("fd_contract_ref", outbox.contractRef());
        properties.put("fd_contract_version", Integer.toString(outbox.contractVersion()));
        properties.put("fd_producer", TicketEventContract.WorkflowStartRequested.PRODUCER_SERVICE);
        properties.put("fd_tenant_id", outbox.tenantId().toString());
        properties.put("fd_message_id", outbox.messageId().toString());
        properties.put("fd_business_intent_key",
                HexFormat.of().formatHex(outbox.businessIntentKey()));
        properties.put("fd_aggregate_type", outbox.aggregateType());
        properties.put("fd_aggregate_id", outbox.aggregateId().toString());
        properties.put("fd_correlation_id", outbox.correlationId().toString());
        if (outbox.causationId() != null) {
            properties.put("fd_causation_id", outbox.causationId().toString());
        }
        properties.put("fd_occurred_at", SIX_DIGIT_UTC.format(outbox.createdAt()));
        properties.put("fd_body_sha256", HexFormat.of().formatHex(actualHash));

        return new TicketOutboxMessage(
                TicketEventContract.WorkflowStartRequested.TOPIC,
                outbox.messageId().toString(), body, properties);
    }

    private static void requireFrozenContract(TicketOutboxRepository.ClaimedOutbox outbox) {
        if (!TicketEventContract.WorkflowStartRequested.CONTRACT_REF.equals(outbox.contractRef())
                || TicketEventContract.WorkflowStartRequested.EVENT_VERSION
                != outbox.contractVersion()
                || !TicketEventContract.WorkflowStartRequested.AGGREGATE_TYPE
                .equals(outbox.aggregateType())) {
            throw new InvalidOutboxContractException("Outbox row is not the frozen workflow-start v1 contract");
        }
    }

    private static void requireUtf8(byte[] body) {
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(body));
        }
        catch (Exception invalidUtf8) {
            throw new InvalidOutboxContractException("body bytes are not valid UTF-8", invalidUtf8);
        }
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        }
        catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    static final class InvalidOutboxContractException extends RuntimeException {
        InvalidOutboxContractException(String message) {
            super(message);
        }

        InvalidOutboxContractException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
