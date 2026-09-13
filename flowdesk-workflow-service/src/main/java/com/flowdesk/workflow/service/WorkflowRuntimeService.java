package com.flowdesk.workflow.service;

import com.flowdesk.common.authz.PermissionCode;
import com.flowdesk.common.web.ApiException;
import com.flowdesk.common.web.FieldViolation;
import com.flowdesk.workflow.api.WorkflowDtos;
import com.flowdesk.workflow.repository.WorkflowRepository;
import com.flowdesk.workflow.repository.WorkflowRepository.InstanceRow;
import com.flowdesk.workflow.repository.WorkflowRepository.TaskRow;
import com.flowdesk.workflow.start.WorkflowApproverEligibilityChecker;
import com.flowdesk.workflow.start.WorkflowIamUnavailableException;
import com.flowdesk.workflow.notification.WorkflowNotificationIntent;
import com.flowdesk.workflow.notification.WorkflowNotificationOutboxWriter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** M6-B runtime evidence queries and fixed two-level approval decisions. */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class WorkflowRuntimeService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> INSTANCE_STATUSES =
            Set.of("RUNNING", "COMPLETED_PASSED", "COMPLETED_REJECTED");
    private static final Set<String> TASK_STATUSES =
            Set.of("PENDING", "APPROVED", "REJECTED");
    private static final Set<String> INSTANCE_SORTS = Set.of("-startedAt", "startedAt");
    private static final Set<String> TASK_SORTS =
            Set.of("-createdAt", "createdAt", "-decidedAt");

    private final WorkflowRepository repository;
    private final WorkflowApproverEligibilityChecker eligibilityChecker;
    private final WorkflowCursorTokenService cursorTokens;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final WorkflowNotificationOutboxWriter notificationWriter;

    public WorkflowRuntimeService(WorkflowRepository repository,
                                  WorkflowApproverEligibilityChecker eligibilityChecker,
                                  WorkflowCursorTokenService cursorTokens,
                                  PlatformTransactionManager transactionManager) {
        this(repository, eligibilityChecker, cursorTokens, transactionManager,
                Clock.systemUTC(), null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public WorkflowRuntimeService(WorkflowRepository repository,
                                  WorkflowApproverEligibilityChecker eligibilityChecker,
                                  WorkflowCursorTokenService cursorTokens,
                                  PlatformTransactionManager transactionManager,
                                  Clock clock,
                                  WorkflowNotificationOutboxWriter notificationWriter) {
        this.repository = repository;
        this.eligibilityChecker = eligibilityChecker;
        this.cursorTokens = cursorTokens;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.notificationWriter = notificationWriter;
    }

    public WorkflowPage<WorkflowDtos.WorkflowInstanceResponse> listInstances(
            String status, UUID ticketId, UUID workflowVersionId, int pageSize,
            String pageToken, String sort) {
        Scope scope = readScope();
        if (status != null && !INSTANCE_STATUSES.contains(status)) {
            throw ApiException.badRequest("INVALID_QUERY_PARAMETER", "Workflow Instance 状态无效。");
        }
        String normalizedSort = normalize(sort, "-startedAt", INSTANCE_SORTS);
        var context = WorkflowSupport.context();
        String tokenScope = "workflow-instances|" + scope.kind() + "|" + status
                + "|" + ticketId + "|" + workflowVersionId;
        WorkflowCursorTokenService.Cursor cursor = cursorTokens.decode(
                pageToken, context.tenantId(), tokenScope, pageSize, normalizedSort);
        List<InstanceRow> rows = repository.findInstances(context.tenantId(), status,
                ticketId, workflowVersionId, scope.approverUserId(),
                !normalizedSort.startsWith("-"),
                cursor == null ? null : Instant.parse(cursor.lastValue()),
                cursor == null ? null : cursor.lastId(), pageSize);
        boolean more = rows.size() > pageSize;
        if (more) {
            rows = rows.subList(0, pageSize);
        }
        String next = null;
        if (more && !rows.isEmpty()) {
            InstanceRow last = rows.get(rows.size() - 1);
            next = cursorTokens.encode(context.tenantId(), tokenScope, pageSize,
                    normalizedSort, last.startedAt().toString(), last.workflowInstanceId());
        }
        return new WorkflowPage<>(rows.stream().map(this::instanceResponse).toList(),
                next, normalizedSort);
    }

    public WorkflowDtos.WorkflowInstanceResponse getInstance(UUID workflowInstanceId) {
        Scope scope = readScope();
        InstanceRow row = repository.findInstance(WorkflowSupport.context().tenantId(),
                        workflowInstanceId, scope.approverUserId(), false)
                .orElseThrow(() -> ApiException.notFound("RESOURCE_NOT_FOUND",
                        "Workflow Instance 不存在。"));
        return instanceResponse(row);
    }

    public WorkflowPage<WorkflowDtos.ApprovalTaskResponse> listTasks(
            String status, Integer levelNo, UUID workflowInstanceId, int pageSize,
            String pageToken, String sort) {
        Scope scope = readScope();
        if (status != null && !TASK_STATUSES.contains(status)) {
            throw ApiException.badRequest("INVALID_QUERY_PARAMETER", "Approval Task 状态无效。");
        }
        if (levelNo != null && levelNo != 1 && levelNo != 2) {
            throw ApiException.badRequest("INVALID_QUERY_PARAMETER", "levelNo 只接受 1 或 2。");
        }
        String normalizedSort = normalize(sort, "-createdAt", TASK_SORTS);
        var context = WorkflowSupport.context();
        String tokenScope = "approval-tasks|" + scope.kind() + "|" + status
                + "|" + levelNo + "|" + workflowInstanceId;
        WorkflowCursorTokenService.Cursor cursor = cursorTokens.decode(
                pageToken, context.tenantId(), tokenScope, pageSize, normalizedSort);
        List<TaskRow> rows = repository.findTasks(context.tenantId(), status, levelNo,
                workflowInstanceId, scope.approverUserId(), normalizedSort,
                cursor == null ? null : cursor.lastValue(),
                cursor == null ? null : cursor.lastId(), pageSize);
        boolean more = rows.size() > pageSize;
        if (more) {
            rows = rows.subList(0, pageSize);
        }
        String next = null;
        if (more && !rows.isEmpty()) {
            TaskRow last = rows.get(rows.size() - 1);
            Instant sortValue = normalizedSort.endsWith("decidedAt")
                    ? (last.decidedAt() == null ? last.createdAt() : last.decidedAt())
                    : last.createdAt();
            next = cursorTokens.encode(context.tenantId(), tokenScope, pageSize,
                    normalizedSort, sortValue.toString(), last.approvalTaskId());
        }
        return new WorkflowPage<>(rows.stream().map(this::taskResponse).toList(),
                next, normalizedSort);
    }

    public java.util.Map<String, Long> taskSummary() {
        Scope scope = readScope();
        return java.util.Map.of("pendingCount", repository.countPendingTasks(
                WorkflowSupport.context().tenantId(), scope.approverUserId()));
    }

    public WorkflowDtos.ApprovalTaskResponse getTask(UUID approvalTaskId) {
        Scope scope = readScope();
        TaskRow row = repository.findTask(WorkflowSupport.context().tenantId(),
                        approvalTaskId, false)
                .filter(task -> scope.approverUserId() == null
                        || scope.approverUserId().equals(task.approverUserId()))
                .orElseThrow(() -> taskNotAssigned());
        return taskResponse(row);
    }

    public List<WorkflowDtos.ApproverChangeResponse> approverChanges(UUID instanceId) {
        getInstance(instanceId);
        return repository.findApproverChanges(WorkflowSupport.context().tenantId(), instanceId).stream()
                .map(row -> new WorkflowDtos.ApproverChangeResponse(row.changeId(), row.levelNo(), row.previous(),
                        row.next(), row.actorId(), row.reason(), row.changedAt())).toList();
    }

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
        requireEligible(request.approverUserId());
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        return Objects.requireNonNull(transactions.execute(tx -> {
            // Decisions take the pending task lock before the instance lock; handoff uses the same order.
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
            if (request.levelNo() == instance.currentLevel()) {
                if (repository.reassignPendingTask(context.tenantId(), currentTask, request.approverUserId(), actorId, now) != 1)
                    throw WorkflowSupport.versionConflict(instance.lockVersion());
                if (notificationWriter != null) notificationWriter.write(context.tenantId(),
                        WorkflowNotificationIntent.reassigned(context.tenantId(), request.approverUserId(),
                                currentTask.approvalTaskId(), instanceId, request.levelNo(), changeId, context.correlationId(), now));
            }
            if (repository.touchInstanceForApproverChange(context.tenantId(), instanceId, version, actorId, now) != 1)
                throw WorkflowSupport.versionConflict(instance.lockVersion());
            repository.insertAudit(context.tenantId(), changeId, "WORKFLOW_APPROVER_CHANGED", "WORKFLOW_INSTANCE",
                    instanceId, "SUCCEEDED", actorId, "第 " + request.levelNo() + " 级审批交接："
                            + request.reason().substring(0, Math.min(request.reason().length(), 900)), null, context.correlationId(), null, now);
            return new ApproverChangeResult(instanceResponse(repository.findInstance(context.tenantId(), instanceId, null, false).orElseThrow()), false);
        }));
    }

    private ApproverChangeResult replayApproverChange(UUID instanceId, String digest, WorkflowRepository.ApproverChangeRow row) {
        if (!instanceId.equals(row.instanceId()) || !digest.equals(row.digest()))
            throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED", "该操作标识已经用于其他交接内容。");
        return new ApproverChangeResult(getInstance(instanceId), true);
    }

    private static ApiException approversMustDiffer() {
        return ApiException.unprocessable("APPROVERS_MUST_DIFFER", "两级审批必须由不同人员办理，请联系管理员更换审批人。", List.of());
    }

    public record ApproverChangeResult(WorkflowDtos.WorkflowInstanceResponse instance, boolean replayed) { }

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
                .orElseThrow(WorkflowRuntimeService::taskNotAssigned);
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
                    .orElseThrow(WorkflowRuntimeService::taskNotAssigned);
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
                repository.insertApprovalFinalizedOutbox(context.tenantId(), instance,
                        "APPROVE".equals(direction) ? "PASSED" : "REJECTED",
                        context.correlationId(), actorId, now,
                        request.reason() == null || request.reason().isBlank() ? null : request.reason());
            }
            repository.insertAudit(context.tenantId(), UUID.randomUUID(),
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

    private DecisionResult resolveDecisionRace(UUID taskId, String direction, String reason,
                                               UUID actorId, byte[] decisionIntentKey) {
        TaskRow refreshed = repository.findTask(
                        WorkflowSupport.context().tenantId(), taskId, false)
                .orElseThrow(WorkflowRuntimeService::taskNotAssigned);
        return existingDecision(refreshed, direction, reason, actorId, decisionIntentKey)
                .orElseThrow(() -> ApiException.conflict("APPROVAL_DECISION_CONFLICT",
                        "审批决定并发竞争失败。"));
    }

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

    private Scope readScope() {
        if (WorkflowSupport.hasAny(PermissionCode.WORKFLOW_RUNTIME_READ_TENANT)) {
            WorkflowSupport.require(PermissionCode.WORKFLOW_RUNTIME_READ_TENANT);
            return new Scope("TENANT", null);
        }
        WorkflowSupport.require(PermissionCode.APPROVAL_READ_ASSIGNED);
        return new Scope("ASSIGNED", WorkflowSupport.actorId());
    }

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

    private WorkflowDtos.WorkflowInstanceResponse instanceResponse(InstanceRow row) {
        try {
            JsonNode input = JSON.readTree(row.inputSnapshotJson());
            return new WorkflowDtos.WorkflowInstanceResponse(row.workflowInstanceId(),
                    row.ticketId(), new WorkflowDtos.WorkflowVersionRef(row.definitionId(),
                    row.workflowVersionId(), row.workflowVersionNo()),
                    new WorkflowDtos.InputSnapshot(input.path("schemaVersion").stringValue(),
                            input.path("serviceRiskLevel").stringValue()),
                    HexFormat.of().formatHex(row.inputSnapshotSha256()),
                    row.conditionResult(), row.status(), row.currentLevel(), row.startedAt(),
                    row.completedAt(), row.lockVersion());
        }
        catch (Exception invalidSnapshot) {
            throw new IllegalStateException("Persisted Workflow input snapshot is invalid",
                    invalidSnapshot);
        }
    }

    private WorkflowDtos.ApprovalTaskResponse taskResponse(TaskRow row) {
        return new WorkflowDtos.ApprovalTaskResponse(row.approvalTaskId(),
                row.workflowInstanceId(), row.levelNo(), row.approverUserId(), row.status(),
                row.decisionType(), row.decisionReason(), row.decidedAt(), row.lockVersion(),
                row.createdAt(), row.updatedAt());
    }

    private static ApiException taskNotAssigned() {
        return ApiException.notFound("APPROVAL_TASK_NOT_ASSIGNED",
                "Approval Task 不存在或不属于当前审批人。");
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static String normalize(String value, String fallback, Set<String> allowed) {
        String result = value == null || value.isBlank() ? fallback : value;
        if (!allowed.contains(result)) {
            throw ApiException.badRequest("SORT_UNSUPPORTED", "不支持的排序字段。");
        }
        return result;
    }

    private record Scope(String kind, UUID approverUserId) {
    }

    public record DecisionResult(WorkflowDtos.ApprovalTaskResponse task, boolean replayed) {
    }
}
