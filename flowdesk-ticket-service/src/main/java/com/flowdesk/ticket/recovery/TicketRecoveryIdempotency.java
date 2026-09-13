package com.flowdesk.ticket.recovery;

import com.flowdesk.common.tenant.ActorRef;
import com.flowdesk.common.tenant.TenantContext;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.UUID;

final class TicketRecoveryIdempotency {
    private TicketRecoveryIdempotency() { }
    static UUID auditId(TenantContext context, UUID recoveryId, String key) {
        if (!(context.actor() instanceof ActorRef.Human human)) throw new IllegalStateException();
        return uuid(digest("FD-IDEMPOTENCY-V1", context.tenantId(), "USER", human.userId(),
                "API-060", "TICKET", recoveryId, key));
    }
    static String requestDigest(UUID recoveryId, String reason) {
        return HexFormat.of().formatHex(digest(
                "FD-TICKET-RECOVERY-INTENT-V1", recoveryId, reason));
    }
    static UUID outcomeAuditId(UUID tenantId, UUID attemptCorrelation, String outcome) {
        return uuid(digest("FD-TICKET-RECOVERY-OUTCOME-V1",
                tenantId, attemptCorrelation, outcome));
    }
    private static byte[] digest(Object... fields) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (Object field : fields) {
                byte[] bytes = field instanceof UUID id ? ByteBuffer.allocate(16)
                        .putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array()
                        : field.toString().getBytes(StandardCharsets.UTF_8);
                out.writeBytes(ByteBuffer.allocate(4).putInt(bytes.length).array());
                out.writeBytes(bytes);
            }
            return MessageDigest.getInstance("SHA-256").digest(out.toByteArray());
        }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
    private static UUID uuid(byte[] digest) {
        ByteBuffer b = ByteBuffer.wrap(Arrays.copyOf(digest, 16));
        return new UUID(b.getLong(), b.getLong());
    }
}
