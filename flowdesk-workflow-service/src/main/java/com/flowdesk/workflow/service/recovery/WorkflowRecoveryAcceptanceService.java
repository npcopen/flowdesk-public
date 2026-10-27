package com.flowdesk.workflow.service.recovery;
import com.flowdesk.common.tenant.TenantContext;
import com.flowdesk.workflow.model.recovery.WorkflowRecoveryModels.Acceptance;
import java.util.UUID;
/**
 * 人工重放事务接纳边界，原子保存重放身份及审计，区分 Inbox 与 Outbox 来源。
 */
public interface WorkflowRecoveryAcceptanceService {
 /**
  * 接纳带版本和幂等保护的人工重放，返回已提交工作项或可复用结果。
  */
 Acceptance accept(TenantContext context, UUID recoveryId, UUID actorId, String reason,
                   String idempotencyKey, long expectedVersion);
}
