package com.flowdesk.workflow.start;

import com.flowdesk.common.tenant.ActorRef;
import com.flowdesk.common.tenant.TenantContext;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.UUID;

/** API-060 stable effect and replay-attempt audit identifiers. */
final class WorkflowRecoveryIdempotency {

    private WorkflowRecoveryIdempotency() {
    }

    static UUID replayAuditId(TenantContext context, UUID recoveryId, String key) {
        if (!(context.actor() instanceof ActorRef.Human human)) {
            throw new IllegalStateException("Workflow recovery replay requires a human actor");
        }
        return uuid(sha256(frame(
                "FD-IDEMPOTENCY-V1",
                context.tenantId(),
                "USER",
                human.userId(),
                "API-060",
                "WORKFLOW",
                recoveryId,
                key)));
    }

    static UUID outcomeAuditId(UUID tenantId, UUID attemptCorrelationId, String outcome) {
        return uuid(sha256(frame(
                "FD-WORKFLOW-RECOVERY-OUTCOME-V1",
                tenantId,
                attemptCorrelationId,
                outcome)));
    }

    static String requestDigest(UUID recoveryId, String reason) {
        return HexFormat.of().formatHex(sha256(frame(
                "FD-WORKFLOW-RECOVERY-INTENT-V1", recoveryId, reason)));
    }

    private static byte[] frame(Object... fields) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Object field : fields) {
            byte[] bytes;
            if (field instanceof UUID uuid) {
                bytes = ByteBuffer.allocate(16)
                        .putLong(uuid.getMostSignificantBits())
                        .putLong(uuid.getLeastSignificantBits())
                        .array();
            }
            else {
                bytes = field.toString().getBytes(StandardCharsets.UTF_8);
            }
            out.writeBytes(ByteBuffer.allocate(4).putInt(bytes.length).array());
            out.writeBytes(bytes);
        }
        return out.toByteArray();
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        }
        catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static UUID uuid(byte[] digest) {
        ByteBuffer buffer = ByteBuffer.wrap(Arrays.copyOf(digest, 16));
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}
