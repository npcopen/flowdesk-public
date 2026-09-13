package com.flowdesk.iam.idempotency;

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

/**
 * Deterministic idempotency anchors for IAM commands (API-SPEC §4.2/4.3).
 */
public final class IdempotencyCodec {

    private IdempotencyCodec() {
    }

    public static byte[] effectKey(TenantContext context,
                                   String operationId,
                                   String resourceScope,
                                   String idempotencyKey) {
        return sha256(frame(
                "FD-IDEMPOTENCY-V1",
                context.tenantId(),
                actorType(context.actor()),
                actorId(context.actor()),
                operationId,
                resourceScope,
                idempotencyKey));
    }

    public static UUID effectKeyUuid(TenantContext context,
                                     String operationId,
                                     String resourceScope,
                                     String idempotencyKey) {
        return uuidFromDigest(effectKey(context, operationId, resourceScope, idempotencyKey));
    }

    public static byte[] requestDigest(String canonicalIntentObject) {
        return sha256(canonicalIntentObject.getBytes(StandardCharsets.UTF_8));
    }

    public static String requestDigestHex(String canonicalIntentObject) {
        return HexFormat.of().formatHex(requestDigest(canonicalIntentObject));
    }

    public static UUID uuidFromDigest(byte[] digest) {
        byte[] first16 = Arrays.copyOf(digest, 16);
        ByteBuffer buffer = ByteBuffer.wrap(first16);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    private static String actorType(ActorRef actor) {
        if (actor instanceof ActorRef.Human) {
            return "USER";
        }
        if (actor instanceof ActorRef.Service) {
            return "SERVICE";
        }
        if (actor instanceof ActorRef.Integration) {
            return "INTEGRATION";
        }
        throw new IllegalArgumentException("Unknown actor type: " + actor);
    }

    private static byte[] actorId(ActorRef actor) {
        if (actor instanceof ActorRef.Human human) {
            return uuidBytes(human.userId());
        }
        if (actor instanceof ActorRef.Service service) {
            return service.serviceId().getBytes(StandardCharsets.UTF_8);
        }
        if (actor instanceof ActorRef.Integration integration) {
            return uuidBytes(integration.integrationId());
        }
        throw new IllegalArgumentException("Unknown actor type: " + actor);
    }

    private static byte[] frame(Object... fields) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Object field : fields) {
            byte[] bytes;
            if (field instanceof UUID uuid) {
                bytes = uuidBytes(uuid);
            } else if (field instanceof String text) {
                bytes = text.getBytes(StandardCharsets.UTF_8);
            } else if (field instanceof byte[] raw) {
                bytes = raw;
            } else {
                bytes = field.toString().getBytes(StandardCharsets.UTF_8);
            }
            byte[] length = ByteBuffer.allocate(4).putInt(bytes.length).array();
            out.writeBytes(length);
            out.writeBytes(bytes);
        }
        return out.toByteArray();
    }

    private static byte[] uuidBytes(UUID uuid) {
        ByteBuffer buffer = ByteBuffer.allocate(16);
        buffer.putLong(uuid.getMostSignificantBits());
        buffer.putLong(uuid.getLeastSignificantBits());
        return buffer.array();
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
