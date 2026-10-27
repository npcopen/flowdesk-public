package com.flowdesk.ticket.service.impl;

import com.flowdesk.ticket.mapper.*;
import com.flowdesk.ticket.model.*;
import com.flowdesk.ticket.persistence.Uuids;

import com.flowdesk.ticket.service.*;

import com.flowdesk.common.authz.PermissionCode;
import com.flowdesk.common.tenant.TenantContext;
import com.flowdesk.common.web.ApiException;
import com.flowdesk.common.web.AsyncProgress;
import com.flowdesk.ticket.dto.TicketDtos;
import com.flowdesk.ticket.mapper.CatalogMapper;
import com.flowdesk.ticket.sla.TicketSlaService;
import com.flowdesk.ticket.event.TicketEventContract;
import com.flowdesk.ticket.event.WorkflowStartV1PayloadCodec;
import com.flowdesk.ticket.iam.AssigneeEligibilityChecker;
import com.flowdesk.ticket.notification.TicketNotificationIntent;
import com.flowdesk.ticket.notification.TicketNotificationOutboxWriter;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * API-021 至 API-033 的人工工单生命周期、状态机、乐观并发与评论写入用例。
 * 业务变更与审计、时间线等副作用在同一本地事务提交；API-024/026 的 IAM 资格预检位于写事务之外。
 */
@Service
public class TicketServiceImpl implements TicketService {

    private static final DateTimeFormatter TICKET_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final TicketAuditMapper ticketAuditMapper;
    private final TicketCommentMapper ticketCommentMapper;
    private final TicketMapper ticketMapper;
    private final TicketNumberMapper ticketNumberMapper;
    private final TicketOutboxWriteMapper ticketOutboxWriteMapper;
    private final TicketTimelineMapper ticketTimelineMapper;
    private final TicketWorkflowLinkMapper ticketWorkflowLinkMapper;
    private final CatalogMapper catalogRepository;
    private final AssigneeEligibilityChecker eligibilityChecker;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final TicketNotificationOutboxWriter notificationWriter;
    private final TicketSlaService slaService;

    /**
     * 构造工单组件并保存调用依赖；支持注入时钟以控制时间边界。
     */
    public TicketServiceImpl(TicketAuditMapper ticketAuditMapper, TicketCommentMapper ticketCommentMapper, TicketMapper ticketMapper, TicketNumberMapper ticketNumberMapper, TicketOutboxWriteMapper ticketOutboxWriteMapper, TicketTimelineMapper ticketTimelineMapper, TicketWorkflowLinkMapper ticketWorkflowLinkMapper, CatalogMapper catalogRepository,
                         AssigneeEligibilityChecker eligibilityChecker,
                         PlatformTransactionManager transactionManager, Clock clock,
                         TicketNotificationOutboxWriter notificationWriter,
                         TicketSlaService slaService) {
        this.ticketAuditMapper = ticketAuditMapper;
        this.ticketCommentMapper = ticketCommentMapper;
        this.ticketMapper = ticketMapper;
        this.ticketNumberMapper = ticketNumberMapper;
        this.ticketOutboxWriteMapper = ticketOutboxWriteMapper;
        this.ticketTimelineMapper = ticketTimelineMapper;
        this.ticketWorkflowLinkMapper = ticketWorkflowLinkMapper;
        this.catalogRepository = catalogRepository;
        this.eligibilityChecker = eligibilityChecker;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = Objects.requireNonNull(clock, "clock");
        this.notificationWriter = notificationWriter;
        this.slaService = slaService;
    }

    // 创建工单

    /**
     * 创建人工工单：先校验已有幂等事实，再根据目录风险选择直接待分配或等待审批。
     * 工单、时间线、审计以及 SLA/流程启动意图在同一事务内提交。
     */
    public CreateResult create(TicketDtos.TicketCreateRequest request,
                               String idempotencyKey, String ifMatch) {
        TenantContext context = TicketSupport.context();
        TicketSupport.require(PermissionCode.TICKET_CREATE);
        String key = TicketSupport.requireIdempotencyKey(idempotencyKey);
        TicketSupport.rejectIfMatchOnCreate(ifMatch);

        byte[] effectKey = IdempotencyCodec.effectKey(context, "API-021", "COLLECTION", key);
        UUID stableTicketId = IdempotencyCodec.uuidFromDigest(effectKey);
        String intent = CanonicalIntent.of(
                "catalogItemId", request.catalogItemId().toString(),
                "subject", request.subject(),
                "description", request.description(),
                "previousAttemptTicketId", request.previousAttemptTicketId() == null
                        ? "null" : request.previousAttemptTicketId().toString());
        String digest = IdempotencyCodec.requestDigestHex(intent);
        UUID auditId = IdempotencyCodec.uuidFromDigest(effectKey);

        Optional<CreateResult> replayed = replayCreate(
                context, effectKey, auditId, digest, stableTicketId, request);
        if (replayed.isPresent()) {
            return replayed.get();
        }

        CatalogItemRow catalog = catalogRepository
                .findCatalogItem(context.tenantId(), request.catalogItemId())
                .filter(item -> "ACTIVE".equals(item.status()))
                .orElseThrow(() -> ApiException.conflict("CATALOG_ITEM_NOT_AVAILABLE",
                        "目录项不存在、不可见或非 ACTIVE。"));
        if (request.previousAttemptTicketId() != null) {
            TicketRow previous = ticketMapper.findTicket(
                            context.tenantId(), request.previousAttemptTicketId())
                    .orElseThrow(() -> ApiException.unprocessable("PREVIOUS_ATTEMPT_INVALID",
                            "previousAttempt 不是本人同租户被审批拒绝的 Ticket。", List.of()));
            if (!"APPROVAL_REJECTED".equals(previous.status())
                    || !TicketSupport.actorId().equals(previous.requesterUserId())) {
                throw ApiException.unprocessable("PREVIOUS_ATTEMPT_INVALID",
                        "previousAttempt 不是本人同租户被审批拒绝的 Ticket。", List.of());
            }
        }

        Instant now = now();
        boolean highRisk = "HIGH".equals(catalog.riskLevel());
        String initialStatus = highRisk ? "AWAITING_APPROVAL" : "PENDING_ASSIGNMENT";
        Instant firstProcessableAt = highRisk ? null : now;

        try {
            TicketRow created = transactionTemplate.execute(statusTx -> {
                String ticketNo = nextTicketNo(context.tenantId(), now);
                try {
                    ticketMapper.insertTicket(context.tenantId(), stableTicketId, ticketNo, effectKey,
                            "MANUAL", catalog.catalogItemId(), TicketSupport.actorId(),
                            request.previousAttemptTicketId(), request.subject(), request.description(),
                            initialStatus, null, catalog.workflowDefinitionId(), catalog.workflowVersionId(),
                            catalog.workflowVersionNo(), firstProcessableAt, TicketSupport.actorId(),
                            context.correlationId(), now);
                } catch (DuplicateKeyException e) {
                    throw new TicketInsertDuplicateException(ticketNo, e);
                }
                String createdSummary = truncate("工单已创建：" + request.subject(), 1000);
                ticketTimelineMapper.insertTimeline(context.tenantId(), UUID.randomUUID(), stableTicketId,
                        effectKey, "CREATED", createdSummary, null, null, "USER",
                        TicketSupport.actorId(), now, context.correlationId());
                ticketAuditMapper.insertAudit(context.tenantId(), auditId, "TICKET_CREATE", "TICKET",
                        stableTicketId, "SUCCEEDED", "USER", TicketSupport.actorId(), digest,
                        context.correlationId(), now);
                if (!highRisk) {
                    slaService.insertClocks(context, stableTicketId, catalog.slaPolicyId(), now);
                } else {
                    insertWorkflowStartRequest(context, stableTicketId, catalog, now);
                }
                return ticketMapper.findTicket(context.tenantId(), stableTicketId).orElseThrow();
            });
            List<AsyncProgress> asyncEffects = highRisk
                    ? List.of(new AsyncProgress("WORKFLOW_START", "REQUESTED", null))
                    : List.of();
            return new CreateResult(TicketResponseMapper.toResponse(created), false, asyncEffects);
        } catch (TicketInsertDuplicateException e) {
            return resolveCreateDuplicate(context, effectKey, auditId, digest, stableTicketId,
                    request, e.ticketNo());
        } catch (DuplicateKeyException e) {
            return resolveCreateDuplicate(context, effectKey, auditId, digest, stableTicketId,
                    request, null);
        }
    }

