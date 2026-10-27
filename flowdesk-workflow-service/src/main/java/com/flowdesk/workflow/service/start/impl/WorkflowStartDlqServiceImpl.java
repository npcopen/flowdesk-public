package com.flowdesk.workflow.service.start.impl;

import com.flowdesk.common.tenant.TenantContextHolder;
import com.flowdesk.common.tenant.TenantContextSource;
import com.flowdesk.workflow.service.WorkflowIdempotencyCodec;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import com.flowdesk.workflow.start.*;
import com.flowdesk.workflow.model.start.WorkflowStartModels.*;
import com.flowdesk.workflow.service.start.*;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.util.List;

/**
 * 工作流业务契约实现，协调声明的持久化与外部依赖，并在明确事务边界内维护业务事实一致性。
 */
public final class WorkflowStartDlqServiceImpl implements WorkflowStartDlqService {
    private static final String MESSAGE_CONFLICT = "MESSAGE_ID_CONTENT_CONFLICT";
    private static final String INTENT_CONFLICT = "BUSINESS_INTENT_CONTENT_CONFLICT";
    private static final String VERSION_REFERENCE_INVALID = "WORKFLOW_VERSION_REFERENCE_INVALID";
    private static final String VERSION_STATE_INVALID = "WORKFLOW_VERSION_STATE_INVALID";
    private static final String VERSION_RULE_INVALID = "WORKFLOW_VERSION_RULE_INVALID";
    private static final String INSTANCE_CONFLICT = "WORKFLOW_INSTANCE_CONTENT_CONFLICT";

    private static final String RETRIES_EXHAUSTED = "BROKER_RETRIES_EXHAUSTED";
    private static final String RECOVERY_SUMMARY =
            "Main Workflow consumer exhausted its bounded Broker deliveries.";

    private final WorkflowStartMapper mapper;
    private final TransactionTemplate transactions;

