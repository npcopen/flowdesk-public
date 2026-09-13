package com.flowdesk.ticket.sla;

import com.flowdesk.common.notification.NotificationWireCodec;

import java.util.HexFormat;
import java.time.Instant;
import java.util.UUID;

/** Canonical Redis key/member encoding; UUIDs are raw 16-byte lower-case hex. */
final class SlaDueIndexCodec {
    private static final HexFormat HEX = HexFormat.of();

    private SlaDueIndexCodec() {
    }

    static String tenantHex(UUID tenantId) {
        return HEX.formatHex(NotificationWireCodec.uuidBytes(tenantId));
    }

    static String key(UUID tenantId) {
        return "flowdesk:ticket:sla:due:v1:{" + tenantHex(tenantId) + "}";
    }

    static String rebuildKey(UUID runId, UUID tenantId) {
        if (runId == null || tenantId == null) {
            throw new IllegalArgumentException("SLA rebuild identifiers must not be null");
        }
        // Keep the temporary and formal keys in the same hash slot.  This is
        // required for the fenced Lua swap when Redis Cluster is used.
        return "flowdesk:ticket:sla:due:rebuild:" + runId + ":{" + tenantHex(tenantId) + "}";
    }

    static String member(UUID tenantId, UUID clockId, String stage) {
        if (tenantId == null || clockId == null) {
            throw new IllegalArgumentException("SLA index identifiers must not be null");
        }
        if (!"REMINDER".equals(stage) && !"BREACH".equals(stage)) {
            throw new IllegalArgumentException("Unsupported SLA index stage");
        }
        return tenantHex(tenantId) + ":" + tenantHex(clockId) + ":" + stage;
    }

    /** Floors UTC epoch microseconds to the Redis millisecond candidate score. */
    static long score(Instant dueAt) {
        if (dueAt == null) throw new IllegalArgumentException("SLA dueAt must not be null");
        long micros = Math.addExact(Math.multiplyExact(dueAt.getEpochSecond(), 1_000_000L),
                dueAt.getNano() / 1_000L);
        return Math.floorDiv(micros, 1_000L);
    }

    static SlaDueIndex.Candidate parse(String member, double score) {
        if (member == null) {
            throw new IllegalArgumentException("SLA index member is null");
        }
        String[] parts = member.split(":", -1);
        if (parts.length != 3 || parts[0].length() != 32 || parts[1].length() != 32
                || !("REMINDER".equals(parts[2]) || "BREACH".equals(parts[2]))) {
            throw new IllegalArgumentException("Malformed SLA index member");
        }
        return new SlaDueIndex.Candidate(uuid(parts[0]), uuid(parts[1]), parts[2], score);
    }

    private static UUID uuid(String value) {
        byte[] bytes = HEX.parseHex(value);
        if (bytes.length != 16) {
            throw new IllegalArgumentException("Malformed UUID member");
        }
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}