    /** 唯一约束竞争后重读幂等锚点，区分同一请求重放、工单编号冲突与无关约束冲突。 */
    private CreateResult resolveCreateDuplicate(TenantContext context, byte[] effectKey,
                                                UUID auditId, String digest, UUID stableTicketId,
                                                TicketDtos.TicketCreateRequest request,
                                                String attemptedTicketNo) {
        Optional<CreateResult> replayed = replayCreate(
                context, effectKey, auditId, digest, stableTicketId, request);
        if (replayed.isPresent()) {
            return replayed.get();
        }
        if (attemptedTicketNo != null
                && ticketMapper.findTicketByNumber(context.tenantId(), attemptedTicketNo).isPresent()) {
            throw ApiException.conflict("TICKET_NUMBER_CONFLICT",
                    "工单编号与既有工单冲突；事务已回滚，请重试。");
        }
        throw unrelatedConstraintConflict();
    }

    /** 仅在工单、创建意图、时间线和审计完整且与本次请求一致时认定为创建重放。 */
    private Optional<CreateResult> replayCreate(TenantContext context, byte[] effectKey,
                                                UUID auditId, String digest, UUID stableTicketId,
                                                TicketDtos.TicketCreateRequest request) {
        CreateReplayAnchors anchors = readCreateReplayAnchors(
                context, effectKey, auditId, stableTicketId);
        if (anchors.partial()) {
            // 多次独立读取可能跨越竞争事务的原子提交点。
            // 只要看到任一锚点，就整体重读一次，以区分读取时序偏差和真正缺失的持久化副作用。
            anchors = readCreateReplayAnchors(context, effectKey, auditId, stableTicketId);
        }
        if (anchors.empty()) {
            return Optional.empty();
        }
        if (!anchors.complete()) {
            throw incompleteIdempotencyAnchor();
        }

        TicketRow ticket = anchors.byId().orElseThrow();
        if (!stableTicketId.equals(anchors.byIntent().orElseThrow().ticketId())
                || !stableTicketId.equals(ticket.ticketId())
                || !Arrays.equals(effectKey, ticket.creationIntentKey())
                || !"MANUAL".equals(ticket.originType())
                || !TicketSupport.actorId().equals(ticket.requesterUserId())
                || !request.catalogItemId().equals(ticket.catalogItemId())
                || !Objects.equals(request.previousAttemptTicketId(), ticket.previousAttemptTicketId())
                || !request.subject().equals(ticket.subject())
                || !request.description().equals(ticket.description())) {
            throw reusedIdempotencyKey();
        }
        validateTimeline(anchors.timeline().orElseThrow(), stableTicketId, effectKey,
                "CREATED", null, null, anchors.audit().orElseThrow().correlationId());
        validateAudit(anchors.audit().orElseThrow(), auditId, "TICKET_CREATE", "TICKET",
                stableTicketId, digest);
        return Optional.of(new CreateResult(TicketResponseMapper.toResponse(ticket), true, List.of()));
    }

    /**
     * 读取工单、审计与时间线的创建幂等锚点，核对请求是否完整执行。
     */
    private CreateReplayAnchors readCreateReplayAnchors(
            TenantContext context, byte[] effectKey, UUID auditId, UUID stableTicketId) {
        return new CreateReplayAnchors(
                ticketMapper.findTicket(context.tenantId(), stableTicketId),
                ticketMapper.findTicketByCreationIntent(context.tenantId(), effectKey),
                ticketTimelineMapper.findTimelineByEffectKey(context.tenantId(), stableTicketId, effectKey),
                ticketAuditMapper.findAudit(context.tenantId(), auditId));
    }

    // 分配工单

