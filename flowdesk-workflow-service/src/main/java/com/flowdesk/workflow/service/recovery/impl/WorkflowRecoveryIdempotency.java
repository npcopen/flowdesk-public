package com.flowdesk.workflow.service.recovery.impl;

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
 * 为人工重放请求和每次尝试的结果审计派生稳定标识，避免重复请求生成重复记录。
 */
final class WorkflowRecoveryIdempotency {

    /**
     * 禁止直接实例化工具容器，相关静态规则与转换由调用方按业务需要复用。
     */
    private WorkflowRecoveryIdempotency() {
    }

    /**
     * 按恢复身份和幂等键派生人工重放请求的稳定审计标识。
     */
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

    /**
     * 根据已提交重放请求派生结果审计标识，避免续跑写入重复结果历史。
     */
    static UUID outcomeAuditId(UUID tenantId, UUID attemptCorrelationId, String outcome) {
        return uuid(sha256(frame(
                "FD-WORKFLOW-RECOVERY-OUTCOME-V1",
                tenantId,
                attemptCorrelationId,
                outcome)));
    }

    /**
     * 计算规范化请求摘要，判断相同幂等键是否被用于不同业务意图。
     */
    static String requestDigest(UUID recoveryId, String reason) {
        return HexFormat.of().formatHex(sha256(frame(
                "FD-WORKFLOW-RECOVERY-INTENT-V1", recoveryId, reason)));
    }

    /**
     * 为摘要字段写入长度边界，避免不同字段组合产生拼接歧义。
     */
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

    /**
     * 计算输入字节的 SHA-256 摘要，用于内容一致性核对及稳定业务键派生。
     */
    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        }
        catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /**
     * 在 UUID 与持久化表示之间转换，保持标识高低位的编码顺序一致。
     */
    private static UUID uuid(byte[] digest) {
        ByteBuffer buffer = ByteBuffer.wrap(Arrays.copyOf(digest, 16));
        return new UUID(buffer.getLong(), buffer.getLong());
    }
    /**
     * 在 UUID 与持久化表示之间转换，保持标识高低位的编码顺序一致。
     */
    private static byte[] uuid(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits()).array();
    }
}
