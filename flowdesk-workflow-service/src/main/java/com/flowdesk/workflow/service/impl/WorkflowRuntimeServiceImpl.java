package com.flowdesk.workflow.service.impl;

import com.flowdesk.workflow.model.persistence.*;
import com.flowdesk.workflow.service.*;
import com.flowdesk.workflow.event.WorkflowReverseEventCodec;
import com.flowdesk.workflow.event.WorkflowReverseEventContract;

import com.flowdesk.common.authz.PermissionCode;
import com.flowdesk.common.web.ApiException;
import com.flowdesk.common.web.FieldViolation;
import com.flowdesk.workflow.dto.WorkflowDtos;
import com.flowdesk.workflow.mapper.WorkflowAuditMapper;
import com.flowdesk.workflow.mapper.WorkflowRuntimeMapper;
import com.flowdesk.workflow.mapper.WorkflowResultOutboxMapper;
import com.flowdesk.workflow.model.persistence.InstanceRow;
import com.flowdesk.workflow.model.persistence.TaskRow;
import com.flowdesk.workflow.start.WorkflowApproverEligibilityChecker;
import com.flowdesk.workflow.start.WorkflowIamUnavailableException;
import com.flowdesk.workflow.notification.WorkflowNotificationIntent;
import com.flowdesk.workflow.notification.WorkflowNotificationOutboxWriter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static com.flowdesk.workflow.service.WorkflowRuntimeResponseMapper.instanceResponse;
import static com.flowdesk.workflow.service.WorkflowRuntimeResponseMapper.taskResponse;