    /** 分配待分配工单；IAM 资格预检位于写事务外，落库时再以版本条件防止并发覆盖。 */
    public TransitionResult assign(UUID ticketId, TicketDtos.AssignmentCommand request,
                                   String idempotencyKey, String ifMatch) {
        TenantContext context = TicketSupport.context();
        TicketSupport.require(PermissionCode.TICKET_ASSIGN_TENANT);
        String key = TicketSupport.requireIdempotencyKey(idempotencyKey);
        TicketRow existing = ticketMapper.findTicket(context.tenantId(), ticketId)
                .orElseThrow(() -> ApiException.notFound("RESOURCE_NOT_FOUND", "工单不存在。"));

        byte[] effectKey = IdempotencyCodec.effectKey(context, "API-024", ticketId.toString(), key);
        String digest = requestDigest(CanonicalIntent.of(
                "ticketId", ticketId.toString(),
                "assigneeUserId", request.assigneeUserId().toString()));
        TicketReplayContract replayContract = new TicketReplayContract(
                "TICKET_ASSIGN", "ASSIGNED", "USER", request.assigneeUserId());
        Optional<TicketRow> replayed = replayTicket(
                context, ticketId, effectKey, digest, replayContract);
        if (replayed.isPresent()) {
            return new TransitionResult(TicketResponseMapper.toResponse(replayed.get()), true);
        }

        long expected = TicketSupport.parseIfMatch(ifMatch);
        if (existing.lockVersion() != expected) {
            throw TicketSupport.versionConflict(TicketSupport.etagHeaders(existing.lockVersion()));
        }
        if (!"PENDING_ASSIGNMENT".equals(existing.status())) {
            throw ApiException.conflict("TICKET_STATE_INVALID", "当前 Ticket 状态不允许分配。");
        }
        if (!eligibilityChecker.isEligibleAgent(context.tenantId(), request.assigneeUserId())) {
            throw ApiException.unprocessable("ASSIGNEE_NOT_ELIGIBLE",
                    "目标用户不是同租户有效 Agent。", List.of());
        }

        Instant now = now();
        try {
            TicketRow updated = transactionTemplate.execute(tx -> {
                if (ticketMapper.updateTicketAssign(context.tenantId(), ticketId, expected,
                        request.assigneeUserId(), TicketSupport.actorId(), now) != 1) {
                    throw new CommandWriteRaceException();
                }
                ticketTimelineMapper.insertTimeline(context.tenantId(), UUID.randomUUID(), ticketId, effectKey,
                        "ASSIGNED", "工单已分配。", "USER", request.assigneeUserId(), "USER",
                        TicketSupport.actorId(), now, context.correlationId());
                ticketAuditMapper.insertAudit(context.tenantId(), IdempotencyCodec.uuidFromDigest(effectKey),
                        "TICKET_ASSIGN", "TICKET", ticketId, "SUCCEEDED", "USER",
                        TicketSupport.actorId(), digest, context.correlationId(), now);
                writeNotification(context, request.assigneeUserId(), TicketNotificationIntent.ASSIGNED,
                        effectKey, ticketId, "工单已分配", "有一张工单已分配给你。", now);
                return ticketMapper.findTicket(context.tenantId(), ticketId).orElseThrow();
            });
            return new TransitionResult(TicketResponseMapper.toResponse(updated), false);
        } catch (CommandWriteRaceException e) {
            return replayRaceOrVersionConflict(context, ticketId, effectKey, digest, replayContract);
        } catch (DuplicateKeyException e) {
            return replayTransitionDuplicate(context, ticketId, effectKey, digest, replayContract);
        }
    }

    // 接单

    /** 仅当前受让人可接单；状态推进、首次响应 SLA 完成及审计时间线一并提交。 */
    public TransitionResult accept(UUID ticketId, String idempotencyKey, String ifMatch) {
        TenantContext context = TicketSupport.context();
        TicketSupport.require(PermissionCode.TICKET_ACCEPT_ASSIGNED);
        String key = TicketSupport.requireIdempotencyKey(idempotencyKey);
        TicketRow existing = ticketMapper.findTicket(context.tenantId(), ticketId)
                .orElseThrow(() -> ApiException.notFound("RESOURCE_NOT_FOUND", "工单不存在。"));

        byte[] effectKey = IdempotencyCodec.effectKey(context, "API-025", ticketId.toString(), key);
        String digest = requestDigest(CanonicalIntent.of("ticketId", ticketId.toString(), "body", ""));
        TicketReplayContract replayContract = new TicketReplayContract(
                "TICKET_ACCEPT", "ACCEPTED", null, null);
        Optional<TicketRow> replayed = replayTicket(
                context, ticketId, effectKey, digest, replayContract);
        if (replayed.isPresent()) {
            return new TransitionResult(TicketResponseMapper.toResponse(replayed.get()), true);
        }

        if (!TicketSupport.actorId().equals(existing.assigneeUserId())) {
            throw ApiException.forbidden("TICKET_NOT_ASSIGNED_TO_ACTOR", "当前用户不是该工单的受让人。");
        }
        long expected = TicketSupport.parseIfMatch(ifMatch);
        if (existing.lockVersion() != expected) {
            throw TicketSupport.versionConflict(TicketSupport.etagHeaders(existing.lockVersion()));
        }
        if (!"ASSIGNED".equals(existing.status())) {
            throw ApiException.conflict("TICKET_STATE_INVALID", "当前 Ticket 状态不允许接单。");
        }
        Instant now = now();
        try {
            TicketRow updated = transactionTemplate.execute(tx -> {
                if (ticketMapper.updateTicketAccept(context.tenantId(), ticketId, expected,
                        TicketSupport.actorId(), now) != 1) {
                    throw new CommandWriteRaceException();
                }
                slaService.satisfyClock(context, ticketId, "FIRST_RESPONSE", now);
                ticketTimelineMapper.insertTimeline(context.tenantId(), UUID.randomUUID(), ticketId, effectKey,
                        "ACCEPTED", "工单已接单。", null, null, "USER",
                        TicketSupport.actorId(), now, context.correlationId());
                ticketAuditMapper.insertAudit(context.tenantId(), IdempotencyCodec.uuidFromDigest(effectKey),
                        "TICKET_ACCEPT", "TICKET", ticketId, "SUCCEEDED", "USER",
                        TicketSupport.actorId(), digest, context.correlationId(), now);
                return ticketMapper.findTicket(context.tenantId(), ticketId).orElseThrow();
            });
            return new TransitionResult(TicketResponseMapper.toResponse(updated), false);
        } catch (CommandWriteRaceException e) {
            return replayRaceOrVersionConflict(context, ticketId, effectKey, digest, replayContract);
        } catch (DuplicateKeyException e) {
            return replayTransitionDuplicate(context, ticketId, effectKey, digest, replayContract);
        }
    }

    // 转派工单

