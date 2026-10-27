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

/**
 * 实现固定的长度分帧 SHA-256 派生算法，避免不同字段组合产生相同拼接输入。
 */
public final class WorkflowIdempotencyCodec {

    /**
     * 禁止直接实例化工具容器，相关静态规则与转换由调用方按业务需要复用。
     */
    private WorkflowIdempotencyCodec() {
    }

    /**
     * 结合租户、操作及请求身份派生业务副作用键，使幂等范围与业务入口一致。
     */
    public static byte[] effectKey(TenantContext context, String operationId,
                                   String resourceScope, String idempotencyKey) {
        return sha256(frame("FD-IDEMPOTENCY-V1", context.tenantId(), actorType(context.actor()),
                actorId(context.actor()), operationId, resourceScope, idempotencyKey));
    }

    /**
     * 按固定命名域和组成部分派生业务键，避免不同用途间的键值碰撞。
     */
    public static byte[] derivedKey(String namespace, Object... fields) {
        Object[] all = new Object[fields.length + 1];
        all[0] = namespace;
        System.arraycopy(fields, 0, all, 1, fields.length);
        return sha256(frame(all));
    }

    /**
     * 返回消息发布键的防御性副本，避免调用方通过数组修改已保存事实。
     */
    public static byte[] publicationKey(UUID tenantId, UUID messageId) {
        return derivedKey("FD-PUB-V1", tenantId, messageId);
    }

    /**
     * 计算规范化请求摘要，判断相同幂等键是否被用于不同业务意图。
     */
    public static byte[] requestDigest(String canonicalIntent) {
        return sha256(canonicalIntent.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 将请求摘要转为十六进制文本，供持久化和幂等内容核验使用。
     */
    public static String requestDigestHex(String canonicalIntent) {
        return HexFormat.of().formatHex(requestDigest(canonicalIntent));
    }

    /**
     * 从摘要提取稳定 UUID，供幂等记录与相关副作用复用确定性身份。
     */
    public static UUID uuidFromDigest(byte[] digest) {
        ByteBuffer value = ByteBuffer.wrap(Arrays.copyOf(digest, 16));
        return new UUID(value.getLong(), value.getLong());
    }

    /**
     * 为摘要字段写入长度边界，避免不同字段组合产生拼接歧义。
     */
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
            // 长度前缀明确字段边界，所有生成端必须保持同一顺序、编码和 UUID 字节表示。
            out.writeBytes(ByteBuffer.allocate(4).putInt(value.length).array());
            out.writeBytes(value);
        }
        return out.toByteArray();
    }

    /**
     * 计算输入字节的 SHA-256 摘要，用于内容一致性核对及稳定业务键派生。
     */
    public static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        }
        catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    /**
     * 将 UUID 编码为高位在前的十六字节值，供存储及摘要计算使用。
     */
    public static byte[] uuidBytes(UUID value) {
        if (value == null) {
            return null;
        }
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits()).array();
    }

    /**
     * 在 UUID 与持久化表示之间转换，保持标识高低位的编码顺序一致。
     */
    public static UUID uuid(byte[] value) {
        ByteBuffer bytes = ByteBuffer.wrap(value);
        return new UUID(bytes.getLong(), bytes.getLong());
    }

    /**
     * 转换可空 UUID，空值保持为空以适配可选数据库字段。
     */
    public static UUID nullableUuid(byte[] value) {
        return value == null ? null : uuid(value);
    }

    /**
     * 将当前主体类型转换为幂等摘要使用的稳定文本。
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
        throw new IllegalArgumentException("Unsupported actor type");
    }

    /**
     * 取得当前操作者标识，供权限、幂等及审计记录使用。
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
        throw new IllegalArgumentException("Unsupported actor type");
    }
}
