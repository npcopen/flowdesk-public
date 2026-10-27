package com.flowdesk.ticket.recovery.impl;

import com.flowdesk.common.tenant.ActorRef;
import com.flowdesk.common.tenant.TenantContext;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.UUID;

/** 派生人工重放请求及结算审计的稳定身份，区分请求幂等与每次执行尝试的结果。 */
final class TicketRecoveryIdempotency {
    /**
     * 私有构造方法，阻止工具类实例化，调用方应使用静态入口。
     */
    private TicketRecoveryIdempotency() { }
    /**
     * 按来源和审计用途派生稳定标识，避免重复记录同一业务效果。
     */
    static UUID auditId(TenantContext context, UUID recoveryId, String key) {
        if (!(context.actor() instanceof ActorRef.Human human)) throw new IllegalStateException();
        return uuid(digest("FD-IDEMPOTENCY-V1", context.tenantId(), "USER", human.userId(),
                "API-060", "TICKET", recoveryId, key));
    }
    /**
     * 计算规范请求摘要，用于判别幂等键是否对应同一业务意图。
     */
    static String requestDigest(UUID recoveryId, String reason) {
        return HexFormat.of().formatHex(digest(
                "FD-TICKET-RECOVERY-INTENT-V1", recoveryId, reason));
    }
    /**
     * 按来源及投递结果派生稳定审计标识。
     */
    static UUID outcomeAuditId(UUID tenantId, UUID attemptCorrelation, String outcome) {
        return uuid(digest("FD-TICKET-RECOVERY-OUTCOME-V1",
                tenantId, attemptCorrelation, outcome));
    }
    /**
     * 计算恢复操作规范请求摘要，供幂等键复用时比较业务意图。
     */
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
    /**
     * 转换业务标识与规范 UUID 字节表示，遵循既有空值与格式校验约定。
     */
    private static UUID uuid(byte[] digest) {
        ByteBuffer b = ByteBuffer.wrap(Arrays.copyOf(digest, 16));
        return new UUID(b.getLong(), b.getLong());
    }
}