    /** 校验当前操作者与目标 Agent 后转派工单，事务内更新受让人并记录通知意图。 */
    public TransitionResult transfer(UUID ticketId, TicketDtos.TransferCommand request,
                                     String idempotencyKey, String ifMatch) {
        TenantContext context = TicketSupport.context();
        TicketSupport.requireAny(PermissionCode.TICKET_TRANSFER_ASSIGNED,
                PermissionCode.TICKET_TRANSFER_TENANT);
        String key = TicketSupport.requireIdempotencyKey(idempotencyKey);
        TicketRow existing = ticketMapper.findTicket(context.tenantId(), ticketId)
                .orElseThrow(() -> ApiException.notFound("RESOURCE_NOT_FOUND", "工单不存在。"));
        byte[] effectKey = IdempotencyCodec.effectKey(context, "API-026", ticketId.toString(), key);
        String digest = requestDigest(CanonicalIntent.of(
                "ticketId", ticketId.toString(),
                "newAssigneeUserId", request.newAssigneeUserId().toString(),
                "reason", request.reason()));
        TicketReplayContract replayContract = new TicketReplayContract(
                "TICKET_TRANSFER", "TRANSFERRED", "USER", request.newAssigneeUserId());
        Optional<TicketRow> replayed = replayTicket(
                context, ticketId, effectKey, digest, replayContract);
        if (replayed.isPresent()) {
            return new TransitionResult(TicketResponseMapper.toResponse(replayed.get()), true);
        }
        boolean admin = TicketSupport.hasAny(PermissionCode.TICKET_TRANSFER_TENANT);
        if (!admin && !TicketSupport.actorId().equals(existing.assigneeUserId())) {
            throw ApiException.forbidden("TICKET_TRANSFER_FORBIDDEN", "当前用户无权转派该工单。");
        }
        long expected = TicketSupport.parseIfMatch(ifMatch);
        if (existing.lockVersion() != expected) {
            throw TicketSupport.versionConflict(TicketSupport.etagHeaders(existing.lockVersion()));
        }
        if (!"ASSIGNED".equals(existing.status()) && !"IN_PROGRESS".equals(existing.status())) {
            throw ApiException.conflict("TICKET_STATE_INVALID", "当前 Ticket 状态不允许转派。");
        }
        if (!eligibilityChecker.isEligibleAgent(context.tenantId(), request.newAssigneeUserId())) {
            throw ApiException.unprocessable("ASSIGNEE_NOT_ELIGIBLE",
                    "目标用户不是同租户有效 Agent。", List.of());
        }
        Instant now = now();
        try {
            TicketRow updated = transactionTemplate.execute(tx -> {
                if (ticketMapper.updateTicketTransfer(context.tenantId(), ticketId, expected,
                        request.newAssigneeUserId(), TicketSupport.actorId(), now) != 1) {
                    throw new CommandWriteRaceException();
                }
                ticketTimelineMapper.insertTimeline(context.tenantId(), UUID.randomUUID(), ticketId, effectKey,
                        "TRANSFERRED", "工单已转派。", "USER", request.newAssigneeUserId(), "USER",
                        TicketSupport.actorId(), now, context.correlationId());
                ticketAuditMapper.insertAudit(context.tenantId(), IdempotencyCodec.uuidFromDigest(effectKey),
                        "TICKET_TRANSFER", "TICKET", ticketId, "SUCCEEDED", "USER",
                        TicketSupport.actorId(), digest, context.correlationId(), now);
                writeNotification(context, request.newAssigneeUserId(), TicketNotificationIntent.TRANSFERRED,
                        effectKey, ticketId, "工单已转派", "有一张工单已转派给你。", now);
                return ticketMapper.findTicket(context.tenantId(), ticketId).orElseThrow();
            });
            return new TransitionResult(TicketResponseMapper.toResponse(updated), false);
        } catch (CommandWriteRaceException e) {
            return replayRaceOrVersionConflict(context, ticketId, effectKey, digest, replayContract);
        } catch (DuplicateKeyException e) {
            return replayTransitionDuplicate(context, ticketId, effectKey, digest, replayContract);
        }
    }

    // 评论

    /** 仅申请人或受让人可为非终态工单评论；评论身份由请求幂等键确定，避免重试重复留言。 */
    public CommentResult addComment(UUID ticketId, TicketDtos.CommentCreateRequest request,
                                    String idempotencyKey) {
        TenantContext context = TicketSupport.context();
        TicketSupport.requireAny(PermissionCode.TICKET_COMMENT_OWN, PermissionCode.TICKET_COMMENT_ASSIGNED);
        String key = TicketSupport.requireIdempotencyKey(idempotencyKey);
        TicketRow ticket = ticketMapper.findTicket(context.tenantId(), ticketId)
                .orElseThrow(() -> ApiException.notFound("RESOURCE_NOT_FOUND", "工单不存在。"));

        byte[] effectKey = IdempotencyCodec.effectKey(context, "API-028", ticketId.toString(), key);
        UUID stableCommentId = IdempotencyCodec.uuidFromDigest(effectKey);
        UUID auditId = stableCommentId;
        String digest = requestDigest(CanonicalIntent.of("ticketId", ticketId.toString(),
                "body", request.body()));
        byte[] timelineEffectKey = IdempotencyCodec.derivedKey(
                "FD-COMMENT-TIMELINE-V1", context.tenantId(), stableCommentId);
        Optional<CommentResult> replayed = replayComment(context, ticketId, stableCommentId,
                auditId, timelineEffectKey, digest, request.body());
        if (replayed.isPresent()) {
            return replayed.get();
        }

        boolean permitted = TicketSupport.actorId().equals(ticket.requesterUserId())
                || TicketSupport.actorId().equals(ticket.assigneeUserId());
        if (!permitted) {
            throw ApiException.forbidden("TICKET_COMMENT_FORBIDDEN", "当前用户无权评论该工单。");
        }
        if ("CLOSED".equals(ticket.status()) || "APPROVAL_REJECTED".equals(ticket.status())) {
            throw ApiException.conflict("TICKET_TERMINAL", "终态工单不接受新评论。");
        }

        Instant now = now();
        try {
            CommentRow created = transactionTemplate.execute(tx -> {
                ticketCommentMapper.insertComment(context.tenantId(), stableCommentId, ticketId,
                        request.body(), context.correlationId(), TicketSupport.actorId(), now);
                ticketTimelineMapper.insertTimeline(context.tenantId(), UUID.randomUUID(), ticketId,
                        timelineEffectKey, "COMMENT_ADDED", "新增评论。", "COMMENT", stableCommentId,
                        "USER", TicketSupport.actorId(), now, context.correlationId());
                ticketAuditMapper.insertAudit(context.tenantId(), auditId, "TICKET_COMMENT_CREATE",
                        "COMMENT", stableCommentId, "SUCCEEDED", "USER", TicketSupport.actorId(),
                        digest, context.correlationId(), now);
                return ticketCommentMapper.findComment(context.tenantId(), stableCommentId).orElseThrow();
            });
            return new CommentResult(TicketResponseMapper.toResponse(created), false);
        } catch (DuplicateKeyException e) {
            return replayComment(context, ticketId, stableCommentId, auditId,
                    timelineEffectKey, digest, request.body())
                    .orElseThrow(TicketServiceImpl::unrelatedConstraintConflict);
        }
    }

