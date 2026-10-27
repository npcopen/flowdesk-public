package com.flowdesk.workflow.model.recovery;

import com.flowdesk.workflow.dto.WorkflowRecoveryView;
import java.time.Instant;
import java.util.UUID;
import java.security.MessageDigest;
import com.flowdesk.workflow.start.AcceptedWorkflowStart;

/**
 * 工作流恢复共享的事实模型，组合来源快照、状态版本和已提交重放身份，供查询、接纳及后台结算共用。
 */
public final class WorkflowRecoveryModels {
    /**
     * 禁止直接实例化工具容器，当前类型通过静态方法提供共享规则和转换。
     */
    private WorkflowRecoveryModels() {}
    /**
     * 恢复持久化事实，提供来源、状态、版本及最近重放身份等信息。
     */
    public record RecoveryFact(UUID recoveryId,
                        String direction,
                        UUID sourceRecordId,
                        byte[] businessIntentKey,
                        String state,
                        int attemptCount,
                        Instant nextAttemptAt,
                        String lastErrorCode,
                        String lastErrorSummary,
                        Instant deadLetteredAt,
                        Instant recoveryRequiredAt,
                        int replayCount,
                        Instant lastReplayedAt,
                        String lastReplayedByType,
                        UUID lastReplayedById,
                        String lastReplayReason,
                        UUID lastReplayCorrelationId,
                        Instant resolvedAt,
                        long lockVersion,
                        Instant createdAt,
                        Instant updatedAt) {

        /**
         * 构造不可变快照并防御性复制可变内容，防止外部修改原数组或集合改变已接纳事实。
         */
        public RecoveryFact {
            businessIntentKey = businessIntentKey.clone();
        }

        /**
         * 返回业务意图键的防御性副本，避免调用方通过数组修改已保存事实。
         */
        @Override
        public byte[] businessIntentKey() {
            return businessIntentKey.clone();
        }
    }

    /**
     * 可供重放的原始来源快照，保留冻结契约、消息身份、原始字节及摘要。
     */
    public record ReplaySource(UUID tenantId,
                        UUID inboxId,
                        String producerService,
                        UUID messageId,
                        byte[] businessIntentKey,
                        String contractRef,
                        int contractVersion,
                        byte[] bodyBytes,
                        byte[] bodySha256,
                        UUID correlationId,
                        UUID causationId,
                        Instant occurredAt,
                        String status,
                        long lockVersion) {

        /**
         * 构造不可变快照并防御性复制可变内容，防止外部修改原数组或集合改变已接纳事实。
         */
        public ReplaySource {
            businessIntentKey = businessIntentKey.clone();
            bodyBytes = bodyBytes.clone();
            bodySha256 = bodySha256.clone();
        }

        /**
         * 返回业务意图键的防御性副本，避免调用方通过数组修改已保存事实。
         */
        @Override
        public byte[] businessIntentKey() {
            return businessIntentKey.clone();
        }

        /**
         * 返回原始消息字节的防御性副本，避免调用方通过数组修改已保存事实。
         */
        @Override
        public byte[] bodyBytes() {
            return bodyBytes.clone();
        }

        /**
         * 返回消息体摘要的防御性副本，避免调用方通过数组修改已保存事实。
         */
        @Override
        public byte[] bodySha256() {
            return bodySha256.clone();
        }
    }

    /**
     * 工作流不可变数据快照，组合声明字段供当前业务处理阶段传递完整事实。
     */
    public record OutboxReplaySource(UUID outboxId, String contractRef, int contractVersion,
                              UUID correlationId, String status, long lockVersion) {
    }

    /**
     * 工作流不可变数据快照，组合声明字段供当前业务处理阶段传递完整事实。
     */
    public record AuditAnchor(UUID auditId,
                       String actionCode,
                       String targetType,
                       UUID targetId,
                       String outcome,
                       String actorType,
                       UUID actorId,
                       String reason,
                       String errorCode,
                       UUID correlationId,
                       UUID causationId) {
    }
    /**
     * 人工恢复事务的接纳结果，关联恢复视图与需要异步执行的已提交工作项。
     */
    public record Acceptance(WorkflowRecoveryView recovery,
                      boolean replayed,
                      ReplayWork work) {
    }

    /**
     * 已持久化接纳的重放工作项，携带租户、原来源及重放审计身份供后台续跑。
     */
    public record ReplayWork(UUID tenantId,
                      UUID recoveryId,
                      UUID sourceRecordId,
                      UUID actorId,
                      String reason,
                      UUID attemptCorrelationId,
                      UUID sourceCorrelationId,
                      Instant leaseUntil) {
    }
}