    /**
     * 注入启动持久化映射及事务管理器，使死信 Inbox、恢复来源和接纳审计原子落库后才确认消息。
     */
    public WorkflowStartDlqServiceImpl(WorkflowStartMapper mapper,
                                PlatformTransactionManager transactionManager) {
        this.mapper = mapper;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /**
     * 用本地事务接纳可信死信，保存原始事实后返回能否向代理确认的结果。
     */
    public WorkflowStartConsumeResult accept(AcceptedWorkflowStart message) {
        WorkflowStartConsumeResult result = transactions.execute(status -> acceptInside(message));
        if (result == null) {
            throw new IllegalStateException("Workflow-start DLQ transaction returned no decision");
        }
        return result;
    }

    /**
     * 检查死信重复及版本引用，可恢复消息原子登记 Inbox 和恢复，确定性错误记录拒绝。
     */
    private WorkflowStartConsumeResult acceptInside(AcceptedWorkflowStart message) {
        var context = TenantContextHolder.current();
        context.requireTenant(message.tenantId());
        if (context.source() != TenantContextSource.BROKER_MESSAGE) {
            throw new IllegalStateException("Workflow-start DLQ acceptance requires Broker context");
        }

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        DuplicateCheck duplicate =
                findDuplicate(message, true);
        if (duplicate.terminalDuplicate()) {
            return WorkflowStartConsumeResult.ack(
                    "WORKFLOW_START_DLQ_DUPLICATE", duplicate.existing().resultId());
        }
        if (duplicate.kind() == DuplicateKind.CONFLICT) {
            insertAudit(message, auditId(message, "WORKFLOW_START_DLQ_CONFLICT",
                            "INBOX_MESSAGE", duplicate.existing().inboxId(),
                            duplicate.conflictCode()),
                    "WORKFLOW_START_DLQ_CONFLICT", "INBOX_MESSAGE",
                    duplicate.existing().inboxId(), "REJECTED",
                    "DLQ identity was reused with different content.",
                    duplicate.conflictCode(), now);
            return WorkflowStartConsumeResult.ack(duplicate.conflictCode(), null);
        }
        if (duplicate.recoverableDuplicate()) {
            RecoveryRecord recovery = findInboxRecovery(
                    message.tenantId(), duplicate.existing().inboxId(), true);
            if (recovery == null) {
                throw new ConcurrencyFailureException(
                        "RECOVERY_REQUIRED Inbox is missing its atomic Recovery fact");
            }
            return WorkflowStartConsumeResult.ack(
                    "WORKFLOW_START_DLQ_RECOVERY_DUPLICATE", null);
        }

        VersionLookup version = findVersion(message);
        if (!version.valid()) {
            UUID inboxId = inboxId(message);
            insertRejectedInbox(message, inboxId, version.errorCode(),
                    WorkflowStartContract.MAIN_DELIVERY_ATTEMPTS, now);
            insertAudit(message, auditId(message, "WORKFLOW_START_DLQ_REJECTED",
                            "INBOX_MESSAGE", inboxId, version.errorCode()),
                    "WORKFLOW_START_DLQ_REJECTED", "INBOX_MESSAGE", inboxId,
                    "REJECTED", "DLQ message failed deterministic reference validation.",
                    version.errorCode(), now);
            return WorkflowStartConsumeResult.ack(version.errorCode(), null);
        }

        UUID inboxId = inboxId(message);
        UUID recoveryId = recoveryId(message, inboxId);
        insertRecoveryRequiredInbox(message, inboxId, RETRIES_EXHAUSTED, now);
        insertInboxRecovery(message, inboxId, recoveryId, RETRIES_EXHAUSTED,
                RECOVERY_SUMMARY, now);
        insertAudit(message, auditId(message, "WORKFLOW_START_DLQ_ACCEPTED",
                        "MESSAGE_RECOVERY", recoveryId, RETRIES_EXHAUSTED),
                "WORKFLOW_START_DLQ_ACCEPTED", "MESSAGE_RECOVERY", recoveryId,
                "FAILED", RECOVERY_SUMMARY, RETRIES_EXHAUSTED, now);
        return WorkflowStartConsumeResult.ack("WORKFLOW_START_RECOVERY_REQUIRED", null);
    }

    /**
     * 根据原消息身份派生稳定 Inbox 标识，使重复投递沿用同一接纳事实。
     */
    private static UUID inboxId(AcceptedWorkflowStart message) {
        return stableId("FD-WORKFLOW-START-INBOX-V1", message.tenantId(),
                message.messageId());
    }

    /**
     * 根据原消息业务身份派生恢复记录标识，使同一来源仅有一条恢复生命周期。
     */
    private static UUID recoveryId(AcceptedWorkflowStart message, UUID inboxId) {
        return stableId("FD-WORKFLOW-START-RECOVERY-V1", message.tenantId(), inboxId);
    }

    /**
     * 根据命名域和业务身份派生审计 UUID，使重复处理沿用同一审计锚点。
     */
    private static UUID auditId(AcceptedWorkflowStart message, String action,
                                String targetType, UUID targetId, String errorCode) {
        return stableId("FD-WORKFLOW-START-AUDIT-V1", message.tenantId(),
                message.messageId(), action, targetType,
                targetId == null ? new UUID(0L, 0L) : targetId,
                errorCode == null ? "" : errorCode);
    }

    /**
     * 按固定命名域和业务组成部分派生稳定标识，避免重试产生重复副作用。
     */
    private static UUID stableId(String namespace, Object... fields) {
        return WorkflowIdempotencyCodec.uuidFromDigest(
                WorkflowIdempotencyCodec.derivedKey(namespace, fields));
    }
    /**
     * 按声明的租户与业务条件查询消息重复事实，供调用方核对可见性、状态或幂等关系。
     */
    private DuplicateCheck findDuplicate(AcceptedWorkflowStart message, boolean lock) {
        List<InboxRecord> byMessage = mapper.findInboxByMessage(uuid(message.tenantId()), message.producerService(), uuid(message.messageId()),
                lock);
        if (!byMessage.isEmpty()) {
            InboxRecord existing = byMessage.get(0);
            return sameContent(existing, message)
                    ? DuplicateCheck.sameMessage(existing)
                    : DuplicateCheck.conflict(MESSAGE_CONFLICT, existing);
        }

        List<InboxRecord> byIntent = mapper.findInboxByIntent(uuid(message.tenantId()), message.producerService(), message.contractRef(),
                message.businessIntentKey(), lock);
        if (!byIntent.isEmpty()) {
            InboxRecord existing = byIntent.get(0);
            return sameContent(existing, message)
                    ? DuplicateCheck.sameIntent(existing)
                    : DuplicateCheck.conflict(INTENT_CONFLICT, existing);
        }
        return DuplicateCheck.fresh();
    }
    /**
     * 按声明的租户与业务条件查询流程版本，供调用方核对可见性、状态或幂等关系。
     */
    private VersionLookup findVersion(AcceptedWorkflowStart message) {
        List<VersionSnapshot> rows = mapper.findVersion(uuid(message.tenantId()), uuid(message.workflowVersionId()),
                uuid(message.workflowDefinitionId()), message.versionNo());
        if (rows.isEmpty()) {
            return VersionLookup.invalid(VERSION_REFERENCE_INVALID);
        }
        VersionSnapshot version = rows.get(0);
        if (!"PUBLISHED".equals(version.versionStatus())
                || !"ACTIVE".equals(version.definitionStatus())) {
            return VersionLookup.invalid(VERSION_STATE_INVALID);
        }
        if (!"SERVICE_RISK_LEVEL".equals(version.conditionFieldCode())
                || !"EQUALS".equals(version.conditionOperator())
                || !"STRING".equals(version.conditionValueType())
                || !("NORMAL".equals(version.conditionValueText())
                || "HIGH".equals(version.conditionValueText()))
                || version.approvalLevelCount() != 2
                || version.snapshotSha256().length != 32) {
            return VersionLookup.invalid(VERSION_RULE_INVALID);
        }
        return VersionLookup.valid(version);
    }
    /**
     * 比较契约、意图键与内容摘要，区分安全重复投递和身份内容冲突。
     */
    private static boolean sameContent(InboxRecord existing, AcceptedWorkflowStart incoming) {
        return existing.contractRef().equals(incoming.contractRef())
                && existing.contractVersion() == incoming.contractVersion()
                && MessageDigest.isEqual(existing.businessIntentKey(), incoming.businessIntentKey())
                && MessageDigest.isEqual(existing.bodySha256(), incoming.bodySha256());
    }
    /**
     * 持久化确定性拒绝 Inbox，沿用稳定身份与业务事实，原子性由上层事务保证。
     */
    private void insertRejectedInbox(AcceptedWorkflowStart message,
                              UUID inboxId,
                              String errorCode,
                              Instant now) {
        insertRejectedInbox(message, inboxId, errorCode, 1, now);
    }
    /**
     * 持久化待恢复 Inbox，沿用稳定身份与业务事实，原子性由上层事务保证。
     */
    private void insertRecoveryRequiredInbox(AcceptedWorkflowStart message,
                                     UUID inboxId,
                                     String errorCode,
                                     Instant now) {
        mapper.insertRecoveryRequiredInbox(uuid(message.tenantId()), uuid(inboxId), message.producerService(),
                uuid(message.messageId()), message.businessIntentKey(), message.contractRef(),
                message.contractVersion(), message.bodyBytes(), message.bodySha256(),
                uuid(message.correlationId()), nullableUuid(message.causationId()),
                Timestamp.from(message.occurredAt()), WorkflowStartContract.MAIN_DELIVERY_ATTEMPTS,
                errorCode, Timestamp.from(now));
    }
    /**
     * 按声明的租户与业务条件查询Inbox 配对恢复记录，供调用方核对可见性、状态或幂等关系。
     */
    private RecoveryRecord findInboxRecovery(UUID tenantId, UUID inboxId, boolean lock) {
        List<RecoveryRecord> rows = mapper.findInboxRecovery(uuid(tenantId), uuid(inboxId), lock);
        return rows.isEmpty() ? null : rows.get(0);
    }
    /**
     * 持久化Inbox 配对恢复记录，沿用稳定身份与业务事实，原子性由上层事务保证。
     */
    private void insertInboxRecovery(AcceptedWorkflowStart message,
                             UUID inboxId,
                             UUID recoveryId,
                             String errorCode,
                             String errorSummary,
                             Instant now) {
        mapper.insertInboxRecovery(uuid(message.tenantId()), uuid(recoveryId), uuid(inboxId),
                message.businessIntentKey(), WorkflowStartContract.MAIN_DELIVERY_ATTEMPTS, errorCode,
                errorSummary, Timestamp.from(now));
    }
    /**
     * 持久化审计记录，沿用稳定身份与业务事实，原子性由上层事务保证。
     */
    private void insertAudit(AcceptedWorkflowStart message,
                     UUID auditId,
                     String actionCode,
                     String targetType,
                     UUID targetId,
                     String outcome,
                     String reason,
                     String errorCode,
                     Instant now) {
        mapper.insertAudit(uuid(message.tenantId()), uuid(auditId), actionCode, targetType, nullableUuid(targetId),
                outcome, reason, errorCode, uuid(message.correlationId()),
                nullableUuid(message.causationId()), Timestamp.from(now));
    }
    /**
     * 在 UUID 与持久化表示之间转换，保持标识高低位的编码顺序一致。
     */
    private static UUID uuid(byte[] value) {
        ByteBuffer buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
    /**
     * 转换可空 UUID，空值保持为空以适配可选数据库字段。
     */
    private static UUID nullableUuid(byte[] value) {
        return value == null ? null : uuid(value);
    }
    /**
     * 持久化确定性拒绝 Inbox，沿用稳定身份与业务事实，原子性由上层事务保证。
     */
    private void insertRejectedInbox(AcceptedWorkflowStart message,
                              UUID inboxId,
                              String errorCode,
                              int attemptCount,
                              Instant now) {
        mapper.insertRejectedInbox(uuid(message.tenantId()), uuid(inboxId), message.producerService(),
                uuid(message.messageId()), message.businessIntentKey(), message.contractRef(),
                message.contractVersion(), message.bodyBytes(), message.bodySha256(),
                uuid(message.correlationId()), nullableUuid(message.causationId()),
                Timestamp.from(message.occurredAt()), attemptCount, errorCode, Timestamp.from(now));
    }
    /**
     * 在 UUID 与持久化表示之间转换，保持标识高低位的编码顺序一致。
     */
    private static byte[] uuid(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits()).array();
    }
    /**
     * 转换可空 UUID，空值保持为空以适配可选数据库字段。
     */
    private static byte[] nullableUuid(UUID value) {
        return value == null ? null : uuid(value);
    }
}
