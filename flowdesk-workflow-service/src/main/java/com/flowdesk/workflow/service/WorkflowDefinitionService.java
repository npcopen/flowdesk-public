package com.flowdesk.workflow.service;

import com.flowdesk.common.authz.PermissionCode;
import com.flowdesk.common.web.ApiException;
import com.flowdesk.common.web.FieldViolation;
import com.flowdesk.workflow.api.WorkflowDtos;
import com.flowdesk.workflow.repository.WorkflowRepository;
import com.flowdesk.workflow.repository.WorkflowRepository.AuditRow;
import com.flowdesk.workflow.repository.WorkflowRepository.DefinitionRow;
import com.flowdesk.workflow.repository.WorkflowRepository.DraftRow;
import com.flowdesk.workflow.repository.WorkflowRepository.VersionRow;
import com.flowdesk.workflow.start.WorkflowApproverEligibilityChecker;
import com.flowdesk.workflow.start.WorkflowIamUnavailableException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** M6-A definition, restricted draft validation and immutable publication use cases. */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class WorkflowDefinitionService {

    private static final Set<String> DEFINITION_SORTS =
            Set.of("definitionName", "definitionCode");
    private static final Set<String> VERSION_SORTS =
            Set.of("-versionNo", "versionNo", "-publishedAt");

    private final WorkflowRepository repository;
    private final WorkflowApproverEligibilityChecker eligibilityChecker;
    private final WorkflowCursorTokenService cursorTokens;
    private final TransactionTemplate transactions;

    public WorkflowDefinitionService(WorkflowRepository repository,
                                     WorkflowApproverEligibilityChecker eligibilityChecker,
                                     WorkflowCursorTokenService cursorTokens,
                                     PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.eligibilityChecker = eligibilityChecker;
        this.cursorTokens = cursorTokens;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public WorkflowPage<WorkflowDtos.WorkflowDefinitionResponse> listDefinitions(
            int pageSize, String pageToken, String sort) {
        WorkflowSupport.require(PermissionCode.WORKFLOW_READ_TENANT);
        var context = WorkflowSupport.context();
        String normalizedSort = normalize(sort, "definitionName", DEFINITION_SORTS);
        String scope = "workflow-definitions";
        WorkflowCursorTokenService.Cursor cursor = cursorTokens.decode(
                pageToken, context.tenantId(), scope, pageSize, normalizedSort);
        List<DefinitionRow> rows = repository.findDefinitions(context.tenantId(),
                normalizedSort, true, cursor == null ? null : cursor.lastValue(),
                cursor == null ? null : cursor.lastId(), pageSize);
        boolean more = rows.size() > pageSize;
        if (more) {
            rows = rows.subList(0, pageSize);
        }
        String next = null;
        if (more && !rows.isEmpty()) {
            DefinitionRow last = rows.get(rows.size() - 1);
            String value = "definitionCode".equals(normalizedSort)
                    ? last.definitionCode() : last.definitionName();
            next = cursorTokens.encode(context.tenantId(), scope, pageSize,
                    normalizedSort, value, last.definitionId());
        }
        return new WorkflowPage<>(rows.stream().map(this::definitionResponse).toList(),
                next, normalizedSort);
    }

    public WorkflowDtos.WorkflowDefinitionResponse getDefinition(UUID definitionId) {
        WorkflowSupport.require(PermissionCode.WORKFLOW_READ_TENANT);
        return definitionResponse(requireDefinition(definitionId));
    }

    public WorkflowDtos.WorkflowDraftResponse getCurrentDraft(UUID definitionId) {
        WorkflowSupport.require(PermissionCode.WORKFLOW_READ_TENANT);
        var context = WorkflowSupport.context();
        requireDefinition(definitionId);
        DraftRow row = repository.findCurrentDraft(context.tenantId(), definitionId)
                .orElseThrow(() -> ApiException.notFound("RESOURCE_NOT_FOUND",
                        "当前可编辑 Workflow Draft 不存在。"));
        return draftResponse(row);
    }

    public WorkflowDtos.WorkflowDraftResponse getDraft(UUID draftId) {
        WorkflowSupport.require(PermissionCode.WORKFLOW_READ_TENANT);
        return draftResponse(requireDraft(draftId));
    }

    public DraftCommandResult createDraft(UUID definitionId,
                                          WorkflowDtos.WorkflowDraftWriteRequest request,
                                          String idempotencyKey, String ifMatch) {
        WorkflowSupport.require(PermissionCode.WORKFLOW_DRAFT_MANAGE);
        String key = WorkflowSupport.requireIdempotencyKey(idempotencyKey);
        WorkflowSupport.rejectIfMatchOnCreate(ifMatch);
        var context = WorkflowSupport.context();
        requireDefinition(definitionId);

        byte[] effectKey = WorkflowIdempotencyCodec.effectKey(
                context, "API-036", definitionId.toString(), key);
        UUID stableId = WorkflowIdempotencyCodec.uuidFromDigest(effectKey);
        String digest = draftRequestDigest(definitionId, request);
        Optional<DraftCommandResult> replay = replayDraftCreate(stableId, digest);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (repository.findCurrentDraft(context.tenantId(), definitionId).isPresent()) {
            throw ApiException.conflict("WORKFLOW_DRAFT_ALREADY_EXISTS",
                    "该 Workflow Definition 已有可编辑 Draft。");
        }

        requireEligible(request.level1ApproverUserId());
        requireEligible(request.level2ApproverUserId());
        requireDistinctApprovers(request.level1ApproverUserId(), request.level2ApproverUserId());
        Instant now = now();
        try {
            DraftRow created = transactions.execute(status -> {
                Optional<DraftCommandResult> lockedReplay = replayDraftCreate(stableId, digest);
                if (lockedReplay.isPresent()) {
                    return requireDraft(stableId);
                }
                repository.findDefinitionForUpdate(context.tenantId(), definitionId)
                        .orElseThrow(() -> ApiException.notFound("RESOURCE_NOT_FOUND",
                                "Workflow Definition 不存在。"));
                if (repository.findCurrentDraft(context.tenantId(), definitionId).isPresent()) {
                    throw ApiException.conflict("WORKFLOW_DRAFT_ALREADY_EXISTS",
                            "该 Workflow Definition 已有可编辑 Draft。");
                }
                WorkflowDtos.ConditionWrite condition = request.condition();
                repository.insertDraft(context.tenantId(), stableId, definitionId,
                        condition.fieldCode(), condition.operator(), condition.valueType(),
                        condition.value(), request.level1ApproverUserId(),
                        request.level2ApproverUserId(), WorkflowSupport.actorId(), now);
                repository.insertAudit(context.tenantId(), stableId,
                        "WORKFLOW_DRAFT_CREATE", "WORKFLOW_DRAFT", stableId,
                        "SUCCEEDED", WorkflowSupport.actorId(), digest, null,
                        context.correlationId(), null, now);
                return requireDraft(stableId);
            });
            return new DraftCommandResult(draftResponse(created), false);
        }
        catch (DuplicateKeyException duplicate) {
            return replayDraftCreate(stableId, digest).orElseThrow(() ->
                    ApiException.conflict("WORKFLOW_DRAFT_ALREADY_EXISTS",
                            "该 Workflow Definition 已有可编辑 Draft。"));
        }
    }

    public WorkflowDtos.WorkflowDraftResponse updateDraft(
            UUID draftId, WorkflowDtos.WorkflowDraftWriteRequest request, String ifMatch) {
        WorkflowSupport.require(PermissionCode.WORKFLOW_DRAFT_MANAGE);
        long expectedVersion = WorkflowSupport.parseIfMatch(ifMatch);
        var context = WorkflowSupport.context();
        DraftRow visible = requireDraft(draftId);
        requireEligible(request.level1ApproverUserId());
        requireEligible(request.level2ApproverUserId());
        requireDistinctApprovers(request.level1ApproverUserId(), request.level2ApproverUserId());
        Instant now = now();
        DraftRow updated = transactions.execute(status -> {
            DraftRow current = repository.findDraftForUpdate(context.tenantId(), draftId)
                    .orElseThrow(() -> ApiException.notFound("RESOURCE_NOT_FOUND",
                            "Workflow Draft 不存在。"));
            requireEditable(current);
            if (current.lockVersion() != expectedVersion) {
                throw WorkflowSupport.versionConflict(current.lockVersion());
            }
            WorkflowDtos.ConditionWrite condition = request.condition();
            int changed = repository.updateDraft(context.tenantId(), draftId, expectedVersion,
                    condition.fieldCode(), condition.operator(), condition.valueType(),
                    condition.value(), request.level1ApproverUserId(),
                    request.level2ApproverUserId(), WorkflowSupport.actorId(), now);
            if (changed != 1) {
                throw WorkflowSupport.versionConflict(requireDraft(draftId).lockVersion());
            }
            repository.insertAudit(context.tenantId(), UUID.randomUUID(),
                    "WORKFLOW_DRAFT_UPDATE", "WORKFLOW_DRAFT", draftId,
                    "SUCCEEDED", WorkflowSupport.actorId(), null, null,
                    context.correlationId(), null, now);
            return requireDraft(draftId);
        });
        if (!visible.definitionId().equals(updated.definitionId())) {
            throw new IllegalStateException("Draft identity changed");
        }
        return draftResponse(updated);
    }

    public WorkflowDtos.WorkflowValidationResponse validateDraft(UUID draftId, String ifMatch) {
        WorkflowSupport.require(PermissionCode.WORKFLOW_DRAFT_MANAGE);
        long expectedVersion = WorkflowSupport.parseIfMatch(ifMatch);
        var context = WorkflowSupport.context();
        Instant now = now();
        return transactions.execute(status -> {
            DraftRow current = repository.findDraftForUpdate(context.tenantId(), draftId)
                    .orElseThrow(() -> ApiException.notFound("RESOURCE_NOT_FOUND",
                            "Workflow Draft 不存在。"));
            requireEditable(current);
            if (current.lockVersion() != expectedVersion) {
                throw WorkflowSupport.versionConflict(current.lockVersion());
            }
            List<FieldViolation> violations = validateRestrictedRule(current);
            if (!violations.isEmpty()) {
                repository.insertAudit(context.tenantId(), UUID.randomUUID(),
                        "WORKFLOW_DRAFT_VALIDATE", "WORKFLOW_DRAFT", draftId,
                        "REJECTED", WorkflowSupport.actorId(),
                        "Persisted restricted rule failed invariant validation.",
                        "WORKFLOW_DRAFT_INVALID", context.correlationId(), null, now);
                return new WorkflowDtos.WorkflowValidationResponse(draftId, false, null,
                        violations, null, current.lockVersion());
            }
            byte[] digest = ruleDigest(context.tenantId(), current);
            int changed = repository.markDraftValidated(context.tenantId(), draftId,
                    expectedVersion, digest, WorkflowSupport.actorId(), now);
            if (changed != 1) {
                throw WorkflowSupport.versionConflict(requireDraft(draftId).lockVersion());
            }
            repository.insertAudit(context.tenantId(), UUID.randomUUID(),
                    "WORKFLOW_DRAFT_VALIDATE", "WORKFLOW_DRAFT", draftId,
                    "SUCCEEDED", WorkflowSupport.actorId(),
                    HexFormat.of().formatHex(digest), null,
                    context.correlationId(), null, now);
            DraftRow refreshed = requireDraft(draftId);
            return new WorkflowDtos.WorkflowValidationResponse(draftId, true,
                    HexFormat.of().formatHex(digest), List.of(), refreshed.validatedAt(),
                    refreshed.lockVersion());
        });
    }

    public VersionCommandResult publishDraft(UUID draftId, String ifMatch,
                                             String idempotencyKey) {
        WorkflowSupport.require(PermissionCode.WORKFLOW_PUBLISH);
        String key = WorkflowSupport.requireIdempotencyKey(idempotencyKey);
        long expectedVersion = WorkflowSupport.parseIfMatch(ifMatch);
        var context = WorkflowSupport.context();
        String intentDigest = WorkflowIdempotencyCodec.requestDigestHex(
                WorkflowCanonicalIntent.of("draftId", draftId.toString()));
        byte[] effectKey = WorkflowIdempotencyCodec.effectKey(
                context, "API-040", draftId.toString(), key);
        UUID auditId = WorkflowIdempotencyCodec.uuidFromDigest(effectKey);
        Optional<VersionCommandResult> replay = replayPublication(draftId, auditId, intentDigest);
        if (replay.isPresent()) {
            return replay.get();
        }

        DraftRow visible = requireDraft(draftId);
        requireEditable(visible);
        requireEligible(visible.level1ApproverUserId());
        requireEligible(visible.level2ApproverUserId());
        requireDistinctApprovers(visible.level1ApproverUserId(), visible.level2ApproverUserId());
        Instant now = now();
        try {
            VersionRow version = transactions.execute(status -> {
                Optional<VersionCommandResult> lockedReplay =
                        replayPublication(draftId, auditId, intentDigest);
                if (lockedReplay.isPresent()) {
                    return repository.findVersionBySourceDraft(context.tenantId(), draftId)
                            .orElseThrow();
                }
                DraftRow current = repository.findDraftForUpdate(context.tenantId(), draftId)
                        .orElseThrow(() -> ApiException.notFound("RESOURCE_NOT_FOUND",
                                "Workflow Draft 不存在。"));
                requireEditable(current);
                if (current.lockVersion() != expectedVersion) {
                    throw WorkflowSupport.versionConflict(current.lockVersion());
                }
                byte[] computed = ruleDigest(context.tenantId(), current);
                if (!"VALID".equals(current.validationState())
                        || current.validationDigest() == null
                        || !Arrays.equals(computed, current.validationDigest())) {
                    throw ApiException.conflict("WORKFLOW_DRAFT_INVALID",
                            "Draft 未通过与当前内容匹配的校验。");
                }
                repository.findDefinitionForUpdate(context.tenantId(), current.definitionId())
                        .orElseThrow(() -> ApiException.notFound("RESOURCE_NOT_FOUND",
                                "Workflow Definition 不存在。"));
                int versionNo = repository.nextVersionNo(
                        context.tenantId(), current.definitionId());
                UUID workflowVersionId = UUID.randomUUID();
                repository.insertVersion(context.tenantId(), workflowVersionId, current,
                        versionNo, WorkflowSupport.actorId(), now);
                if (repository.consumeDraft(context.tenantId(), draftId, expectedVersion,
                        WorkflowSupport.actorId(), now) != 1) {
                    throw ApiException.conflict("WORKFLOW_PUBLICATION_CONFLICT",
                            "Workflow Draft 发布竞争失败。");
                }
                repository.insertAudit(context.tenantId(), auditId,
                        "WORKFLOW_DRAFT_PUBLISH", "WORKFLOW_VERSION", workflowVersionId,
                        "SUCCEEDED", WorkflowSupport.actorId(), intentDigest, null,
                        context.correlationId(), null, now);
                return repository.findVersion(context.tenantId(), workflowVersionId).orElseThrow();
            });
            return new VersionCommandResult(versionResponse(version), false);
        }
        catch (DuplicateKeyException duplicate) {
            return replayPublication(draftId, auditId, intentDigest).orElseThrow(() ->
                    ApiException.conflict("WORKFLOW_PUBLICATION_CONFLICT",
                            "Workflow Draft 发布竞争失败。"));
        }
    }

    public WorkflowPage<WorkflowDtos.WorkflowVersionResponse> listVersions(
            UUID definitionId, Integer versionNo, int pageSize,
            String pageToken, String sort) {
        WorkflowSupport.require(PermissionCode.WORKFLOW_READ_TENANT);
        var context = WorkflowSupport.context();
        requireDefinition(definitionId);
        String normalizedSort = normalize(sort, "-versionNo", VERSION_SORTS);
        String scope = "workflow-versions|" + definitionId + "|" + versionNo;
        WorkflowCursorTokenService.Cursor cursor = cursorTokens.decode(
                pageToken, context.tenantId(), scope, pageSize, normalizedSort);
        List<VersionRow> rows = repository.findVersions(context.tenantId(), definitionId,
                versionNo, normalizedSort, cursor == null ? null : cursor.lastValue(),
                cursor == null ? null : cursor.lastId(), pageSize);
        boolean more = rows.size() > pageSize;
        if (more) {
            rows = rows.subList(0, pageSize);
        }
        String next = null;
        if (more && !rows.isEmpty()) {
            VersionRow last = rows.get(rows.size() - 1);
            String value = normalizedSort.endsWith("publishedAt")
                    ? last.publishedAt().toString() : Integer.toString(last.versionNo());
            next = cursorTokens.encode(context.tenantId(), scope, pageSize,
                    normalizedSort, value, last.workflowVersionId());
        }
        return new WorkflowPage<>(rows.stream().map(this::versionResponse).toList(),
                next, normalizedSort);
    }

    public WorkflowDtos.WorkflowVersionResponse getVersion(UUID workflowVersionId) {
        WorkflowSupport.require(PermissionCode.WORKFLOW_READ_TENANT);
        var context = WorkflowSupport.context();
        return versionResponse(repository.findVersion(context.tenantId(), workflowVersionId)
                .orElseThrow(() -> ApiException.notFound("RESOURCE_NOT_FOUND",
                        "Workflow Version 不存在。")));
    }

    private Optional<DraftCommandResult> replayDraftCreate(UUID stableId, String digest) {
        var context = WorkflowSupport.context();
        Optional<AuditRow> audit = repository.findAudit(context.tenantId(), stableId);
        if (audit.isEmpty()) {
            if (repository.findDraft(context.tenantId(), stableId).isPresent()) {
                throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED", "幂等身份与既有 Draft 冲突。");
            }
            return Optional.empty();
        }
        AuditRow anchor = audit.get();
        if (!"WORKFLOW_DRAFT_CREATE".equals(anchor.actionCode())
                || !"WORKFLOW_DRAFT".equals(anchor.targetType())
                || !stableId.equals(anchor.targetId())
                || !WorkflowSupport.actorId().equals(anchor.actorId())
                || !digest.equals(anchor.reason())) {
            throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED",
                    "Idempotency-Key 已用于不同 Workflow Draft 意图。");
        }
        DraftRow row = repository.findDraft(context.tenantId(), stableId)
                .orElseThrow(() -> ApiException.conflict("IDEMPOTENCY_KEY_REUSED",
                        "Draft 幂等锚点不完整。"));
        return Optional.of(new DraftCommandResult(draftResponse(row), true));
    }

    private Optional<VersionCommandResult> replayPublication(
            UUID draftId, UUID auditId, String intentDigest) {
        var context = WorkflowSupport.context();
        Optional<AuditRow> audit = repository.findAudit(context.tenantId(), auditId);
        if (audit.isEmpty()) {
            return Optional.empty();
        }
        AuditRow anchor = audit.get();
        if (!"WORKFLOW_DRAFT_PUBLISH".equals(anchor.actionCode())
                || !"WORKFLOW_VERSION".equals(anchor.targetType())
                || !WorkflowSupport.actorId().equals(anchor.actorId())
                || !intentDigest.equals(anchor.reason())) {
            throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED",
                    "Idempotency-Key 已用于不同 Workflow 发布意图。");
        }
        VersionRow version = repository.findVersionBySourceDraft(context.tenantId(), draftId)
                .filter(row -> row.workflowVersionId().equals(anchor.targetId()))
                .orElseThrow(() -> ApiException.conflict("WORKFLOW_PUBLICATION_CONFLICT",
                        "Workflow 发布锚点不完整。"));
        return Optional.of(new VersionCommandResult(versionResponse(version), true));
    }

    private void requireEligible(UUID approverUserId) {
        try {
            if (!eligibilityChecker.isEligibleApprover(
                    WorkflowSupport.context().tenantId(), approverUserId)) {
                throw ApiException.unprocessable("WORKFLOW_APPROVER_NOT_ELIGIBLE",
                        "审批人不是同租户有效 Approver。", List.of());
            }
        }
        catch (WorkflowIamUnavailableException unavailable) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "IAM_ELIGIBILITY_UNAVAILABLE", "IAM 资格当前无法确定。", true);
        }
    }

    private DefinitionRow requireDefinition(UUID definitionId) {
        return repository.findDefinition(WorkflowSupport.context().tenantId(), definitionId)
                .orElseThrow(() -> ApiException.notFound("RESOURCE_NOT_FOUND",
                        "Workflow Definition 不存在。"));
    }

    private DraftRow requireDraft(UUID draftId) {
        return repository.findDraft(WorkflowSupport.context().tenantId(), draftId)
                .orElseThrow(() -> ApiException.notFound("RESOURCE_NOT_FOUND",
                        "Workflow Draft 不存在。"));
    }

    private static void requireEditable(DraftRow draft) {
        if (!"EDITABLE".equals(draft.status())) {
            throw ApiException.conflict("WORKFLOW_DRAFT_NOT_EDITABLE",
                    "Workflow Draft 已消费或不可编辑。");
        }
    }

    private static void requireDistinctApprovers(UUID first, UUID second) {
        if (Objects.equals(first, second)) {
            throw ApiException.unprocessable("APPROVERS_MUST_DIFFER", "两级审批必须由不同人员办理。",
                    List.of(new FieldViolation("/body/level2ApproverUserId", "APPROVERS_MUST_DIFFER",
                            "请选择另一位审批人。")));
        }
    }

    private static List<FieldViolation> validateRestrictedRule(DraftRow row) {
        if (Objects.equals(row.level1ApproverUserId(), row.level2ApproverUserId())) {
            return List.of(new FieldViolation("/body/level2ApproverUserId", "APPROVERS_MUST_DIFFER",
                    "两级审批必须由不同人员办理。"));
        }
        if ("SERVICE_RISK_LEVEL".equals(row.conditionFieldCode())
                && "EQUALS".equals(row.conditionOperator())
                && "STRING".equals(row.conditionValueType())
                && Set.of("NORMAL", "HIGH").contains(row.conditionValueText())
                && row.approvalLevelCount() == 2
                && row.level1ApproverUserId() != null
                && row.level2ApproverUserId() != null) {
            return List.of();
        }
        return List.of(new FieldViolation("/draft", "RESTRICTED_RULE_INVALID",
                "持久化 Draft 不满足冻结的两级受限规则。"));
    }

    private static byte[] ruleDigest(UUID tenantId, DraftRow draft) {
        return WorkflowRuleDigest.digest(tenantId, draft.definitionId(),
                draft.conditionFieldCode(), draft.conditionOperator(),
                draft.conditionValueType(), draft.conditionValueText(),
                draft.level1ApproverUserId(), draft.level2ApproverUserId());
    }

    private static String draftRequestDigest(UUID definitionId,
                                             WorkflowDtos.WorkflowDraftWriteRequest request) {
        WorkflowDtos.ConditionWrite condition = request.condition();
        return WorkflowIdempotencyCodec.requestDigestHex(WorkflowCanonicalIntent.of(
                "definitionId", definitionId.toString(),
                "fieldCode", condition.fieldCode(), "operator", condition.operator(),
                "valueType", condition.valueType(), "value", condition.value(),
                "level1ApproverUserId", request.level1ApproverUserId().toString(),
                "level2ApproverUserId", request.level2ApproverUserId().toString()));
    }

    private WorkflowDtos.WorkflowDefinitionResponse definitionResponse(DefinitionRow row) {
        return new WorkflowDtos.WorkflowDefinitionResponse(row.definitionId(),
                row.definitionCode(), row.definitionName(), row.status(), row.lockVersion(),
                row.createdAt(), row.updatedAt());
    }

    private WorkflowDtos.WorkflowDraftResponse draftResponse(DraftRow row) {
        return new WorkflowDtos.WorkflowDraftResponse(row.draftId(), row.definitionId(),
                row.status(), new WorkflowDtos.Condition(row.conditionFieldCode(),
                row.conditionOperator(), row.conditionValueType(), row.conditionValueText()),
                row.approvalLevelCount(), row.level1ApproverUserId(),
                row.level2ApproverUserId(), row.validationState(),
                row.validationDigest() == null ? null
                        : HexFormat.of().formatHex(row.validationDigest()),
                row.validatedAt(), row.consumedAt(), row.lockVersion(),
                row.createdAt(), row.updatedAt());
    }

    private WorkflowDtos.WorkflowVersionResponse versionResponse(VersionRow row) {
        return new WorkflowDtos.WorkflowVersionResponse(row.workflowVersionId(),
                row.definitionId(), row.versionNo(), row.status(),
                new WorkflowDtos.Condition(row.conditionFieldCode(), row.conditionOperator(),
                        row.conditionValueType(), row.conditionValueText()),
                row.approvalLevelCount(), row.level1ApproverUserId(),
                row.level2ApproverUserId(), HexFormat.of().formatHex(row.snapshotSha256()),
                row.publishedAt(), row.publishedByUserId());
    }

    private static String normalize(String value, String defaultValue, Set<String> allowed) {
        String normalized = value == null || value.isBlank() ? defaultValue : value;
        if (!allowed.contains(normalized)) {
            throw ApiException.badRequest("SORT_UNSUPPORTED", "不支持的排序字段。");
        }
        return normalized;
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public record DraftCommandResult(WorkflowDtos.WorkflowDraftResponse draft,
                                     boolean replayed) {
    }

    public record VersionCommandResult(WorkflowDtos.WorkflowVersionResponse version,
                                       boolean replayed) {
    }
}