    /** 核对评论内容、作者、时间线及审计后复用原结果，不把部分缺失视为成功。 */
    private Optional<CommentResult> replayComment(TenantContext context, UUID ticketId,
                                                  UUID commentId, UUID auditId,
                                                  byte[] timelineEffectKey, String digest,
                                                  String expectedBody) {
        Optional<CommentRow> comment = ticketCommentMapper.findComment(
                context.tenantId(), commentId);
        Optional<TimelineRow> timeline = ticketTimelineMapper.findTimelineByEffectKey(
                context.tenantId(), ticketId, timelineEffectKey);
        Optional<AuditRow> audit = ticketAuditMapper.findAudit(context.tenantId(), auditId);
        if (comment.isEmpty() && timeline.isEmpty() && audit.isEmpty()) {
            return Optional.empty();
        }
        if (comment.isEmpty() || timeline.isEmpty() || audit.isEmpty()) {
            throw incompleteIdempotencyAnchor();
        }
        CommentRow row = comment.get();
        if (!commentId.equals(row.commentId()) || !ticketId.equals(row.ticketId())
                || !expectedBody.equals(row.body())
                || !TicketSupport.actorId().equals(row.authorUserId())) {
            throw reusedIdempotencyKey();
        }
        validateTimeline(timeline.get(), ticketId, timelineEffectKey, "COMMENT_ADDED",
                "COMMENT", commentId, audit.get().correlationId());
        validateAudit(audit.get(), auditId, "TICKET_COMMENT_CREATE", "COMMENT", commentId, digest);
        return Optional.of(new CommentResult(TicketResponseMapper.toResponse(row), true));
    }

    // 解决、关闭与重开

    /** 由当前受让人解决处理中工单，并在同一事务完成解决 SLA、时间线、审计及申请人通知。 */
    public TransitionResult resolve(UUID ticketId, TicketDtos.ResolveCommand request,
                                    String idempotencyKey, String ifMatch) {
        TenantContext context = TicketSupport.context();
        TicketSupport.require(PermissionCode.TICKET_RESOLVE_ASSIGNED);
        String key = TicketSupport.requireIdempotencyKey(idempotencyKey);
        TicketRow existing = ticketMapper.findTicket(context.tenantId(), ticketId)
                .orElseThrow(() -> ApiException.notFound("RESOURCE_NOT_FOUND", "工单不存在。"));

        byte[] effectKey = IdempotencyCodec.effectKey(context, "API-029", ticketId.toString(), key);
        String digest = requestDigest(CanonicalIntent.of(
                "ticketId", ticketId.toString(),
                "resolutionSummary", request.resolutionSummary()));
        TicketReplayContract replayContract = new TicketReplayContract(
                "TICKET_RESOLVE", "RESOLVED", null, null);
        Optional<TicketRow> replayed = replayTicket(
                context, ticketId, effectKey, digest, replayContract);
        if (replayed.isPresent()) {
            return new TransitionResult(TicketResponseMapper.toResponse(replayed.get()), true);
        }

        if (!TicketSupport.actorId().equals(existing.assigneeUserId())) {
            throw ApiException.forbidden("TICKET_NOT_ASSIGNED_TO_ACTOR", "当前用户不是该工单的受让人。");
        }
        long expected = TicketSupport.parseIfMatch(ifMatch);
        if (existing.lockVersion() != expected) {
            throw TicketSupport.versionConflict(TicketSupport.etagHeaders(existing.lockVersion()));
        }
        if (!"IN_PROGRESS".equals(existing.status())) {
            throw ApiException.conflict("TICKET_STATE_INVALID", "当前 Ticket 状态不允许解决。");
        }
        Instant now = now();
        try {
            TicketRow updated = transactionTemplate.execute(tx -> {
                if (ticketMapper.updateTicketResolve(context.tenantId(), ticketId, expected,
                        TicketSupport.actorId(), now) != 1) {
                    throw new CommandWriteRaceException();
                }
                slaService.satisfyClock(context, ticketId, "RESOLUTION", now);
                ticketTimelineMapper.insertTimeline(context.tenantId(), UUID.randomUUID(), ticketId, effectKey,
                        "RESOLVED", truncate("工单已解决：" + request.resolutionSummary(), 1000),
                        null, null, "USER", TicketSupport.actorId(), now, context.correlationId());
                ticketAuditMapper.insertAudit(context.tenantId(), IdempotencyCodec.uuidFromDigest(effectKey),
                        "TICKET_RESOLVE", "TICKET", ticketId, "SUCCEEDED", "USER",
                        TicketSupport.actorId(), digest, context.correlationId(), now);
                writeNotification(context, existing.requesterUserId(), TicketNotificationIntent.RESOLVED,
                        effectKey, ticketId, "工单已解决", "你提交的工单已解决。", now);
                return ticketMapper.findTicket(context.tenantId(), ticketId).orElseThrow();
            });
            return new TransitionResult(TicketResponseMapper.toResponse(updated), false);
        } catch (CommandWriteRaceException e) {
            return replayRaceOrVersionConflict(context, ticketId, effectKey, digest, replayContract);
        } catch (DuplicateKeyException e) {
            return replayTransitionDuplicate(context, ticketId, effectKey, digest, replayContract);
        }
    }

