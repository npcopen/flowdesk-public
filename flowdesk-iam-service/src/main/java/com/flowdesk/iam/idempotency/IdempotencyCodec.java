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
 * 为 IAM 写命令派生确定性的幂等锚点：相同租户、操作者、操作和请求键得到同一副作用身份。
 */
public final class IdempotencyCodec {

    /**
     * 禁止直接实例化该无状态工具类；调用方通过其静态方法或嵌套契约模型使用能力。
     */
    private IdempotencyCodec() {
    }

    /**
     * 对命名空间、租户、操作者、资源范围及幂等键进行分帧后派生摘要，用于识别一次稳定业务副作用。
     */
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
     * 把稳定业务副作用摘要映射为 UUID，供资源主键或审计锚点复用同一幂等身份。
     */
    public static UUID effectKeyUuid(TenantContext context,
                                     String operationId,
                                     String resourceScope,
                                     String idempotencyKey) {
        return uuidFromDigest(effectKey(context, operationId, resourceScope, idempotencyKey));
    }

    /**
     * 计算规范化业务意图的 SHA-256 摘要，供同幂等键下的内容一致性核对。
     */
    public static byte[] requestDigest(String canonicalIntentObject) {
        return sha256(canonicalIntentObject.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 把规范化业务意图摘要编码为十六进制文本，适配审计字段的持久化和比较。
     */
    public static String requestDigestHex(String canonicalIntentObject) {
        return HexFormat.of().formatHex(requestDigest(canonicalIntentObject));
    }

    /**
     * 从摘要的固定字节构造 UUID，要求输入具有足够长度，避免依赖平台字符编码。
     */
    public static UUID uuidFromDigest(byte[] digest) {
        byte[] first16 = Arrays.copyOf(digest, 16);
        ByteBuffer buffer = ByteBuffer.wrap(first16);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    /**
     * 将可信上下文中的操作者类别编码为幂等输入，区分人类、服务及其他受支持主体。
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
     * 提取可信上下文中的人类或服务标识，作为幂等摘要的操作者维度；不支持的主体明确拒绝。
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

    /**
     * 以显式长度分帧组合字段，避免直接拼接造成不同输入组合共享同一字节序列。
     */
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
            // 每个字段先写入四字节长度，再写内容，区分如 ab+c 与 a+bc 这样的不同输入组合。
            byte[] length = ByteBuffer.allocate(4).putInt(bytes.length).array();
            out.writeBytes(length);
            out.writeBytes(bytes);
        }
        return out.toByteArray();
    }

    /**
     * 将 UUID 的高低 64 位按固定二进制顺序编码，供跨环境稳定摘要派生。
     */
    private static byte[] uuidBytes(UUID uuid) {
        ByteBuffer buffer = ByteBuffer.allocate(16);
        buffer.putLong(uuid.getMostSignificantBits());
        buffer.putLong(uuid.getLeastSignificantBits());
        return buffer.array();
    }

    /**
     * 计算输入字节的 SHA-256 摘要；算法不可用时抛出服务端异常，不使用不安全的替代摘要。
     */
    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
