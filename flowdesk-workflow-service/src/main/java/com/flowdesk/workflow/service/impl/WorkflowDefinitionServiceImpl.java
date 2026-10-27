package com.flowdesk.workflow.service.impl;

import com.flowdesk.workflow.model.persistence.*;
import com.flowdesk.workflow.service.*;

import com.flowdesk.common.authz.PermissionCode;
import com.flowdesk.common.web.ApiException;
import com.flowdesk.common.web.FieldViolation;
import com.flowdesk.workflow.dto.WorkflowDtos;
import com.flowdesk.workflow.mapper.WorkflowDefinitionMapper;
import com.flowdesk.workflow.mapper.WorkflowAuditMapper;
import com.flowdesk.workflow.model.persistence.AuditRow;
import com.flowdesk.workflow.model.persistence.DefinitionRow;
import com.flowdesk.workflow.model.persistence.DraftRow;
import com.flowdesk.workflow.model.persistence.VersionRow;
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

/**
 * 流程定义与受限两级审批规则的草稿管理、校验和不可变版本发布用例。
 */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class WorkflowDefinitionServiceImpl implements WorkflowDefinitionService {

    private static final Set<String> DEFINITION_SORTS =
            Set.of("definitionName", "definitionCode");
    private static final Set<String> VERSION_SORTS =
            Set.of("-versionNo", "versionNo", "-publishedAt");

    private final WorkflowDefinitionMapper repository;
    private final WorkflowAuditMapper audits;
    private final WorkflowApproverEligibilityChecker eligibilityChecker;
    private final WorkflowCursorTokenService cursorTokens;
    private final TransactionTemplate transactions;

    /**
     * 注入定义与审计映射、审批资格检查器和游标服务，草稿创建及版本发布由同一事务管理器维护原子性。
     */
    public WorkflowDefinitionServiceImpl(WorkflowDefinitionMapper repository,
                                     WorkflowAuditMapper audits,
                                     WorkflowApproverEligibilityChecker eligibilityChecker,
                                     WorkflowCursorTokenService cursorTokens,
                                     PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.audits = audits;
        this.eligibilityChecker = eligibilityChecker;
        this.cursorTokens = cursorTokens;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /**
     * 分页查询当前租户可见的流程定义，按排序及游标生成稳定结果。
     */
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

    /**
     * 读取当前租户的流程定义详情；实现先核验 WORKFLOW_READ_TENANT 权限，定义不存在或不可见时返回统一的 404 资源错误。
     */
    public WorkflowDtos.WorkflowDefinitionResponse getDefinition(UUID definitionId) {
        WorkflowSupport.require(PermissionCode.WORKFLOW_READ_TENANT);
        return definitionResponse(requireDefinition(definitionId));
    }

    /**
     * 按声明的租户与业务条件查询当前草稿，供调用方核对可见性、状态或幂等关系。
     */
    public WorkflowDtos.WorkflowDraftResponse getCurrentDraft(UUID definitionId) {
        WorkflowSupport.require(PermissionCode.WORKFLOW_READ_TENANT);
        var context = WorkflowSupport.context();
        requireDefinition(definitionId);
        DraftRow row = repository.findCurrentDraft(context.tenantId(), definitionId)
                .orElseThrow(() -> ApiException.notFound("RESOURCE_NOT_FOUND",
                        "当前可编辑 Workflow Draft 不存在。"));
        return draftResponse(row);
    }

    /**
     * 按声明的租户与业务条件查询草稿，供调用方核对可见性、状态或幂等关系。
     */
    public WorkflowDtos.WorkflowDraftResponse getDraft(UUID draftId) {
        WorkflowSupport.require(PermissionCode.WORKFLOW_READ_TENANT);
        return draftResponse(requireDraft(draftId));
    }

    /**
     * 检查定义和版本预条件后创建草稿，以持久化锚点保障同一幂等请求可安全重放。
     */
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
                audits.insertAudit(context.tenantId(), stableId,
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

    /**
     * 锁定可编辑草稿并核对版本，保存新规则时清除原校验状态及摘要。
     */
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
            audits.insertAudit(context.tenantId(), UUID.randomUUID(),
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

    /**
     * 验证规则结构与审批资格，条件更新成功后保存匹配当前规则的校验结果。
     */
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
                audits.insertAudit(context.tenantId(), UUID.randomUUID(),
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
            audits.insertAudit(context.tenantId(), UUID.randomUUID(),
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

    /**
     * 核对已验证草稿和版本，在事务中分配版本并冻结规则快照，重复发布复用原结果。
     */
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
                // 发布时重新计算规则摘要，确保校验通过的是当前草稿内容，而非修改前的旧规则。
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
                // 持有定义行锁后分配版本号，使同一流程的并发发布保持递增且不重复。
                int versionNo = repository.nextVersionNo(
                        context.tenantId(), current.definitionId());
                UUID workflowVersionId = UUID.randomUUID();
                repository.insertVersion(context.tenantId(), workflowVersionId, current,
                        versionNo, WorkflowSupport.actorId(), now);
                // 发布版本、消费草稿及审计原子提交，保证同一草稿只能生成一次发布结果。
                if (repository.consumeDraft(context.tenantId(), draftId, expectedVersion,
                        WorkflowSupport.actorId(), now) != 1) {
                    throw ApiException.conflict("WORKFLOW_PUBLICATION_CONFLICT",
                            "Workflow Draft 发布竞争失败。");
                }
                audits.insertAudit(context.tenantId(), auditId,
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

    /**
     * 分页查询当前租户可见的流程版本，按排序及游标生成稳定结果。
     */
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

    /**
     * 按声明的租户与业务条件查询流程版本，供调用方核对可见性、状态或幂等关系。
     */
    public WorkflowDtos.WorkflowVersionResponse getVersion(UUID workflowVersionId) {
        WorkflowSupport.require(PermissionCode.WORKFLOW_READ_TENANT);
        var context = WorkflowSupport.context();
        return versionResponse(repository.findVersion(context.tenantId(), workflowVersionId)
                .orElseThrow(() -> ApiException.notFound("RESOURCE_NOT_FOUND",
                        "Workflow Version 不存在。")));
    }

    /**
     * 核对创建锚点与本次请求摘要相同，返回原草稿以吸收重复请求。
     */
    private Optional<DraftCommandResult> replayDraftCreate(UUID stableId, String digest) {
        var context = WorkflowSupport.context();
        Optional<AuditRow> audit = audits.findAudit(context.tenantId(), stableId);
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

    /**
     * 核验已发布版本与原草稿关系，避免相同幂等请求生成第二个版本。
     */
    private Optional<VersionCommandResult> replayPublication(
            UUID draftId, UUID auditId, String intentDigest) {
        var context = WorkflowSupport.context();
        Optional<AuditRow> audit = audits.findAudit(context.tenantId(), auditId);
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

    /**
     * 调用资格检查器核验目标审批人，不合格或依赖不可用时终止写入。
     */
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

    /**
     * 按当前租户读取流程定义，不存在或不可见时返回统一资源错误。
     */
    private DefinitionRow requireDefinition(UUID definitionId) {
        return repository.findDefinition(WorkflowSupport.context().tenantId(), definitionId)
                .orElseThrow(() -> ApiException.notFound("RESOURCE_NOT_FOUND",
                        "Workflow Definition 不存在。"));
    }

    /**
     * 按当前租户读取草稿，不存在或不可见时返回统一资源错误。
     */
    private DraftRow requireDraft(UUID draftId) {
        return repository.findDraft(WorkflowSupport.context().tenantId(), draftId)
                .orElseThrow(() -> ApiException.notFound("RESOURCE_NOT_FOUND",
                        "Workflow Draft 不存在。"));
    }

    /**
     * 校验草稿仍处于可编辑状态，阻止修改已被消费的规则。
     */
    private static void requireEditable(DraftRow draft) {
        if (!"EDITABLE".equals(draft.status())) {
            throw ApiException.conflict("WORKFLOW_DRAFT_NOT_EDITABLE",
                    "Workflow Draft 已消费或不可编辑。");
        }
    }

    /**
     * 检查两级审批人为不同用户，防止同一人完成全部审批。
     */
    private static void requireDistinctApprovers(UUID first, UUID second) {
        if (Objects.equals(first, second)) {
            throw ApiException.unprocessable("APPROVERS_MUST_DIFFER", "两级审批必须由不同人员办理。",
                    List.of(new FieldViolation("/body/level2ApproverUserId", "APPROVERS_MUST_DIFFER",
                            "请选择另一位审批人。")));
        }
    }

    /**
     * 验证当前支持的条件结构和两级审批配置，收集发布前所需的规则校验结果。
     */
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

    /**
     * 生成草稿规则的稳定摘要，用于比较验证时和发布时的规则内容。
     */
    private static byte[] ruleDigest(UUID tenantId, DraftRow draft) {
        return WorkflowRuleDigest.digest(tenantId, draft.definitionId(),
                draft.conditionFieldCode(), draft.conditionOperator(),
                draft.conditionValueType(), draft.conditionValueText(),
                draft.level1ApproverUserId(), draft.level2ApproverUserId());
    }

    /**
     * 计算草稿请求摘要，防止同一幂等键创建不同内容的草稿。
     */
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

    /**
     * 将流程定义持久化行转换为响应，保留资源身份、状态及版本。
     */
    private WorkflowDtos.WorkflowDefinitionResponse definitionResponse(DefinitionRow row) {
        return new WorkflowDtos.WorkflowDefinitionResponse(row.definitionId(),
                row.definitionCode(), row.definitionName(), row.status(), row.lockVersion(),
                row.createdAt(), row.updatedAt());
    }

    /**
     * 转换草稿数据并按接口场景携带版本信息，供条件更新继续使用。
     */
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

    /**
     * 转换已发布版本的不可变规则快照，供接口读取。
     */
    private WorkflowDtos.WorkflowVersionResponse versionResponse(VersionRow row) {
        return new WorkflowDtos.WorkflowVersionResponse(row.workflowVersionId(),
                row.definitionId(), row.versionNo(), row.status(),
                new WorkflowDtos.Condition(row.conditionFieldCode(), row.conditionOperator(),
                        row.conditionValueType(), row.conditionValueText()),
                row.approvalLevelCount(), row.level1ApproverUserId(),
                row.level2ApproverUserId(), HexFormat.of().formatHex(row.snapshotSha256()),
                row.publishedAt(), row.publishedByUserId());
    }

    /**
     * 从白名单选择请求排序或默认排序，拒绝不受支持的表达式。
     */
    private static String normalize(String value, String defaultValue, Set<String> allowed) {
        String normalized = value == null || value.isBlank() ? defaultValue : value;
        if (!allowed.contains(normalized)) {
            throw ApiException.badRequest("SORT_UNSUPPORTED", "不支持的排序字段。");
        }
        return normalized;
    }

    /**
     * 读取注入时钟并截断至数据库可保存的精度，便于持久化比较及测试。
     */
    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }




}
