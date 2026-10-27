package com.flowdesk.alert.recovery;

import com.flowdesk.common.tenant.ActorRef;
import com.flowdesk.common.tenant.TenantContext;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 为人工恢复派生稳定审计 ID 和请求意图摘要，本类只计算标识，不查询或写入数据库。
 *
 * <p>请求 ID 回答“这个幂等键是否已使用”，意图摘要再回答“是否仍是同一个请求”；
 * 完成审计使用另一命名空间，保证同一人工尝试被重新执行时不会重复记录同类结果。
 */
public final class AlertRecoveryIdempotency {
    /**
     * 禁止直接实例化该无状态工具类；调用方通过其静态方法或嵌套契约模型使用能力。
     */
    private AlertRecoveryIdempotency() {}

    /** 幂等键作用域包含租户、人工操作者、API、归属服务和恢复记录，避免不同主体的命令相互命中。 */
    public static UUID auditId(TenantContext c, UUID recovery, String key) {
        if (!(c.actor() instanceof ActorRef.Human h)) throw new IllegalStateException();
        return uuid(
                digest(
                        "FD-IDEMPOTENCY-V1",
                        c.tenantId(),
                        "USER",
                        h.userId(),
                        "API-060",
                        "ALERT",
                        recovery,
                        key));
    }

    /** 原因属于恢复意图；If-Match 不进入摘要，使已接纳请求携带原版本重试时仍可返回原结果。 */
    public static String requestDigest(UUID r, String reason) {
        return HexFormat.of().formatHex(digest("FD-ALERT-RECOVERY-INTENT-V1", r, reason));
    }

    /** c 为人工尝试标识而非原消息链路 ID；租约接管继续同一尝试，因此沿用同一个完成审计 ID。 */
    public static UUID outcomeAuditId(UUID t, UUID c, String outcome) {
        return uuid(digest("FD-ALERT-RECOVERY-OUTCOME-V1", t, c, outcome));
    }

    /** 每个字段写入长度前缀后再拼接，避免不同字段边界产生同一字节序列；字符串统一使用 UTF-8。 */
    private static byte[] digest(Object... fs) {
        try {
            var out = new ByteArrayOutputStream();
            for (Object f : fs) {
                byte[] b =
                        f instanceof UUID id
                                ? ByteBuffer.allocate(16)
                                        .putLong(id.getMostSignificantBits())
                                        .putLong(id.getLeastSignificantBits())
                                        .array()
                                : f.toString().getBytes(StandardCharsets.UTF_8);
                out.writeBytes(ByteBuffer.allocate(4).putInt(b.length).array());
                out.writeBytes(b);
            }
            return MessageDigest.getInstance("SHA-256").digest(out.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 从固定摘要的前十六字节构造稳定 UUID，保证同一人工恢复意图跨重试使用相同审计标识。
     */
    private static UUID uuid(byte[] d) {
        var b = ByteBuffer.wrap(Arrays.copyOf(d, 16));
        return new UUID(b.getLong(), b.getLong());
    }
}
