package com.flowdesk.ticket.reverse;

import com.flowdesk.ticket.model.reverse.*;
import com.flowdesk.ticket.persistence.Uuids;
import com.flowdesk.common.tenant.TenantContextHolder;
import com.flowdesk.ticket.service.IdempotencyCodec;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** 不可变业务身份的确定性生成规则。 */
public final class WorkflowReverseIdentity {
    /**
     * 私有构造方法，阻止工具类实例化，调用方应使用静态入口。
     */
    private WorkflowReverseIdentity() { }

    /**
     * 按业务来源、时钟类型与周期派生稳定 SLA 时钟标识。
     */
    public static UUID slaClockId(UUID tenantId, UUID ticketId, String clockType) {
        if (tenantId == null || ticketId == null
                || !("FIRST_RESPONSE".equals(clockType) || "RESOLUTION".equals(clockType))) {
            throw new IllegalArgumentException("Workflow reverse SLA clock identity is invalid");
        }
        return IdempotencyCodec.uuidFromDigest(IdempotencyCodec.derivedKey(
                "FD-WORKFLOW-SLA-CLOCK-V1", tenantId, ticketId, clockType));
    }

    /**
     * 为工作流终态落地派生稳定效果键，供时间线与审计去重。
     */
    public static byte[] finalizationEffectKey(AcceptedWorkflowReverse message) {
        if ("PASSED".equals(message.outcome()) && Boolean.FALSE.equals(message.conditionResult())) {
            return IdempotencyCodec.derivedKey("FD-WORKFLOW-APPROVAL-FINALIZED-V1",
                    message.tenantId(), message.ticketId(), message.workflowInstanceId(),
                    message.outcome(), "AUTO_PASS");
        }
        return IdempotencyCodec.derivedKey("FD-WORKFLOW-APPROVAL-FINALIZED-V1",
                message.tenantId(), message.ticketId(), message.workflowInstanceId(),
                message.outcome());
    }

    /**
     * 按来源和审计用途派生稳定标识，避免重复记录同一业务效果。
     */
    public static UUID auditId(AcceptedWorkflowReverse message, String action,
                               UUID targetId, String outcome, String reason, String error) {
        return IdempotencyCodec.uuidFromDigest(IdempotencyCodec.derivedKey(
                "FD-WORKFLOW-REVERSE-AUDIT-V1", message.tenantId(), message.messageId(),
                action, targetId == null ? new UUID(0L, 0L) : targetId, outcome));
    }
}
