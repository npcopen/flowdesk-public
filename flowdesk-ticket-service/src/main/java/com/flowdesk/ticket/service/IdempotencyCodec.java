package com.flowdesk.ticket.service;

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
 * 工单命令的确定性幂等身份编码（API-SPEC §4.2/4.3）。
 * 字段分帧与摘要派生保持和 IAM 编码器一致，租户、操作者及操作范围共同限定幂等键。
 */
public final class IdempotencyCodec {

    /**
     * 私有构造方法，阻止工具类实例化，调用方应使用静态入口。
     */
    private IdempotencyCodec() {
    }

    /** 把租户、操作者、操作标识、资源范围与客户端幂等键共同派生为效果键，隔离不同命令意图。 */
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

    /**
     * 将副作用键映射为稳定 UUID，使重试沿用同一业务身份。
     */
    public static UUID effectKeyUuid(TenantContext context,
                                     String operationId,
                                     String resourceScope,
                                     String idempotencyKey) {
        return uuidFromDigest(effectKey(context, operationId, resourceScope, idempotencyKey));
    }

    /** 用显式版本命名空间派生副作用身份，避免不同用途的摘要意外共用。 */
    public static byte[] derivedKey(String version, Object... fields) {
        Object[] framed = new Object[fields.length + 1];
        framed[0] = version;
        System.arraycopy(fields, 0, framed, 1, fields.length);
        return sha256(frame(framed));
    }

    /**
     * 计算规范请求摘要，用于判别幂等键是否对应同一业务意图。
     */
    public static byte[] requestDigest(String canonicalIntentObject) {
        return sha256(canonicalIntentObject.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 返回请求摘要的十六进制表示，供审计与重放一致性检查。
     */
    public static String requestDigestHex(String canonicalIntentObject) {
        return HexFormat.of().formatHex(requestDigest(canonicalIntentObject));
    }

    /**
     * 取得发布身份，供发件箱去重与重复投递核验。
     */
    public static byte[] publicationKey(String version, UUID tenantId, UUID messageId) {
        return sha256(frame(version, tenantId, messageId));
    }

    /**
     * 从摘要派生稳定 UUID，使相同摘要映射到同一记录身份。
     */
    public static UUID uuidFromDigest(byte[] digest) {
        byte[] first16 = Arrays.copyOf(digest, 16);
        ByteBuffer buffer = ByteBuffer.wrap(first16);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    /**
     * 返回当前操作主体类型，区分用户操作与服务操作。
     */
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

    /**
     * 返回当前操作主体标识，用于业务归属、审计和幂等键派生。
     */
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

    /** 每个字段先编码为长度再拼接内容，防止不同字段边界形成相同的字节串。 */
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

    /**
     * 将 UUID 编码为固定长度字节，供摘要和数据库标识使用。
     */
    private static byte[] uuidBytes(UUID uuid) {
        ByteBuffer buffer = ByteBuffer.allocate(16);
        buffer.putLong(uuid.getMostSignificantBits());
        buffer.putLong(uuid.getLeastSignificantBits());
        return buffer.array();
    }

    /**
     * 计算输入内容 SHA-256 摘要，供消息完整性和业务意图核验。
     */
    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
