package com.flowdesk.ticket.incident;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;

final class IncidentResultEnvelopeFactory {
    private static final DateTimeFormatter MICROS = new DateTimeFormatterBuilder()
            .appendInstant(6).toFormatter(Locale.ROOT);
    private IncidentResultEnvelopeFactory() { }

    static IncidentResultMessage create(IncidentResultOutboxRepository.Claimed row) {
        byte[] body = row.body();
        if (body.length == 0 || body.length > IncidentMessageContract.MAX_BODY_BYTES
                || row.intent().length != 32 || row.publicationKey().length != 32) {
            throw new IllegalArgumentException("Persisted Incident result contract is invalid");
        }
        try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body));
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(body);
            if (!MessageDigest.isEqual(hash, row.hash())) {
                throw new IllegalArgumentException("Persisted Incident result body hash is invalid");
            }
            LinkedHashMap<String, String> p = new LinkedHashMap<>();
            p.put("fd_envelope_version", IncidentMessageContract.ENVELOPE_VERSION);
            p.put("fd_event_type", IncidentMessageContract.ESTABLISHED_EVENT_TYPE);
            p.put("fd_contract_ref", IncidentMessageContract.ESTABLISHED_CONTRACT_REF);
            p.put("fd_contract_version", "1");
            p.put("fd_producer", IncidentMessageContract.TICKET_PRODUCER);
            p.put("fd_tenant_id", row.tenantId().toString());
            p.put("fd_message_id", row.messageId().toString());
            p.put("fd_business_intent_key", HexFormat.of().formatHex(row.intent()));
            p.put("fd_aggregate_type", IncidentMessageContract.AGGREGATE_TYPE);
            p.put("fd_aggregate_id", row.incidentId().toString());
            p.put("fd_correlation_id", row.correlationId().toString());
            if (row.causationId() != null) p.put("fd_causation_id", row.causationId().toString());
            p.put("fd_occurred_at", MICROS.format(row.createdAt()));
            p.put("fd_body_sha256", HexFormat.of().formatHex(hash));
            return new IncidentResultMessage(IncidentMessageContract.ESTABLISHED_TOPIC,
                    row.messageId().toString(), body, p);
        }
        catch (IllegalArgumentException invalid) { throw invalid; }
        catch (Exception invalid) { throw new IllegalArgumentException("Persisted Incident result is invalid", invalid); }
    }
}