    /** 仅原申请人可确认关闭已解决的人工工单，Incident 工单不走此人工关闭路径。 */
    public TransitionResult close(UUID ticketId, TicketDtos.CloseOrReopenCommand request,
                                  String idempotencyKey, String ifMatch) {
        TenantContext context = TicketSupport.context();
        TicketSupport.require(PermissionCode.TICKET_CLOSE_OWN);
        String key = TicketSupport.requireIdempotencyKey(idempotencyKey);
        TicketRow existing = ticketMapper.findTicket(context.tenantId(), ticketId)
                .orElseThrow(() -> ApiException.notFound("RESOURCE_NOT_FOUND", "工单不存在。"));
        if (!"MANUAL".equals(existing.originType())) {
            throw ApiException.conflict("TICKET_ORIGIN_NOT_CLOSABLE", "Incident 工单不能由人工关闭。");
        }
        if (!TicketSupport.actorId().equals(existing.requesterUserId())) {
            throw ApiException.forbidden("TICKET_REQUESTER_ONLY", "只有原申请人可以关闭该工单。");
        }

        byte[] effectKey = IdempotencyCodec.effectKey(context, "API-030", ticketId.toString(), key);
        String digest = requestDigest(CanonicalIntent.of(
                "ticketId", ticketId.toString(),
                "reason", request.reason() == null ? "null" : request.reason()));
        TicketReplayContract replayContract = new TicketReplayContract(
                "TICKET_CLOSE", "CLOSED", null, null);
        Optional<TicketRow> replayed = replayTicket(
                context, ticketId, effectKey, digest, replayContract);
        if (replayed.isPresent()) {
            return new TransitionResult(TicketResponseMapper.toResponse(replayed.get()), true);
        }
        long expected = TicketSupport.parseIfMatch(ifMatch);
        if (existing.lockVersion() != expected) {
            throw TicketSupport.versionConflict(TicketSupport.etagHeaders(existing.lockVersion()));
        }
        if (!"RESOLVED".equals(existing.status())) {
            throw ApiException.conflict("TICKET_STATE_INVALID", "当前 Ticket 状态不允许关闭。");
        }
        Instant now = now();
        try {
            TicketRow updated = transactionTemplate.execute(tx -> {
                if (ticketMapper.updateTicketClose(context.tenantId(), ticketId, expected,
                        TicketSupport.actorId(), now) != 1) {
                    throw new CommandWriteRaceException();
                }
                ticketTimelineMapper.insertTimeline(context.tenantId(), UUID.randomUUID(), ticketId, effectKey,
                        "CLOSED", request.reason() == null ? "申请人已确认关闭。" : truncate(
                                "申请人已确认关闭：" + request.reason(), 1000), null, null, "USER",
                        TicketSupport.actorId(), now, context.correlationId());
                ticketAuditMapper.insertAudit(context.tenantId(), IdempotencyCodec.uuidFromDigest(effectKey),
                        "TICKET_CLOSE", "TICKET", ticketId, "SUCCEEDED", "USER",
                        TicketSupport.actorId(), digest, context.correlationId(), now);
                return ticketMapper.findTicket(context.tenantId(), ticketId).orElseThrow();
            });
            return new TransitionResult(TicketResponseMapper.toResponse(updated), false);
        } catch (CommandWriteRaceException e) {
            return replayRaceOrVersionConflict(context, ticketId, effectKey, digest, replayContract);
        } catch (DuplicateKeyException e) {
            return replayTransitionDuplicate(context, ticketId, effectKey, digest, replayContract);
        }
    }

    /** 由原申请人携带原因重开已解决的人工工单，新增解决 SLA 周期并通知处理人。 */
    public TransitionResult reopen(UUID ticketId, TicketDtos.CloseOrReopenCommand request,
                                   String idempotencyKey, String ifMatch) {
        TenantContext context = TicketSupport.context();
        TicketSupport.require(PermissionCode.TICKET_REOPEN_OWN);
        String key = TicketSupport.requireIdempotencyKey(idempotencyKey);
        TicketRow existing = ticketMapper.findTicket(context.tenantId(), ticketId)
                .orElseThrow(() -> ApiException.notFound("RESOURCE_NOT_FOUND", "工单不存在。"));
        if (!"MANUAL".equals(existing.originType())) {
            throw ApiException.conflict("TICKET_ORIGIN_NOT_REOPENABLE", "Incident 工单不能重新打开。");
        }
        if (!TicketSupport.actorId().equals(existing.requesterUserId())) {
            throw ApiException.forbidden("TICKET_REQUESTER_ONLY", "只有原申请人可以重新打开该工单。");
        }
        if (request.reason() == null || request.reason().isBlank()) {
            throw ApiException.unprocessable("VALIDATION_FAILED", "重开必须提供原因。",
                    List.of(new com.flowdesk.common.web.FieldViolation(
                            "/body/reason", "REQUIRED", "重开必须提供原因。")));
        }

        byte[] effectKey = IdempotencyCodec.effectKey(context, "API-031", ticketId.toString(), key);
        String digest = requestDigest(CanonicalIntent.of("ticketId", ticketId.toString(),
                "reason", request.reason()));
        TicketReplayContract replayContract = new TicketReplayContract(
                "TICKET_REOPEN", "REOPENED", null, null);
        Optional<TicketRow> replayed = replayTicket(
                context, ticketId, effectKey, digest, replayContract);
        if (replayed.isPresent()) {
            return new TransitionResult(TicketResponseMapper.toResponse(replayed.get()), true);
        }
        long expected = TicketSupport.parseIfMatch(ifMatch);
        if (existing.lockVersion() != expected) {
            throw TicketSupport.versionConflict(TicketSupport.etagHeaders(existing.lockVersion()));
        }
        if (!"RESOLVED".equals(existing.status())) {
            throw ApiException.conflict("TICKET_STATE_INVALID", "当前 Ticket 状态不允许重新打开。");
        }
        Instant now = now();
        try {
            TicketRow updated = transactionTemplate.execute(tx -> {
                if (ticketMapper.updateTicketReopen(context.tenantId(), ticketId, expected,
                        TicketSupport.actorId(), now) != 1) {
                    throw new CommandWriteRaceException();
                }
                ticketTimelineMapper.insertTimeline(context.tenantId(), UUID.randomUUID(), ticketId, effectKey,
                        "REOPENED", truncate("申请人已重新打开：" + request.reason(), 1000),
                        null, null, "USER", TicketSupport.actorId(), now, context.correlationId());
                slaService.reopenResolutionClock(context, ticketId, existing.reopenCount() + 1, now);
                writeNotification(context, existing.assigneeUserId(), TicketNotificationIntent.REOPENED,
                        effectKey, ticketId, "工单已重新打开", "申请人反馈问题仍未解决，请继续处理。原因：" + request.reason(), now);
                ticketAuditMapper.insertAudit(context.tenantId(), IdempotencyCodec.uuidFromDigest(effectKey),
                        "TICKET_REOPEN", "TICKET", ticketId, "SUCCEEDED", "USER",
                        TicketSupport.actorId(), digest, context.correlationId(), now);
                return ticketMapper.findTicket(context.tenantId(), ticketId).orElseThrow();
            });
            return new TransitionResult(TicketResponseMapper.toResponse(updated), false);
        } catch (CommandWriteRaceException e) {
            return replayRaceOrVersionConflict(context, ticketId, effectKey, digest, replayContract);
        } catch (DuplicateKeyException e) {
            return replayTransitionDuplicate(context, ticketId, effectKey, digest, replayContract);
        }
    }

