package com.flowdesk.workflow.service;

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

/** Service-local implementation of the frozen length-framed SHA-256 algorithm. */
public final class WorkflowIdempotencyCodec {

    private WorkflowIdempotencyCodec() {
    }

    public static byte[] effectKey(TenantContext context, String operationId,
                                   String resourceScope, String idempotencyKey) {
        return sha256(frame("FD-IDEMPOTENCY-V1", context.tenantId(), actorType(context.actor()),
                actorId(context.actor()), operationId, resourceScope, idempotencyKey));
    }

    public static byte[] derivedKey(String namespace, Object... fields) {
        Object[] all = new Object[fields.length + 1];
        all[0] = namespace;
        System.arraycopy(fields, 0, all, 1, fields.length);
        return sha256(frame(all));
    }

    public static byte[] publicationKey(UUID tenantId, UUID messageId) {
        return derivedKey("FD-PUB-V1", tenantId, messageId);
    }

    public static byte[] requestDigest(String canonicalIntent) {
        return sha256(canonicalIntent.getBytes(StandardCharsets.UTF_8));
    }

    public static String requestDigestHex(String canonicalIntent) {
        return HexFormat.of().formatHex(requestDigest(canonicalIntent));
    }

    public static UUID uuidFromDigest(byte[] digest) {
        ByteBuffer value = ByteBuffer.wrap(Arrays.copyOf(digest, 16));
        return new UUID(value.getLong(), value.getLong());
    }

    public static byte[] frame(Object... fields) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Object field : fields) {
            byte[] value;
            if (field instanceof UUID uuid) {
                value = uuidBytes(uuid);
            }
            else if (field instanceof byte[] bytes) {
                value = bytes;
            }
            else {
                value = String.valueOf(field).getBytes(StandardCharsets.UTF_8);
            }
            out.writeBytes(ByteBuffer.allocate(4).putInt(value.length).array());
            out.writeBytes(value);
        }
        return out.toByteArray();
    }

    public static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        }
        catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    public static byte[] uuidBytes(UUID value) {
        if (value == null) {
            return null;
        }
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits()).array();
    }

    public static UUID uuid(byte[] value) {
        ByteBuffer bytes = ByteBuffer.wrap(value);
        return new UUID(bytes.getLong(), bytes.getLong());
    }

    public static UUID nullableUuid(byte[] value) {
        return value == null ? null : uuid(value);
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
        throw new IllegalArgumentException("Unsupported actor type");
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
        throw new IllegalArgumentException("Unsupported actor type");
    }
}