/**
 * 固定两级审批的决定与审批人交接用例；每次状态变更及其本地副作用在同一事务内提交。
 */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class WorkflowRuntimeServiceImpl implements WorkflowRuntimeService {
    private final WorkflowRuntimeMapper repository;
    private final WorkflowAuditMapper audits;
    private final WorkflowResultOutboxMapper resultOutbox;
    private final WorkflowApproverEligibilityChecker eligibilityChecker;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final WorkflowNotificationOutboxWriter notificationWriter;

    /**
     * 注入审批运行时、审计和反向消息映射，使用资格检查器完成事务外预检；事务管理器将决定、任务推进和通知意图一并提交。
     */
    public WorkflowRuntimeServiceImpl(WorkflowRuntimeMapper repository,
                                  WorkflowAuditMapper audits,
                                  WorkflowResultOutboxMapper resultOutbox,
                                  WorkflowApproverEligibilityChecker eligibilityChecker,
                                  PlatformTransactionManager transactionManager,
                                  Clock clock,
                                  WorkflowNotificationOutboxWriter notificationWriter) {
        this.repository = repository;
        this.audits = audits;
        this.resultOutbox = resultOutbox;
        this.eligibilityChecker = eligibilityChecker;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.notificationWriter = notificationWriter;
    }

    /**
     * 事务外检查新审批人资格，再按任务和实例的锁顺序提交交接，同步更新通知与审计。
     */
    public ApproverChangeResult changeApprover(UUID instanceId, WorkflowDtos.ApproverChangeRequest request,
                                               String ifMatch, String idempotencyKey) {
        WorkflowSupport.require(PermissionCode.APPROVAL_REASSIGN_TENANT);
        WorkflowSupport.require(PermissionCode.WORKFLOW_RUNTIME_READ_TENANT);
        var context = WorkflowSupport.context();
        UUID actorId = WorkflowSupport.actorId();
        String key = WorkflowSupport.requireIdempotencyKey(idempotencyKey);
        long version = WorkflowSupport.parseIfMatch(ifMatch);
        UUID changeId = WorkflowIdempotencyCodec.uuidFromDigest(WorkflowIdempotencyCodec.effectKey(
                context, "APPROVER-CHANGE-V1", instanceId.toString(), key));
        String digest = HexFormat.of().formatHex(WorkflowIdempotencyCodec.sha256(
                (request.levelNo() + "\n" + request.approverUserId() + "\n" + request.reason())
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var replay = repository.findApproverChange(context.tenantId(), changeId);
        if (replay.isPresent()) return replayApproverChange(instanceId, digest, replay.get());
        // 远程资格检查在数据库事务前完成，避免持有任务锁等待 IAM。
        requireEligible(request.approverUserId());
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        return Objects.requireNonNull(transactions.execute(tx -> {
            // 审批决定先锁任务再锁实例，交接沿用相同顺序，降低两条路径互相等待的风险。
            var currentTask = repository.findPendingTask(context.tenantId(), instanceId).orElse(null);
            if (currentTask != null) currentTask = repository.findTask(context.tenantId(), currentTask.approvalTaskId(), true).orElse(null);
            var instance = repository.findInstance(context.tenantId(), instanceId, null, true)
                    .orElseThrow(() -> ApiException.notFound("RESOURCE_NOT_FOUND", "审批实例不可见。"));
            var lockedReplay = repository.findApproverChange(context.tenantId(), changeId);
            if (lockedReplay.isPresent()) return replayApproverChange(instanceId, digest, lockedReplay.get());
            if (instance.lockVersion() != version) throw WorkflowSupport.versionConflict(instance.lockVersion());
            if (!"RUNNING".equals(instance.status()) || instance.currentLevel() == null
                    || request.levelNo() < instance.currentLevel() || currentTask == null
                    || !"PENDING".equals(currentTask.status())) {
                throw ApiException.conflict("APPROVAL_HANDOFF_NOT_ALLOWED", "只能交接尚未完成的审批级别。");
            }
            UUID previous = repository.findEffectiveApprover(context.tenantId(), instanceId, request.levelNo()).orElseThrow();
            UUID other = repository.findEffectiveApprover(context.tenantId(), instanceId, request.levelNo() == 1 ? 2 : 1).orElseThrow();
            if (request.approverUserId().equals(other)) throw approversMustDiffer();
            if (request.approverUserId().equals(previous)) {
                throw ApiException.unprocessable("APPROVER_UNCHANGED", "请选择另一位审批人。", List.of());
            }
            repository.insertApproverChange(context.tenantId(), changeId, instanceId, request.levelNo(), previous,
                    request.approverUserId(), actorId, request.reason(), digest, now);
            repository.overrideApprover(context.tenantId(), instanceId, request.levelNo(), request.approverUserId(), now);
            // 当前级交接需同步更新待办任务和通知；后续级交接仅覆盖该级的有效审批人。
            if (request.levelNo() == instance.currentLevel()) {
                if (repository.reassignPendingTask(context.tenantId(), currentTask, request.approverUserId(), actorId, now) != 1)
                    throw WorkflowSupport.versionConflict(instance.lockVersion());
                if (notificationWriter != null) notificationWriter.write(context.tenantId(),
                        WorkflowNotificationIntent.reassigned(context.tenantId(), request.approverUserId(),
                                currentTask.approvalTaskId(), instanceId, request.levelNo(), changeId, context.correlationId(), now));
            }
            if (repository.touchInstanceForApproverChange(context.tenantId(), instanceId, version, actorId, now) != 1)
                throw WorkflowSupport.versionConflict(instance.lockVersion());
            audits.insertAudit(context.tenantId(), changeId, "WORKFLOW_APPROVER_CHANGED", "WORKFLOW_INSTANCE",
                    instanceId, "SUCCEEDED", actorId, "第 " + request.levelNo() + " 级审批交接："
                            + request.reason().substring(0, Math.min(request.reason().length(), 900)), null, context.correlationId(), null, now);
            return new ApproverChangeResult(instanceResponse(repository.findInstance(context.tenantId(), instanceId, null, false).orElseThrow()), false);
        }));
    }

    /**
     * 核验重复交接的实例和摘要一致，返回既有交接结果而不重复变更审批人。
     */
    private ApproverChangeResult replayApproverChange(UUID instanceId, String digest, ApproverChangeRow row) {
        if (!instanceId.equals(row.instanceId()) || !digest.equals(row.digest()))
            throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED", "该操作标识已经用于其他交接内容。");
        // 交接入口在查询重放记录前已要求租户级运行时读取权限。
        InstanceRow instance = repository.findInstance(WorkflowSupport.context().tenantId(),
                        instanceId, null, false)
                .orElseThrow(() -> ApiException.notFound("RESOURCE_NOT_FOUND",
                        "Workflow Instance 不存在。"));
        return new ApproverChangeResult(instanceResponse(instance), true);
    }

    /**
     * 构造两级审批人不得相同的业务错误，要求管理员选用不同办理人。
     */
    private static ApiException approversMustDiffer() {
        return ApiException.unprocessable("APPROVERS_MUST_DIFFER", "两级审批必须由不同人员办理，请联系管理员更换审批人。", List.of());
    }



    /**
     * 检查办理人、意图及版本后原子提交决定，推进实例并写入后续任务和消息副作用。
     */
    public DecisionResult decide(UUID approvalTaskId,
                                 WorkflowDtos.ApprovalDecisionRequest request,
                                 String direction, String ifMatch,
                                 String idempotencyKey) {
        WorkflowSupport.require(PermissionCode.APPROVAL_DECIDE_ASSIGNED);
        String key = WorkflowSupport.requireIdempotencyKey(idempotencyKey);
        long expectedVersion = WorkflowSupport.parseIfMatch(ifMatch);
        validateReason(direction, request.reason());
        var context = WorkflowSupport.context();
        UUID actorId = WorkflowSupport.actorId();
        String operationId = "APPROVE".equals(direction) ? "API-047" : "API-048";
        byte[] decisionIntentKey = WorkflowIdempotencyCodec.effectKey(
                context, operationId, approvalTaskId.toString(), key);

        TaskRow visible = repository.findTask(context.tenantId(), approvalTaskId, false)
                .filter(task -> actorId.equals(task.approverUserId()))
                .orElseThrow(WorkflowRuntimeServiceImpl::taskNotAssigned);
        Optional<DecisionResult> existing = existingDecision(
                visible, direction, request.reason(), actorId, decisionIntentKey);
        if (existing.isPresent()) {
            return existing.get();
        }

        UUID preflightLevel2 = null;
        if ("APPROVE".equals(direction) && visible.levelNo() == 2
                && repository.findEffectiveApprover(context.tenantId(), visible.workflowInstanceId(), 1)
                        .filter(actorId::equals).isPresent()) throw approversMustDiffer();
        if ("APPROVE".equals(direction) && visible.levelNo() == 1) {
            preflightLevel2 = repository.findLevel2Approver(
                            context.tenantId(), visible.workflowInstanceId())
                    .orElseThrow(() -> ApiException.conflict("APPROVAL_TASK_NOT_PENDING",
                            "审批实例缺少固定的二级审批人。"));
            if (actorId.equals(preflightLevel2)) throw approversMustDiffer();
            requireEligible(preflightLevel2);
        }
        UUID checkedLevel2 = preflightLevel2;
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);

        DecisionResult committed = transactions.execute(status -> {
            TaskRow task = repository.findTask(context.tenantId(), approvalTaskId, true)
                    .filter(row -> actorId.equals(row.approverUserId()))
                    .orElseThrow(WorkflowRuntimeServiceImpl::taskNotAssigned);
            Optional<DecisionResult> terminal = existingDecision(
                    task, direction, request.reason(), actorId, decisionIntentKey);
            if (terminal.isPresent()) {
                return terminal.get();
            }
            if (task.lockVersion() != expectedVersion) {
                throw WorkflowSupport.versionConflict(task.lockVersion());
            }
            InstanceRow instance = repository.findInstance(context.tenantId(),
                            task.workflowInstanceId(), null, true)
                    .orElseThrow(() -> ApiException.conflict("APPROVAL_TASK_NOT_PENDING",
                            "审批实例不存在或状态不一致。"));
            if (!"RUNNING".equals(instance.status())
                    || !Integer.valueOf(task.levelNo()).equals(instance.currentLevel())) {
                throw ApiException.conflict("APPROVAL_TASK_NOT_PENDING",
                        "Approval Task 与实例当前级别不一致。");
            }
            String targetStatus = "APPROVE".equals(direction) ? "APPROVED" : "REJECTED";
            if (repository.decideTask(context.tenantId(), approvalTaskId,
                    expectedVersion, targetStatus, direction, request.reason(),
                    decisionIntentKey, actorId, now) != 1) {
                return resolveDecisionRace(approvalTaskId, direction,
                        request.reason(), actorId, decisionIntentKey);
            }

            if ("APPROVE".equals(direction) && task.levelNo() == 1) {
                UUID currentLevel2 = repository.findLevel2Approver(
                                context.tenantId(), instance.workflowInstanceId())
                        .orElseThrow(() -> ApiException.conflict("APPROVAL_TASK_NOT_PENDING",
                                "审批实例缺少固定的二级审批人。"));
                // 加锁后再次核对二级审批人，若预检后发生交接则拒绝沿用旧资格结果。
                if (!currentLevel2.equals(checkedLevel2)) {
                    throw ApiException.conflict("APPROVAL_TASK_NOT_PENDING",
                            "发布版本的二级审批人发生不一致。");
                }
                if (repository.advanceInstanceToLevel2(context.tenantId(),
                        instance.workflowInstanceId(), instance.lockVersion(), actorId, now) != 1) {
                    throw ApiException.conflict("APPROVAL_DECISION_CONFLICT",
                            "审批实例推进竞争失败。");
                }
                UUID level2TaskId = WorkflowIdempotencyCodec.uuidFromDigest(
                        WorkflowIdempotencyCodec.derivedKey("FD-APPROVAL-TASK-V1",
                                context.tenantId(), instance.workflowInstanceId(), 2));
                repository.insertSecondLevelTask(context.tenantId(), level2TaskId,
                        instance.workflowInstanceId(), checkedLevel2, now);
                if (notificationWriter != null) {
                    notificationWriter.write(context.tenantId(), WorkflowNotificationIntent.create(
                            context.tenantId(), checkedLevel2, level2TaskId,
                            instance.workflowInstanceId(), 2, context.correlationId(),
                            instance.startMessageId() == null
                                    ? instance.workflowInstanceId() : instance.startMessageId(), now));
                }
            }
            else {
                String instanceStatus = "APPROVE".equals(direction)
                        ? "COMPLETED_PASSED" : "COMPLETED_REJECTED";
                if (repository.completeInstance(context.tenantId(),
                        instance.workflowInstanceId(), instance.lockVersion(),
                        instanceStatus, actorId, now) != 1) {
                    throw ApiException.conflict("APPROVAL_DECISION_CONFLICT",
                            "审批实例终局竞争失败。");
                }
                if (instance.startMessageId() == null) {
                    throw new IllegalStateException("Workflow Instance has no start causation message");
                }
                insertApprovalFinalizedOutbox(context.tenantId(), instance,
                        "APPROVE".equals(direction) ? "PASSED" : "REJECTED",
                        context.correlationId(), actorId, now,
                        request.reason() == null || request.reason().isBlank() ? null : request.reason());
            }
            audits.insertAudit(context.tenantId(), UUID.randomUUID(),
                    "APPROVAL_TASK_" + targetStatus, "APPROVAL_TASK", approvalTaskId,
                    "SUCCEEDED", actorId, request.reason(), null,
                    context.correlationId(), instance.startMessageId(), now);
            TaskRow refreshed = repository.findTask(context.tenantId(), approvalTaskId, false)
                    .orElseThrow();
            return new DecisionResult(taskResponse(refreshed), false);
        });
        if (committed == null) {
            throw new IllegalStateException("Approval transaction returned no result");
        }
        return committed;
    }

    /**
     * 并发决定后重读已提交任务，区分相同意图重放与相反决定冲突。
     */
    private DecisionResult resolveDecisionRace(UUID taskId, String direction, String reason,
                                               UUID actorId, byte[] decisionIntentKey) {
        TaskRow refreshed = repository.findTask(
                        WorkflowSupport.context().tenantId(), taskId, false)
                .orElseThrow(WorkflowRuntimeServiceImpl::taskNotAssigned);
        return existingDecision(refreshed, direction, reason, actorId, decisionIntentKey)
                .orElseThrow(() -> ApiException.conflict("APPROVAL_DECISION_CONFLICT",
                        "审批决定并发竞争失败。"));
    }

    // 已决定的任务只有操作者、方向和原因均一致才能复用结果，避免重复审批改变终态。
    /**
     * 核对已有决定与本次操作者、方向和原因，一致则返回幂等结果，不一致则拒绝。
     */
    private Optional<DecisionResult> existingDecision(TaskRow task, String direction,
                                                       String reason, UUID actorId,
                                                       byte[] suppliedIntentKey) {
        if ("PENDING".equals(task.status())) {
            return Optional.empty();
        }
        if (task.decisionType() == null || task.decidedByUserId() == null
                || task.decisionIntentKey() == null) {
            throw ApiException.conflict("APPROVAL_TASK_NOT_PENDING",
                    "终局 Approval Task 缺少完整决定元数据。");
        }
        if (actorId.equals(task.decidedByUserId())
                && direction.equals(task.decisionType())
                && Objects.equals(reason, task.decisionReason())) {
            return Optional.of(new DecisionResult(taskResponse(task),
                    Arrays.equals(suppliedIntentKey, task.decisionIntentKey())));
        }
        throw ApiException.conflict("APPROVAL_DECISION_CONFLICT",
                "Approval Task 已存在不同的终局决定。");
    }

    /**
     * 调用资格检查器核验目标审批人，不合格或依赖不可用时终止写入。
     */
    private void requireEligible(UUID approverUserId) {
        try {
            if (!eligibilityChecker.isEligibleApprover(
                    WorkflowSupport.context().tenantId(), approverUserId)) {
                throw ApiException.unprocessable("WORKFLOW_APPROVER_NOT_ELIGIBLE",
                        "二级审批人当前不是同租户有效 Approver。", List.of());
            }
        }
        catch (WorkflowIamUnavailableException unavailable) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "IAM_ELIGIBILITY_UNAVAILABLE", "IAM 资格当前无法确定。", true);
        }
    }

    /**
     * 按审批动作核验原因文本，拒绝缺少必要说明的审批决定。
     */
    private static void validateReason(String direction, String reason) {
        boolean invalidEmpty = reason != null && reason.isEmpty();
        boolean rejectMissing = "REJECT".equals(direction)
                && (reason == null || reason.isBlank());
        if (invalidEmpty || rejectMissing) {
            throw ApiException.unprocessable("VALIDATION_FAILED", "审批原因不满足约束。",
                    List.of(new FieldViolation("/body/reason", "INVALID",
                            "拒绝原因必须非空；同意原因若提供也必须非空。")));
        }
    }

    /**
     * 统一表达任务不可见或未分配，避免泄露无办理权限的审批事实。
     */
    private static ApiException taskNotAssigned() {
        return ApiException.notFound("APPROVAL_TASK_NOT_ASSIGNED",
                "Approval Task 不存在或不属于当前审批人。");
    }



    /**
     * 持久化审批完成反向事件，沿用稳定身份与业务事实，原子性由上层事务保证。
     */
    private void insertApprovalFinalizedOutbox(UUID tenantId, InstanceRow instance,
            String outcome, UUID correlationId, UUID actorId, Instant completedAt, String decisionReason) {

        byte[] businessIntentKey = WorkflowReverseEventCodec.approvalFinalizedIntent(
                tenantId, instance.workflowInstanceId());
        byte[] body = WorkflowReverseEventCodec.approvalFinalized(instance.ticketId(),
                instance.workflowInstanceId(), instance.startIntentKey(),
                instance.definitionId(), instance.workflowVersionId(),
                instance.workflowVersionNo(), outcome, completedAt, null, decisionReason);
        UUID outboxId = WorkflowReverseEventCodec.outboxId(tenantId,
                WorkflowReverseEventContract.ApprovalFinalized.CONTRACT_REF, businessIntentKey);
        UUID messageId = WorkflowReverseEventCodec.messageId(tenantId,
                WorkflowReverseEventContract.ApprovalFinalized.CONTRACT_REF, businessIntentKey);
        resultOutbox.insertApprovalFinalizedOutbox(new ApprovalFinalizedOutbox(
        tenantId,outboxId,messageId,WorkflowIdempotencyCodec.publicationKey(tenantId,messageId),businessIntentKey,
        instance.workflowInstanceId(),WorkflowReverseEventContract.ApprovalFinalized.CONTRACT_REF,body,
        WorkflowIdempotencyCodec.sha256(body),correlationId,instance.startMessageId(),completedAt,completedAt,actorId,completedAt,actorId));
    }
}