    // 通用辅助方法

    /** 用同一副作用键同时验证时间线与审计；必须两者完整一致，才复用当前工单响应。 */
    private Optional<TicketRow> replayTicket(
            TenantContext context, UUID ticketId, byte[] effectKey, String digest,
            TicketReplayContract contract) {
        UUID auditId = IdempotencyCodec.uuidFromDigest(effectKey);
        Optional<TimelineRow> timeline = ticketTimelineMapper.findTimelineByEffectKey(
                context.tenantId(), ticketId, effectKey);
        Optional<AuditRow> audit = ticketAuditMapper.findAudit(context.tenantId(), auditId);
        if (timeline.isEmpty() && audit.isEmpty()) {
            return Optional.empty();
        }
        if (timeline.isEmpty() || audit.isEmpty()) {
            throw incompleteIdempotencyAnchor();
        }

        TicketRow ticket = ticketMapper.findTicket(context.tenantId(), ticketId)
                .orElseThrow(TicketServiceImpl::incompleteIdempotencyAnchor);
        validateTimeline(timeline.get(), ticketId, effectKey, contract.eventType(),
                contract.relatedRefType(), contract.relatedRefId(), audit.get().correlationId());
        validateAudit(audit.get(), auditId, contract.actionCode(), "TICKET", ticketId, digest);
        return Optional.of(ticket);
    }

    /** 状态更新竞争失败后优先确认同一请求是否已成功；没有完整幂等事实才返回版本冲突。 */
    private TransitionResult replayRaceOrVersionConflict(
            TenantContext context, UUID ticketId, byte[] effectKey, String digest,
            TicketReplayContract contract) {
        return replayTicket(context, ticketId, effectKey, digest, contract)
                .map(row -> new TransitionResult(TicketResponseMapper.toResponse(row), true))
                .orElseThrow(() -> versionConflict(context, ticketId));
    }

    /**
     * 在写入竞争后回读既有流转事实，确认同一意图后返回幂等结果。
     */
    private TransitionResult replayTransitionDuplicate(
            TenantContext context, UUID ticketId, byte[] effectKey, String digest,
            TicketReplayContract contract) {
        TicketRow replayed = replayTicket(
                context, ticketId, effectKey, digest, contract)
                .orElseThrow(TicketServiceImpl::unrelatedConstraintConflict);
        return new TransitionResult(TicketResponseMapper.toResponse(replayed), true);
    }

    /**
     * 核对既有时间线与当前操作意图及目标，拒绝不一致的重放锚点。
     */
    private void validateTimeline(TimelineRow timeline, UUID ticketId,
                                  byte[] effectKey, String eventType,
                                  String relatedRefType, UUID relatedRefId,
                                  UUID expectedCorrelationId) {
        if (!ticketId.equals(timeline.ticketId())
                || !Arrays.equals(effectKey, timeline.effectKey())
                || !eventType.equals(timeline.eventType())
                || !Objects.equals(relatedRefType, timeline.relatedRefType())
                || !Objects.equals(relatedRefId, timeline.relatedRefId())
                || !"USER".equals(timeline.actorType())
                || !TicketSupport.actorId().equals(timeline.actorId())
                || expectedCorrelationId == null
                || !expectedCorrelationId.equals(timeline.correlationId())) {
            throw reusedIdempotencyKey();
        }
    }

    /**
     * 核对审计操作、目标和摘要，防止不同意图复用同一业务效果。
     */
    private void validateAudit(AuditRow audit, UUID auditId,
                               String actionCode, String targetType, UUID targetId,
                               String digest) {
        if (!auditId.equals(audit.auditId())
                || !actionCode.equals(audit.actionCode())
                || !targetType.equals(audit.targetType())
                || !Objects.equals(targetId, audit.targetId())
                || !"SUCCEEDED".equals(audit.outcome())
                || !"USER".equals(audit.actorType())
                || !TicketSupport.actorId().equals(audit.actorId())
                || !digest.equals(audit.reason())
                || audit.errorCode() != null
                || audit.correlationId() == null
                || audit.causationId() != null) {
            throw reusedIdempotencyKey();
        }
    }

    /**
     * 构造幂等事实不完整错误，避免缺失审计或时间线时误判成功。
     */
    private static ApiException incompleteIdempotencyAnchor() {
        return ApiException.conflict("IDEMPOTENCY_KEY_REUSED",
                "幂等锚点不完整或成功业务效果无法确认，拒绝重放。");
    }

    /**
     * 构造不同业务意图复用同一幂等键的冲突响应。
     */
    private static ApiException reusedIdempotencyKey() {
        return ApiException.conflict("IDEMPOTENCY_KEY_REUSED",
                "同一 Idempotency-Key 已用于不同业务意图或锚点不一致。");
    }

    /**
     * 返回无关唯一约束冲突，避免将普通数据冲突误判为成功重放。
     */
    private static ApiException unrelatedConstraintConflict() {
        return ApiException.conflict("RESOURCE_ALREADY_EXISTS",
                "数据唯一约束冲突，且无法确认属于当前幂等请求。");
    }

    /**
     * 构造乐观锁冲突响应，提示客户端重新读取资源当前版本。
     */
    private ApiException versionConflict(TenantContext context, UUID ticketId) {
        TicketRow refreshed = ticketMapper.findTicket(context.tenantId(), ticketId)
                .orElseThrow(() -> ApiException.notFound("RESOURCE_NOT_FOUND", "工单不存在。"));
        return TicketSupport.versionConflict(TicketSupport.etagHeaders(refreshed.lockVersion()));
    }

    /** 将审批启动关联及固定载荷写入本地 Outbox，待后台可靠发布，避免在工单事务中直接调用 MQ。 */
    private void insertWorkflowStartRequest(TenantContext context, UUID ticketId,
                                            CatalogItemRow catalog, Instant now) {
        byte[] startIntentKey = IdempotencyCodec.derivedKey(
                TicketEventContract.WorkflowStartRequested.BUSINESS_INTENT_KEY_NAMESPACE,
                context.tenantId(), ticketId);
        ticketWorkflowLinkMapper.insertWorkflowStartLink(context.tenantId(), ticketId, startIntentKey, now,
                context.correlationId());
        byte[] bodyBytes = WorkflowStartV1PayloadCodec.encode(
                ticketId,
                catalog.workflowDefinitionId(),
                catalog.workflowVersionId(),
                catalog.workflowVersionNo(),
                TicketSupport.actorId());
        UUID messageId = UUID.randomUUID();
        ticketOutboxWriteMapper.insertOutboxEvent(context.tenantId(), UUID.randomUUID(), messageId,
                IdempotencyCodec.publicationKey(
                        TicketEventContract.WorkflowStartRequested.PUBLICATION_KEY_NAMESPACE,
                        context.tenantId(), messageId),
                startIntentKey, TicketEventContract.WorkflowStartRequested.AGGREGATE_TYPE, ticketId,
                TicketEventContract.WorkflowStartRequested.CONTRACT_REF,
                TicketEventContract.WorkflowStartRequested.EVENT_VERSION, bodyBytes,
                sha256(bodyBytes), context.correlationId(), now);
    }

    /** 以 UTC 日期和租户内原子日序号生成工单编号，编号分配随外层事务提交或回滚。 */
    private String nextTicketNo(UUID tenantId, Instant now) {
        LocalDate businessDate = LocalDate.ofInstant(now, ZoneOffset.UTC);
        String prefix = "FD-" + TICKET_DATE.format(businessDate) + "-";
        long sequence = ticketNumberMapper.nextTicketSequence(tenantId, businessDate, now);
        return String.format(Locale.ROOT, "%s%04d", prefix, sequence);
    }

    /**
     * 限制文本长度，避免摘要或审计内容超过持久化字段容量。
     */
    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    /**
     * 取得当前时间并规范为业务使用的时间精度。
     */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * 将稳定幂等键转为 UUID，保持业务事实与关联记录身份一致。
     */
    private static UUID uuidFromKey(byte[] key) {
        byte[] first = Arrays.copyOf(key, 16);
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(first);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    /**
     * 组织业务通知意图并写入发件箱，使通知发布与业务事实保持一致。
     */
    private void writeNotification(TenantContext context, UUID recipientUserId, String kind,
                                   byte[] sourceEffectKey, UUID sourceRefId,
                                   String title, String body, Instant occurredAt) {
        if (notificationWriter == null || recipientUserId == null) {
            return;
        }
        var ticket = ticketMapper.findTicket(context.tenantId(), sourceRefId).orElse(null);
        if (ticket != null) {
            body = ticket.ticketNo() + " · " + ticket.subject() + "\n" + body;
        }
        notificationWriter.write(context.tenantId(), TicketNotificationIntent.create(
                context.tenantId(), recipientUserId, kind, sourceEffectKey,
                "TICKET", sourceRefId, title, body, context.correlationId(), null, occurredAt));
    }

    /**
     * 计算规范请求摘要，用于判别幂等键是否对应同一业务意图。
     */
    private static String requestDigest(String canonicalIntent) {
        return IdempotencyCodec.requestDigestHex(canonicalIntent);
    }

    /**
     * 计算输入内容 SHA-256 摘要，供消息完整性和业务意图核验。
     */
    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * 工单流转重放的操作契约，定义动作与对应事实的核验方式。
     */
    private record TicketReplayContract(String actionCode, String eventType,
                                        String relatedRefType, UUID relatedRefId) {
    }

    /**
     * 创建幂等锚点集合，集中核验工单、时间线和审计是否完整一致。
     */
    private record CreateReplayAnchors(Optional<TicketRow> byId,
                                       Optional<TicketRow> byIntent,
                                       Optional<TimelineRow> timeline,
                                       Optional<AuditRow> audit) {
        /**
         * 判断创建幂等锚点是否全部缺失，区分首次请求与既有执行事实。
         */
        private boolean empty() {
            return byId.isEmpty() && byIntent.isEmpty() && timeline.isEmpty() && audit.isEmpty();
        }

        /**
         * 判断创建幂等锚点是否齐全，完整事实才可作为成功重放依据。
         */
        private boolean complete() {
            return byId.isPresent() && byIntent.isPresent()
                    && timeline.isPresent() && audit.isPresent();
        }

        /**
         * 判断是否仅存在部分幂等锚点，识别可能的一致性问题。
         */
        private boolean partial() {
            return !empty() && !complete();
        }
    }

    /**
     * 工单插入唯一约束竞争信号，保留尝试工单号以便分类冲突。
     */
    private static final class TicketInsertDuplicateException extends RuntimeException {
        private final String ticketNo;

        /**
         * 构造工单异常，保存稳定错误信息及可用的原始原因，供上层分类处理。
         * 唯一约束竞争时回读既有事实并核验内容，避免重复产生业务效果。
         */
        private TicketInsertDuplicateException(String ticketNo, DuplicateKeyException cause) {
            super("Ticket insert violated a unique constraint", cause);
            this.ticketNo = ticketNo;
        }

        /**
         * 返回本次尝试工单号，供唯一约束竞争后区分编号冲突。
         */
        private String ticketNo() {
            return ticketNo;
        }
    }

    /**
     * 工单命令写入竞争信号，供外层回读并区分重放与版本冲突。
     */
    private static final class CommandWriteRaceException extends RuntimeException {
        /**
         * 私有构造方法，阻止工具类实例化，调用方应使用静态入口。
         */
        private CommandWriteRaceException() {
            super("Ticket command lost the optimistic write race");
        }
    }







}
