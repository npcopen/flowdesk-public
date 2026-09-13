package com.flowdesk.ticket.service;

import com.flowdesk.common.authz.PermissionCode;
import com.flowdesk.common.tenant.TenantContext;
import com.flowdesk.common.web.ApiException;
import com.flowdesk.common.web.AsyncProgress;
import com.flowdesk.ticket.api.TicketDtos;
import com.flowdesk.ticket.event.TicketEventContract;
import com.flowdesk.ticket.event.WorkflowStartV1PayloadCodec;
import com.flowdesk.ticket.iam.AssigneeEligibilityChecker;
import com.flowdesk.ticket.notification.TicketNotificationIntent;
import com.flowdesk.ticket.notification.TicketNotificationOutboxWriter;
import com.flowdesk.ticket.repository.TicketRepository;
import com.flowdesk.ticket.sla.SlaTargetQualificationService;
import com.flowdesk.ticket.sla.SlaTargetConfigurationGate;
import com.flowdesk.ticket.sla.SlaIndexProjection;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * API-021..033 manual ticket lifecycle, state machine, optimistic concurrency,
 * comments, timeline and SLA evidence. API-024/026 perform the IAM eligibility
 * pre-check outside the MySQL write transaction.
 */
@Service
public class TicketService {

    private static final DateTimeFormatter TICKET_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private static final Set<String> TICKET_STATUSES = Set.of(
            "AWAITING_APPROVAL", "APPROVAL_REJECTED", "PENDING_ASSIGNMENT", "ASSIGNED",
            "IN_PROGRESS", "RESOLVED", "CLOSED");
    private static final Set<String> TICKET_SORTS = Set.of(
            "-updatedAt", "updatedAt", "-createdAt", "createdAt", "ticketNo");
    private static final Set<String> COMMENT_SORTS = Set.of("createdAt", "-createdAt");
    private static final Set<String> TIMELINE_SORTS = Set.of("occurredAt", "-occurredAt");
    private static final Set<String> TIMELINE_EVENTS = Set.of(
            "CREATED", "APPROVAL_REQUESTED", "APPROVAL_PASSED", "APPROVAL_REJECTED",
            "ASSIGNED", "ACCEPTED", "TRANSFERRED", "COMMENT_ADDED", "RESOLVED", "CLOSED",
            "REOPENED", "INCIDENT_RECOVERED", "SLA_REMINDER_TRIGGERED",
            "SLA_BREACH_TRIGGERED", "SLA_SATISFIED_ON_TIME", "SLA_SATISFIED_LATE");

    private final TicketRepository repository;
    private final TicketCursorTokenService cursorTokenService;
    private final AssigneeEligibilityChecker eligibilityChecker;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final TicketNotificationOutboxWriter notificationWriter;
    private final SlaTargetQualificationService slaQualificationService;
    private final SlaTargetConfigurationGate slaTargetConfigurationGate;
    private final SlaIndexProjection slaIndexProjection;

    public TicketService(TicketRepository repository,
                         TicketCursorTokenService cursorTokenService,
                         AssigneeEligibilityChecker eligibilityChecker,
                         PlatformTransactionManager transactionManager) {
        this(repository, cursorTokenService, eligibilityChecker, transactionManager,
                Clock.systemUTC(), null, null, null);
    }

    public TicketService(TicketRepository repository,
                         TicketCursorTokenService cursorTokenService,
                         AssigneeEligibilityChecker eligibilityChecker,
                         PlatformTransactionManager transactionManager,
                         Clock clock,
                         TicketNotificationOutboxWriter notificationWriter) {
        this(repository, cursorTokenService, eligibilityChecker, transactionManager,
                clock, notificationWriter, null, null);
    }

    public TicketService(TicketRepository repository,
                         TicketCursorTokenService cursorTokenService,
                         AssigneeEligibilityChecker eligibilityChecker,
                         PlatformTransactionManager transactionManager,
                         Clock clock,
                         TicketNotificationOutboxWriter notificationWriter,
                         SlaTargetQualificationService slaQualificationService) {
        this(repository, cursorTokenService, eligibilityChecker, transactionManager, clock,
                notificationWriter, slaQualificationService, null, null);
    }

    public TicketService(TicketRepository repository,
                         TicketCursorTokenService cursorTokenService,
                         AssigneeEligibilityChecker eligibilityChecker,
                         PlatformTransactionManager transactionManager,
                         Clock clock,
                         TicketNotificationOutboxWriter notificationWriter,
                         SlaTargetQualificationService slaQualificationService,
                         SlaTargetConfigurationGate slaTargetConfigurationGate) {
        this(repository, cursorTokenService, eligibilityChecker, transactionManager, clock,
                notificationWriter, slaQualificationService, slaTargetConfigurationGate, null);
    }

    @Autowired
    public TicketService(TicketRepository repository,
                         TicketCursorTokenService cursorTokenService,
                         AssigneeEligibilityChecker eligibilityChecker,
                         PlatformTransactionManager transactionManager,
                         Clock clock,
                         TicketNotificationOutboxWriter notificationWriter,
                         SlaTargetQualificationService slaQualificationService,
                         SlaTargetConfigurationGate slaTargetConfigurationGate,
                         SlaIndexProjection slaIndexProjection) {
        this.repository = repository;
        this.cursorTokenService = cursorTokenService;
        this.eligibilityChecker = eligibilityChecker;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = Objects.requireNonNull(clock, "clock");
        this.notificationWriter = notificationWriter;
        this.slaQualificationService = slaQualificationService;
        this.slaTargetConfigurationGate = slaTargetConfigurationGate;
        this.slaIndexProjection = slaIndexProjection;
    }

    // ----- create ---------------------------------------------------------------

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

        TicketRepository.CatalogItemRow catalog = repository
                .findCatalogItem(context.tenantId(), request.catalogItemId())
                .filter(item -> "ACTIVE".equals(item.status()))
                .orElseThrow(() -> ApiException.conflict("CATALOG_ITEM_NOT_AVAILABLE",
                        "目录项不存在、不可见或非 ACTIVE。"));
        if (request.previousAttemptTicketId() != null) {
            TicketRepository.TicketRow previous = repository.findTicket(
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
            TicketRepository.TicketRow created = transactionTemplate.execute(statusTx -> {
                String ticketNo = nextTicketNo(context.tenantId(), now);
                try {
                    repository.insertTicket(context.tenantId(), stableTicketId, ticketNo, effectKey,
                            "MANUAL", catalog.catalogItemId(), TicketSupport.actorId(),
                            request.previousAttemptTicketId(), request.subject(), request.description(),
                            initialStatus, null, catalog.workflowDefinitionId(), catalog.workflowVersionId(),
                            catalog.workflowVersionNo(), firstProcessableAt, TicketSupport.actorId(),
                            context.correlationId(), now);
                } catch (DuplicateKeyException e) {
                    throw new TicketInsertDuplicateException(ticketNo, e);
                }
                String createdSummary = truncate("工单已创建：" + request.subject(), 1000);
                repository.insertTimeline(context.tenantId(), UUID.randomUUID(), stableTicketId,
                        effectKey, "CREATED", createdSummary, null, null, "USER",
                        TicketSupport.actorId(), now, context.correlationId());
                repository.insertAudit(context.tenantId(), auditId, "TICKET_CREATE", "TICKET",
                        stableTicketId, "SUCCEEDED", "USER", TicketSupport.actorId(), digest,
                        context.correlationId(), now);
                if (!highRisk) {
                    insertSlaClocks(context, stableTicketId, catalog.slaPolicyId(), now);
                } else {
                    insertWorkflowStartRequest(context, stableTicketId, catalog, now);
                }
                return repository.findTicket(context.tenantId(), stableTicketId).orElseThrow();
            });
            List<AsyncProgress> asyncEffects = highRisk
                    ? List.of(new AsyncProgress("WORKFLOW_START", "REQUESTED", null))
                    : List.of();
            return new CreateResult(toResponse(created), false, asyncEffects);
        } catch (TicketInsertDuplicateException e) {
            return resolveCreateDuplicate(context, effectKey, auditId, digest, stableTicketId,
                    request, e.ticketNo());
        } catch (DuplicateKeyException e) {
            return resolveCreateDuplicate(context, effectKey, auditId, digest, stableTicketId,
                    request, null);
        }
    }

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
                && repository.findTicketByNumber(context.tenantId(), attemptedTicketNo).isPresent()) {
            throw ApiException.conflict("TICKET_NUMBER_CONFLICT",
                    "工单编号与既有工单冲突；事务已回滚，请重试。");
        }
        throw unrelatedConstraintConflict();
    }

    private Optional<CreateResult> replayCreate(TenantContext context, byte[] effectKey,
                                                UUID auditId, String digest, UUID stableTicketId,
                                                TicketDtos.TicketCreateRequest request) {
        CreateReplayAnchors anchors = readCreateReplayAnchors(
                context, effectKey, auditId, stableTicketId);
        if (anchors.partial()) {
            // The independent reads can straddle one competing transaction's atomic commit.
            // Once any anchor is visible, one full refresh distinguishes that read skew from
            // a genuinely incomplete persisted effect.
            anchors = readCreateReplayAnchors(context, effectKey, auditId, stableTicketId);
        }
        if (anchors.empty()) {
            return Optional.empty();
        }
        if (!anchors.complete()) {
            throw incompleteIdempotencyAnchor();
        }

        TicketRepository.TicketRow ticket = anchors.byId().orElseThrow();
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
        return Optional.of(new CreateResult(toResponse(ticket), true, List.of()));
    }

    private CreateReplayAnchors readCreateReplayAnchors(
            TenantContext context, byte[] effectKey, UUID auditId, UUID stableTicketId) {
        return new CreateReplayAnchors(
                repository.findTicket(context.tenantId(), stableTicketId),
                repository.findTicketByCreationIntent(context.tenantId(), effectKey),
                repository.findTimelineByEffectKey(context.tenantId(), stableTicketId, effectKey),
                repository.findAudit(context.tenantId(), auditId));
    }

    // ----- list / get --------------------------------------------------------------

    public PageResult<TicketDtos.TicketResponse> list(String status, String originType,
                                                      UUID catalogItemId, Instant createdFrom,
                                                      Instant createdTo, String scope,
                                                      int pageSize, String pageToken, String sort) {
        TenantContext context = TicketSupport.context();
        RoleScope roleScope = resolveRoleScope();
        String requestedScope = normalizeListScope(roleScope, scope);
        TicketRepository.Scope dbScope = switch (requestedScope) {
            case "mine" -> TicketRepository.Scope.REQUESTER;
            case "assigned" -> TicketRepository.Scope.ASSIGNEE;
            case "visible" -> TicketRepository.Scope.VISIBLE;
            default -> TicketRepository.Scope.TENANT;
        };
        if (status != null) {
            TicketSupport.requireEnumValue(status, TICKET_STATUSES,
                    "INVALID_QUERY_PARAMETER", "status 不是合法 Ticket 状态。");
        }
        if (originType != null) {
            TicketSupport.requireEnumValue(originType, Set.of("MANUAL", "INCIDENT"),
                    "INVALID_QUERY_PARAMETER", "originType 只接受 MANUAL/INCIDENT。");
        }
        String normalizedSort = normalizeSort(sort, TICKET_SORTS, "-updatedAt");
        boolean ascending = !normalizedSort.startsWith("-");
        String sortColumn = ascending ? normalizedSort : normalizedSort.substring(1);
        String cursorScope = "tickets|" + roleScope.name() + "|" + requestedScope + "|" + status
                + "|" + originType + "|" + catalogItemId + "|" + createdFrom + "|" + createdTo;
        TicketCursorTokenService.Cursor cursor = cursorTokenService.decode(
                pageToken, context.tenantId(), cursorScope, pageSize, normalizedSort);
        Object cursorValue = decodeCursorValue(cursor, sortColumn);
        List<TicketRepository.TicketRow> rows = repository.findTickets(context.tenantId(), dbScope,
                TicketSupport.actorId(), status, originType, catalogItemId, createdFrom, createdTo,
                sortColumn, ascending, cursorValue, cursor == null ? null : cursor.lastId(), pageSize);
        boolean hasMore = rows.size() > pageSize;
        if (hasMore) {
            rows = rows.subList(0, pageSize);
        }
        List<TicketDtos.TicketResponse> items = rows.stream().map(this::toResponse).toList();
        String nextToken = null;
        if (hasMore && !items.isEmpty()) {
            TicketRepository.TicketRow last = rows.get(rows.size() - 1);
            nextToken = cursorTokenService.encode(context.tenantId(), cursorScope, pageSize,
                    normalizedSort, cursorString(last, sortColumn), last.ticketId());
        }
        return new PageResult<>(items, nextToken, normalizedSort);
    }

    public Map<String, Long> summary() {
        TicketRepository.Scope scope = switch (resolveRoleScope()) {
            case REQUESTER -> TicketRepository.Scope.REQUESTER;
            case AGENT -> TicketRepository.Scope.ASSIGNEE;
            case MIXED -> TicketRepository.Scope.VISIBLE;
            case ADMIN -> TicketRepository.Scope.TENANT;
        };
        return Map.of("pendingCount", repository.countPendingTickets(
                TicketSupport.context().tenantId(), scope, TicketSupport.actorId()));
    }

    public Map<String, UUID> ticketForSlaClock(UUID clockId) {
        var clock = repository.findSlaClockById(TicketSupport.context().tenantId(), clockId)
                .orElseThrow(() -> ApiException.notFound("RESOURCE_NOT_FOUND", "相关工单不可见。"));
        findVisibleTicket(clock.ticketId());
        return Map.of("ticketId", clock.ticketId());
    }

    public TicketDtos.TicketResponse get(UUID ticketId) {
        TicketRepository.TicketRow row = findVisibleTicket(ticketId);
        return toResponse(row);
    }

    // ----- assign -------------------------------------------------------------------

    public TransitionResult assign(UUID ticketId, TicketDtos.AssignmentCommand request,
                                   String idempotencyKey, String ifMatch) {
        TenantContext context = TicketSupport.context();
        TicketSupport.require(PermissionCode.TICKET_ASSIGN_TENANT);
        String key = TicketSupport.requireIdempotencyKey(idempotencyKey);
        TicketRepository.TicketRow existing = repository.findTicket(context.tenantId(), ticketId)
                .orElseThrow(() -> ApiException.notFound("RESOURCE_NOT_FOUND", "工单不存在。"));

        byte[] effectKey = IdempotencyCodec.effectKey(context, "API-024", ticketId.toString(), key);
        String digest = requestDigest(CanonicalIntent.of(
                "ticketId", ticketId.toString(),
                "assigneeUserId", request.assigneeUserId().toString()));
        TicketReplayContract replayContract = new TicketReplayContract(
                "TICKET_ASSIGN", "ASSIGNED", "USER", request.assigneeUserId());
        Optional<TicketRepository.TicketRow> replayed = replayTicket(
                context, ticketId, effectKey, digest, replayContract);
        if (replayed.isPresent()) {
            return new TransitionResult(toResponse(replayed.get()), true);
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
            TicketRepository.TicketRow updated = transactionTemplate.execute(tx -> {
                if (repository.updateTicketAssign(context.tenantId(), ticketId, expected,
                        request.assigneeUserId(), TicketSupport.actorId(), now) != 1) {
                    throw new CommandWriteRaceException();
                }
                repository.insertTimeline(context.tenantId(), UUID.randomUUID(), ticketId, effectKey,
                        "ASSIGNED", "工单已分配。", "USER", request.assigneeUserId(), "USER",
                        TicketSupport.actorId(), now, context.correlationId());
                repository.insertAudit(context.tenantId(), IdempotencyCodec.uuidFromDigest(effectKey),
                        "TICKET_ASSIGN", "TICKET", ticketId, "SUCCEEDED", "USER",
                        TicketSupport.actorId(), digest, context.correlationId(), now);
                writeNotification(context, request.assigneeUserId(), TicketNotificationIntent.ASSIGNED,
                        effectKey, ticketId, "工单已分配", "有一张工单已分配给你。", now);
                return repository.findTicket(context.tenantId(), ticketId).orElseThrow();
            });
            return new TransitionResult(toResponse(updated), false);
        } catch (CommandWriteRaceException e) {
            return replayRaceOrVersionConflict(context, ticketId, effectKey, digest, replayContract);
        } catch (DuplicateKeyException e) {
            return replayTransitionDuplicate(context, ticketId, effectKey, digest, replayContract);
        }
    }

    // ----- accept ---------------------------------------------------------------------

    public TransitionResult accept(UUID ticketId, String idempotencyKey, String ifMatch) {
        TenantContext context = TicketSupport.context();
        TicketSupport.require(PermissionCode.TICKET_ACCEPT_ASSIGNED);
        String key = TicketSupport.requireIdempotencyKey(idempotencyKey);
        TicketRepository.TicketRow existing = repository.findTicket(context.tenantId(), ticketId)
                .orElseThrow(() -> ApiException.notFound("RESOURCE_NOT_FOUND", "工单不存在。"));

        byte[] effectKey = IdempotencyCodec.effectKey(context, "API-025", ticketId.toString(), key);
        String digest = requestDigest(CanonicalIntent.of("ticketId", ticketId.toString(), "body", ""));
        TicketReplayContract replayContract = new TicketReplayContract(
                "TICKET_ACCEPT", "ACCEPTED", null, null);
        Optional<TicketRepository.TicketRow> replayed = replayTicket(
                context, ticketId, effectKey, digest, replayContract);
        if (replayed.isPresent()) {
            return new TransitionResult(toResponse(replayed.get()), true);
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
            TicketRepository.TicketRow updated = transactionTemplate.execute(tx -> {
                if (repository.updateTicketAccept(context.tenantId(), ticketId, expected,
                        TicketSupport.actorId(), now) != 1) {
                    throw new CommandWriteRaceException();
                }
                satisfySlaClock(context, ticketId, "FIRST_RESPONSE", now);
                repository.insertTimeline(context.tenantId(), UUID.randomUUID(), ticketId, effectKey,
                        "ACCEPTED", "工单已接单。", null, null, "USER",
                        TicketSupport.actorId(), now, context.correlationId());
                repository.insertAudit(context.tenantId(), IdempotencyCodec.uuidFromDigest(effectKey),
                        "TICKET_ACCEPT", "TICKET", ticketId, "SUCCEEDED", "USER",
                        TicketSupport.actorId(), digest, context.correlationId(), now);
                return repository.findTicket(context.tenantId(), ticketId).orElseThrow();
            });
            return new TransitionResult(toResponse(updated), false);
        } catch (CommandWriteRaceException e) {
            return replayRaceOrVersionConflict(context, ticketId, effectKey, digest, replayContract);
        } catch (DuplicateKeyException e) {
            return replayTransitionDuplicate(context, ticketId, effectKey, digest, replayContract);
        }
    }

    // ----- transfer --------------------------------------------------------------------

    public TransitionResult transfer(UUID ticketId, TicketDtos.TransferCommand request,
                                     String idempotencyKey, String ifMatch) {
        TenantContext context = TicketSupport.context();
        TicketSupport.requireAny(PermissionCode.TICKET_TRANSFER_ASSIGNED,
                PermissionCode.TICKET_TRANSFER_TENANT);
        String key = TicketSupport.requireIdempotencyKey(idempotencyKey);
        TicketRepository.TicketRow existing = repository.findTicket(context.tenantId(), ticketId)
                .orElseThrow(() -> ApiException.notFound("RESOURCE_NOT_FOUND", "工单不存在。"));
        byte[] effectKey = IdempotencyCodec.effectKey(context, "API-026", ticketId.toString(), key);
        String digest = requestDigest(CanonicalIntent.of(
                "ticketId", ticketId.toString(),
                "newAssigneeUserId", request.newAssigneeUserId().toString(),
                "reason", request.reason()));
        TicketReplayContract replayContract = new TicketReplayContract(
                "TICKET_TRANSFER", "TRANSFERRED", "USER", request.newAssigneeUserId());
        Optional<TicketRepository.TicketRow> replayed = replayTicket(
                context, ticketId, effectKey, digest, replayContract);
        if (replayed.isPresent()) {
            return new TransitionResult(toResponse(replayed.get()), true);
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
            TicketRepository.TicketRow updated = transactionTemplate.execute(tx -> {
                if (repository.updateTicketTransfer(context.tenantId(), ticketId, expected,
                        request.newAssigneeUserId(), TicketSupport.actorId(), now) != 1) {
                    throw new CommandWriteRaceException();
                }
                repository.insertTimeline(context.tenantId(), UUID.randomUUID(), ticketId, effectKey,
                        "TRANSFERRED", "工单已转派。", "USER", request.newAssigneeUserId(), "USER",
                        TicketSupport.actorId(), now, context.correlationId());
                repository.insertAudit(context.tenantId(), IdempotencyCodec.uuidFromDigest(effectKey),
                        "TICKET_TRANSFER", "TICKET", ticketId, "SUCCEEDED", "USER",
                        TicketSupport.actorId(), digest, context.correlationId(), now);
                writeNotification(context, request.newAssigneeUserId(), TicketNotificationIntent.TRANSFERRED,
                        effectKey, ticketId, "工单已转派", "有一张工单已转派给你。", now);
                return repository.findTicket(context.tenantId(), ticketId).orElseThrow();
            });
            return new TransitionResult(toResponse(updated), false);
        } catch (CommandWriteRaceException e) {
            return replayRaceOrVersionConflict(context, ticketId, effectKey, digest, replayContract);
        } catch (DuplicateKeyException e) {
            return replayTransitionDuplicate(context, ticketId, effectKey, digest, replayContract);
        }
    }

    // ----- comments --------------------------------------------------------------------

    public PageResult<TicketDtos.TicketCommentResponse> comments(UUID ticketId, int pageSize,
                                                                 String pageToken, String sort) {
        TenantContext context = TicketSupport.context();
        findVisibleTicket(ticketId);
        String normalizedSort = normalizeSort(sort, COMMENT_SORTS, "-createdAt");
        boolean ascending = !normalizedSort.startsWith("-");
        String cursorScope = "comments|" + ticketId;
        TicketCursorTokenService.Cursor cursor = cursorTokenService.decode(
                pageToken, context.tenantId(), cursorScope, pageSize, normalizedSort);
        Object cursorValue = cursor == null ? null : Timestamp.from(Instant.parse(cursor.lastValue()));
        List<TicketRepository.CommentRow> rows = repository.findComments(context.tenantId(), ticketId,
                ascending, cursorValue, cursor == null ? null : cursor.lastId(), pageSize);
        boolean hasMore = rows.size() > pageSize;
        if (hasMore) {
            rows = rows.subList(0, pageSize);
        }
        List<TicketDtos.TicketCommentResponse> items = rows.stream().map(this::toResponse).toList();
        String nextToken = null;
        if (hasMore && !items.isEmpty()) {
            TicketRepository.CommentRow last = rows.get(rows.size() - 1);
            nextToken = cursorTokenService.encode(context.tenantId(), cursorScope, pageSize,
                    normalizedSort, last.createdAt().toString(), last.commentId());
        }
        return new PageResult<>(items, nextToken, normalizedSort);
    }

    public CommentResult addComment(UUID ticketId, TicketDtos.CommentCreateRequest request,
                                    String idempotencyKey) {
        TenantContext context = TicketSupport.context();
        TicketSupport.requireAny(PermissionCode.TICKET_COMMENT_OWN, PermissionCode.TICKET_COMMENT_ASSIGNED);
        String key = TicketSupport.requireIdempotencyKey(idempotencyKey);
        TicketRepository.TicketRow ticket = repository.findTicket(context.tenantId(), ticketId)
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
            TicketRepository.CommentRow created = transactionTemplate.execute(tx -> {
                repository.insertComment(context.tenantId(), stableCommentId, ticketId,
                        request.body(), context.correlationId(), TicketSupport.actorId(), now);
                repository.insertTimeline(context.tenantId(), UUID.randomUUID(), ticketId,
                        timelineEffectKey, "COMMENT_ADDED", "新增评论。", "COMMENT", stableCommentId,
                        "USER", TicketSupport.actorId(), now, context.correlationId());
                repository.insertAudit(context.tenantId(), auditId, "TICKET_COMMENT_CREATE",
                        "COMMENT", stableCommentId, "SUCCEEDED", "USER", TicketSupport.actorId(),
                        digest, context.correlationId(), now);
                return repository.findComment(context.tenantId(), stableCommentId).orElseThrow();
            });
            return new CommentResult(toResponse(created), false);
        } catch (DuplicateKeyException e) {
            return replayComment(context, ticketId, stableCommentId, auditId,
                    timelineEffectKey, digest, request.body())
                    .orElseThrow(TicketService::unrelatedConstraintConflict);
        }
    }

    private Optional<CommentResult> replayComment(TenantContext context, UUID ticketId,
                                                  UUID commentId, UUID auditId,
                                                  byte[] timelineEffectKey, String digest,
                                                  String expectedBody) {
        Optional<TicketRepository.CommentRow> comment = repository.findComment(
                context.tenantId(), commentId);
        Optional<TicketRepository.TimelineRow> timeline = repository.findTimelineByEffectKey(
                context.tenantId(), ticketId, timelineEffectKey);
        Optional<TicketRepository.AuditRow> audit = repository.findAudit(context.tenantId(), auditId);
        if (comment.isEmpty() && timeline.isEmpty() && audit.isEmpty()) {
            return Optional.empty();
        }
        if (comment.isEmpty() || timeline.isEmpty() || audit.isEmpty()) {
            throw incompleteIdempotencyAnchor();
        }
        TicketRepository.CommentRow row = comment.get();
        if (!commentId.equals(row.commentId()) || !ticketId.equals(row.ticketId())
                || !expectedBody.equals(row.body())
                || !TicketSupport.actorId().equals(row.authorUserId())) {
            throw reusedIdempotencyKey();
        }
        validateTimeline(timeline.get(), ticketId, timelineEffectKey, "COMMENT_ADDED",
                "COMMENT", commentId, audit.get().correlationId());
        validateAudit(audit.get(), auditId, "TICKET_COMMENT_CREATE", "COMMENT", commentId, digest);
        return Optional.of(new CommentResult(toResponse(row), true));
    }

    // ----- resolve / close / reopen -------------------------------------------------------

    public TransitionResult resolve(UUID ticketId, TicketDtos.ResolveCommand request,
                                    String idempotencyKey, String ifMatch) {
        TenantContext context = TicketSupport.context();
        TicketSupport.require(PermissionCode.TICKET_RESOLVE_ASSIGNED);
        String key = TicketSupport.requireIdempotencyKey(idempotencyKey);
        TicketRepository.TicketRow existing = repository.findTicket(context.tenantId(), ticketId)
                .orElseThrow(() -> ApiException.notFound("RESOURCE_NOT_FOUND", "工单不存在。"));

        byte[] effectKey = IdempotencyCodec.effectKey(context, "API-029", ticketId.toString(), key);
        String digest = requestDigest(CanonicalIntent.of(
                "ticketId", ticketId.toString(),
                "resolutionSummary", request.resolutionSummary()));
        TicketReplayContract replayContract = new TicketReplayContract(
                "TICKET_RESOLVE", "RESOLVED", null, null);
        Optional<TicketRepository.TicketRow> replayed = replayTicket(
                context, ticketId, effectKey, digest, replayContract);
        if (replayed.isPresent()) {
            return new TransitionResult(toResponse(replayed.get()), true);
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
            TicketRepository.TicketRow updated = transactionTemplate.execute(tx -> {
                if (repository.updateTicketResolve(context.tenantId(), ticketId, expected,
                        TicketSupport.actorId(), now) != 1) {
                    throw new CommandWriteRaceException();
                }
                satisfySlaClock(context, ticketId, "RESOLUTION", now);
                repository.insertTimeline(context.tenantId(), UUID.randomUUID(), ticketId, effectKey,
                        "RESOLVED", truncate("工单已解决：" + request.resolutionSummary(), 1000),
                        null, null, "USER", TicketSupport.actorId(), now, context.correlationId());
                repository.insertAudit(context.tenantId(), IdempotencyCodec.uuidFromDigest(effectKey),
                        "TICKET_RESOLVE", "TICKET", ticketId, "SUCCEEDED", "USER",
                        TicketSupport.actorId(), digest, context.correlationId(), now);
                writeNotification(context, existing.requesterUserId(), TicketNotificationIntent.RESOLVED,
                        effectKey, ticketId, "工单已解决", "你提交的工单已解决。", now);
                return repository.findTicket(context.tenantId(), ticketId).orElseThrow();
            });
            return new TransitionResult(toResponse(updated), false);
        } catch (CommandWriteRaceException e) {
            return replayRaceOrVersionConflict(context, ticketId, effectKey, digest, replayContract);
        } catch (DuplicateKeyException e) {
            return replayTransitionDuplicate(context, ticketId, effectKey, digest, replayContract);
        }
    }

    public TransitionResult close(UUID ticketId, TicketDtos.CloseOrReopenCommand request,
                                  String idempotencyKey, String ifMatch) {
        TenantContext context = TicketSupport.context();
        TicketSupport.require(PermissionCode.TICKET_CLOSE_OWN);
        String key = TicketSupport.requireIdempotencyKey(idempotencyKey);
        TicketRepository.TicketRow existing = repository.findTicket(context.tenantId(), ticketId)
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
        Optional<TicketRepository.TicketRow> replayed = replayTicket(
                context, ticketId, effectKey, digest, replayContract);
        if (replayed.isPresent()) {
            return new TransitionResult(toResponse(replayed.get()), true);
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
            TicketRepository.TicketRow updated = transactionTemplate.execute(tx -> {
                if (repository.updateTicketClose(context.tenantId(), ticketId, expected,
                        TicketSupport.actorId(), now) != 1) {
                    throw new CommandWriteRaceException();
                }
                repository.insertTimeline(context.tenantId(), UUID.randomUUID(), ticketId, effectKey,
                        "CLOSED", request.reason() == null ? "申请人已确认关闭。" : truncate(
                                "申请人已确认关闭：" + request.reason(), 1000), null, null, "USER",
                        TicketSupport.actorId(), now, context.correlationId());
                repository.insertAudit(context.tenantId(), IdempotencyCodec.uuidFromDigest(effectKey),
                        "TICKET_CLOSE", "TICKET", ticketId, "SUCCEEDED", "USER",
                        TicketSupport.actorId(), digest, context.correlationId(), now);
                return repository.findTicket(context.tenantId(), ticketId).orElseThrow();
            });
            return new TransitionResult(toResponse(updated), false);
        } catch (CommandWriteRaceException e) {
            return replayRaceOrVersionConflict(context, ticketId, effectKey, digest, replayContract);
        } catch (DuplicateKeyException e) {
            return replayTransitionDuplicate(context, ticketId, effectKey, digest, replayContract);
        }
    }

    public TransitionResult reopen(UUID ticketId, TicketDtos.CloseOrReopenCommand request,
                                   String idempotencyKey, String ifMatch) {
        TenantContext context = TicketSupport.context();
        TicketSupport.require(PermissionCode.TICKET_REOPEN_OWN);
        String key = TicketSupport.requireIdempotencyKey(idempotencyKey);
        TicketRepository.TicketRow existing = repository.findTicket(context.tenantId(), ticketId)
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
        Optional<TicketRepository.TicketRow> replayed = replayTicket(
                context, ticketId, effectKey, digest, replayContract);
        if (replayed.isPresent()) {
            return new TransitionResult(toResponse(replayed.get()), true);
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
            TicketRepository.TicketRow updated = transactionTemplate.execute(tx -> {
                if (repository.updateTicketReopen(context.tenantId(), ticketId, expected,
                        TicketSupport.actorId(), now) != 1) {
                    throw new CommandWriteRaceException();
                }
                repository.insertTimeline(context.tenantId(), UUID.randomUUID(), ticketId, effectKey,
                        "REOPENED", truncate("申请人已重新打开：" + request.reason(), 1000),
                        null, null, "USER", TicketSupport.actorId(), now, context.correlationId());
                var previousClock = repository.findSlaClockForUpdate(context.tenantId(), ticketId, "RESOLUTION")
                        .orElseThrow(() -> new IllegalStateException("Resolved ticket is missing its resolution clock"));
                int cycleNo = existing.reopenCount() + 1;
                UUID reopenedClockId = uuidFromKey(IdempotencyCodec.derivedKey(
                        "FD-TICKET-SLA-REOPEN-V1", context.tenantId(), ticketId, cycleNo));
                Instant deadline = now.plusSeconds(previousClock.policyDurationSeconds());
                Instant reminder = previousClock.policyReminderBeforeSeconds() == null ? null
                        : deadline.minusSeconds(previousClock.policyReminderBeforeSeconds());
                repository.insertSlaClock(context.tenantId(), reopenedClockId, ticketId,
                        previousClock.slaPolicyId(), "RESOLUTION", now, deadline, reminder,
                        previousClock.policyDurationSeconds(), previousClock.policyReminderBeforeSeconds(), cycleNo, now);
                scheduleSlaIndexProjection(context.tenantId(), reopenedClockId);
                writeNotification(context, existing.assigneeUserId(), TicketNotificationIntent.REOPENED,
                        effectKey, ticketId, "工单已重新打开", "申请人反馈问题仍未解决，请继续处理。原因：" + request.reason(), now);
                repository.insertAudit(context.tenantId(), IdempotencyCodec.uuidFromDigest(effectKey),
                        "TICKET_REOPEN", "TICKET", ticketId, "SUCCEEDED", "USER",
                        TicketSupport.actorId(), digest, context.correlationId(), now);
                return repository.findTicket(context.tenantId(), ticketId).orElseThrow();
            });
            return new TransitionResult(toResponse(updated), false);
        } catch (CommandWriteRaceException e) {
            return replayRaceOrVersionConflict(context, ticketId, effectKey, digest, replayContract);
        } catch (DuplicateKeyException e) {
            return replayTransitionDuplicate(context, ticketId, effectKey, digest, replayContract);
        }
    }

    // ----- timeline / SLA -------------------------------------------------------------------

    public PageResult<TicketDtos.TimelineEntryResponse> timeline(UUID ticketId, String eventType,
                                                                 Instant occurredFrom, Instant occurredTo,
                                                                 int pageSize, String pageToken,
                                                                 String sort) {
        TenantContext context = TicketSupport.context();
        findVisibleTicket(ticketId);
        if (eventType != null) {
            TicketSupport.requireEnumValue(eventType, TIMELINE_EVENTS,
                    "INVALID_QUERY_PARAMETER", "eventType 不是合法时间线事件。");
        }
        String normalizedSort = normalizeSort(sort, TIMELINE_SORTS, "occurredAt");
        boolean ascending = !normalizedSort.startsWith("-");
        String cursorScope = "timeline|" + ticketId + "|" + eventType + "|" + occurredFrom + "|" + occurredTo;
        TicketCursorTokenService.Cursor cursor = cursorTokenService.decode(
                pageToken, context.tenantId(), cursorScope, pageSize, normalizedSort);
        Object cursorValue = cursor == null ? null : Timestamp.from(Instant.parse(cursor.lastValue()));
        List<TicketRepository.TimelineRow> rows = repository.findTimelineEntries(context.tenantId(),
                ticketId, eventType, occurredFrom, occurredTo, ascending, cursorValue,
                cursor == null ? null : cursor.lastId(), pageSize);
        boolean hasMore = rows.size() > pageSize;
        if (hasMore) {
            rows = rows.subList(0, pageSize);
        }
        List<TicketDtos.TimelineEntryResponse> items = rows.stream().map(this::toResponse).toList();
        String nextToken = null;
        if (hasMore && !items.isEmpty()) {
            TicketRepository.TimelineRow last = rows.get(rows.size() - 1);
            nextToken = cursorTokenService.encode(context.tenantId(), cursorScope, pageSize,
                    normalizedSort, last.occurredAt().toString(), last.timelineId());
        }
        return new PageResult<>(items, nextToken, normalizedSort);
    }

    public TicketDtos.SlaStatusResponse slaStatus(UUID ticketId) {
        TenantContext context = TicketSupport.context();
        findVisibleTicket(ticketId);
        List<TicketRepository.SlaClockRow> clocks = repository.findSlaClocks(
                context.tenantId(), ticketId);
        if (clocks.isEmpty()) {
            throw ApiException.conflict("SLA_NOT_STARTED", "Ticket 尚未启动 SLA 时钟。");
        }
        Map<UUID, List<TicketRepository.SlaTriggerRow>> triggersByClock = new java.util.HashMap<>();
        for (TicketRepository.SlaTriggerRow trigger : repository.findSlaTriggers(context.tenantId(),
                clocks.stream().map(TicketRepository.SlaClockRow::slaClockId).toList())) {
            triggersByClock.computeIfAbsent(trigger.slaClockId(), k -> new ArrayList<>()).add(trigger);
        }
        List<TicketDtos.SlaClockStatus> responses = clocks.stream()
                .map(clock -> toResponse(clock, triggersByClock.getOrDefault(clock.slaClockId(), List.of())))
                .toList();
        int currentCycle = clocks.stream().mapToInt(TicketRepository.SlaClockRow::cycleNo).max().orElse(0);
        return new TicketDtos.SlaStatusResponse(ticketId,
                responses.stream().filter(row -> row.cycleNo() == currentCycle).toList(),
                responses.stream().filter(row -> row.cycleNo() < currentCycle).toList());
    }

    public TicketDtos.WorkflowStartStatusResponse workflowStartStatus(UUID ticketId) {
        TenantContext context = TicketSupport.context();
        findVisibleTicket(ticketId);
        TicketRepository.WorkflowStartRow row = repository.findWorkflowStartLink(
                        context.tenantId(), ticketId)
                .orElseThrow(() -> ApiException.notFound("RESOURCE_NOT_FOUND", "工单审批启动状态不存在。"));
        return new TicketDtos.WorkflowStartStatusResponse(row.ticketId(), row.status(),
                row.workflowInstanceId(), row.requestedAt(), row.establishedAt(),
                row.recoveryRequiredAt(), row.recoveryReasonCode(), row.retryCount(),
                row.lastAttemptAt(), row.lockVersion(), row.createdAt(), row.updatedAt());
    }

    // ----- helpers -------------------------------------------------------------------------------

    private TicketRepository.TicketRow findVisibleTicket(UUID ticketId) {
        TenantContext context = TicketSupport.context();
        TicketRepository.TicketRow row = repository.findTicket(context.tenantId(), ticketId)
                .orElseThrow(() -> ApiException.notFound("RESOURCE_NOT_FOUND", "工单不存在。"));
        if (!canView(context, row)) {
            throw ApiException.notFound("RESOURCE_NOT_FOUND", "工单不存在。");
        }
        return row;
    }

    private boolean canView(TenantContext context, TicketRepository.TicketRow row) {
        if (TicketSupport.hasAny(PermissionCode.TICKET_READ_TENANT)) {
            return true;
        }
        if (TicketSupport.hasAny(PermissionCode.TICKET_READ_OWN)
                && TicketSupport.actorId().equals(row.requesterUserId())) {
            return true;
        }
        return TicketSupport.hasAny(PermissionCode.TICKET_READ_ASSIGNED)
                && TicketSupport.actorId().equals(row.assigneeUserId());
    }

    private RoleScope resolveRoleScope() {
        if (TicketSupport.hasAny(PermissionCode.TICKET_READ_TENANT)) {
            return RoleScope.ADMIN;
        }
        if (TicketSupport.hasAny(PermissionCode.TICKET_READ_ASSIGNED) && TicketSupport.hasAny(PermissionCode.TICKET_READ_OWN)) return RoleScope.MIXED;
        if (TicketSupport.hasAny(PermissionCode.TICKET_READ_ASSIGNED)) {
            return RoleScope.AGENT;
        }
        if (TicketSupport.hasAny(PermissionCode.TICKET_READ_OWN)) {
            return RoleScope.REQUESTER;
        }
        throw ApiException.forbidden("ACCESS_DENIED", "当前角色或权限不足。");
    }

    private String normalizeListScope(RoleScope roleScope, String scope) {
        if (scope == null) {
            return roleScope.defaultScope();
        }
        if (roleScope == RoleScope.ADMIN && Set.of("tenant", "mine", "assigned").contains(scope)) return scope;
        if (roleScope == RoleScope.MIXED && Set.of("visible", "mine", "assigned").contains(scope)) return scope;
        String expected = switch (roleScope) {
            case MIXED -> "visible";
            case REQUESTER -> "mine";
            case AGENT -> "assigned";
            case ADMIN -> "tenant";
        };
        if (!expected.equals(scope)) {
            throw ApiException.forbidden("TICKET_SCOPE_FORBIDDEN", "不能扩大当前角色的 Ticket 列表范围。");
        }
        return scope;
    }

    private Optional<TicketRepository.TicketRow> replayTicket(
            TenantContext context, UUID ticketId, byte[] effectKey, String digest,
            TicketReplayContract contract) {
        UUID auditId = IdempotencyCodec.uuidFromDigest(effectKey);
        Optional<TicketRepository.TimelineRow> timeline = repository.findTimelineByEffectKey(
                context.tenantId(), ticketId, effectKey);
        Optional<TicketRepository.AuditRow> audit = repository.findAudit(context.tenantId(), auditId);
        if (timeline.isEmpty() && audit.isEmpty()) {
            return Optional.empty();
        }
        if (timeline.isEmpty() || audit.isEmpty()) {
            throw incompleteIdempotencyAnchor();
        }

        TicketRepository.TicketRow ticket = repository.findTicket(context.tenantId(), ticketId)
                .orElseThrow(TicketService::incompleteIdempotencyAnchor);
        validateTimeline(timeline.get(), ticketId, effectKey, contract.eventType(),
                contract.relatedRefType(), contract.relatedRefId(), audit.get().correlationId());
        validateAudit(audit.get(), auditId, contract.actionCode(), "TICKET", ticketId, digest);
        return Optional.of(ticket);
    }

    private TransitionResult replayRaceOrVersionConflict(
            TenantContext context, UUID ticketId, byte[] effectKey, String digest,
            TicketReplayContract contract) {
        return replayTicket(context, ticketId, effectKey, digest, contract)
                .map(row -> new TransitionResult(toResponse(row), true))
                .orElseThrow(() -> versionConflict(context, ticketId));
    }

    private TransitionResult replayTransitionDuplicate(
            TenantContext context, UUID ticketId, byte[] effectKey, String digest,
            TicketReplayContract contract) {
        TicketRepository.TicketRow replayed = replayTicket(
                context, ticketId, effectKey, digest, contract)
                .orElseThrow(TicketService::unrelatedConstraintConflict);
        return new TransitionResult(toResponse(replayed), true);
    }

    private void validateTimeline(TicketRepository.TimelineRow timeline, UUID ticketId,
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

    private void validateAudit(TicketRepository.AuditRow audit, UUID auditId,
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

    private static ApiException incompleteIdempotencyAnchor() {
        return ApiException.conflict("IDEMPOTENCY_KEY_REUSED",
                "幂等锚点不完整或成功业务效果无法确认，拒绝重放。");
    }

    private static ApiException reusedIdempotencyKey() {
        return ApiException.conflict("IDEMPOTENCY_KEY_REUSED",
                "同一 Idempotency-Key 已用于不同业务意图或锚点不一致。");
    }

    private static ApiException unrelatedConstraintConflict() {
        return ApiException.conflict("RESOURCE_ALREADY_EXISTS",
                "数据唯一约束冲突，且无法确认属于当前幂等请求。");
    }

    private ApiException versionConflict(TenantContext context, UUID ticketId) {
        TicketRepository.TicketRow refreshed = repository.findTicket(context.tenantId(), ticketId)
                .orElseThrow(() -> ApiException.notFound("RESOURCE_NOT_FOUND", "工单不存在。"));
        return TicketSupport.versionConflict(TicketSupport.etagHeaders(refreshed.lockVersion()));
    }

    /**
     * Applies a due SLA candidate in one short transaction.  The caller must have
     * installed a persisted-task TenantContext; the Ticket row is deliberately
     * locked before the Clock row so scanner and user actions share one order.
     */
    public SlaProcessResult processSlaCandidate(UUID tenantId, UUID ticketId, UUID slaClockId,
                                                String stage, Instant suppliedNow) {
        TenantContext context = TicketSupport.context();
        context.requireTenant(tenantId);
        if (ticketId == null || slaClockId == null
                || !("REMINDER".equals(stage) || "BREACH".equals(stage))) {
            return new SlaProcessResult(false, false, "INVALID_CANDIDATE");
        }
        Instant now = suppliedNow == null ? now() : suppliedNow.truncatedTo(ChronoUnit.MICROS);
        SlaProcessResult result = transactionTemplate.execute(tx -> {
            TicketRepository.TicketRow ticket = repository.findTicketForUpdate(tenantId, ticketId)
                    .orElse(null);
            if (ticket == null) {
                return new SlaProcessResult(false, false, "TICKET_NOT_FOUND");
            }
            TicketRepository.SlaClockRow clock = repository.findSlaClockByIdForUpdate(
                    tenantId, slaClockId).orElse(null);
            if (clock == null || !ticketId.equals(clock.ticketId())) {
                return new SlaProcessResult(false, false, "CLOCK_NOT_FOUND");
            }
            scheduleSlaIndexProjection(context.tenantId(), clock.slaClockId());
            return triggerSlaStage(context, ticket, clock, stage, now);
        });
        return result == null ? new SlaProcessResult(false, false, "NO_RESULT") : result;
    }

    public void retrySlaNotification(UUID tenantId, UUID ticketId, UUID clockId, String stage, Instant now) {
        var context = TicketSupport.context();
        context.requireTenant(tenantId);
        transactionTemplate.executeWithoutResult(tx -> {
            var ticket = repository.findTicketForUpdate(tenantId, ticketId).orElse(null);
            var clock = repository.findSlaClockByIdForUpdate(tenantId, clockId).orElse(null);
            if (ticket != null && clock != null && ticketId.equals(clock.ticketId())
                    && repository.findSlaTrigger(tenantId, clockId, stage, false).isPresent()) {
                ensureSlaTrigger(context, ticket, clock, stage, now);
            }
        });
    }

    private SlaProcessResult triggerSlaStage(TenantContext context,
                                             TicketRepository.TicketRow ticket,
                                             TicketRepository.SlaClockRow clock,
                                             String stage, Instant now) {
        if (!"RUNNING".equals(clock.status())) {
            if (repository.findSlaTrigger(context.tenantId(), clock.slaClockId(), stage, false).isPresent()) {
                ensureSlaTrigger(context, ticket, clock, stage, now);
            }
            return new SlaProcessResult(false, false, "CLOCK_TERMINAL");
        }
        if ("REMINDER".equals(stage)) {
            if (clock.reminderDueAt() == null || now.isBefore(clock.reminderDueAt())) {
                return new SlaProcessResult(false, false, "REMINDER_NOT_DUE");
            }
            if (now.isAfter(clock.deadlineAt())) {
                appendReminderMissedAudit(context, clock, now);
                return new SlaProcessResult(false, true, "REMINDER_MISSED_AFTER_DEADLINE");
            }
            boolean inserted = ensureSlaTrigger(context, ticket, clock, stage, now);
            return new SlaProcessResult(inserted, inserted, inserted ? "REMINDER_TRIGGERED" : "REMINDER_DUPLICATE");
        }

        // Breach is strict: equality with deadline is still on time.
        if (!now.isAfter(clock.deadlineAt())) {
            return new SlaProcessResult(false, false, "BREACH_NOT_DUE");
        }
        requireOneUpdate(repository.updateSlaClockBreached(context.tenantId(),
                clock.slaClockId(), clock.lockVersion(), now), clock.clockType());
        TicketRepository.SlaClockRow breached = repository.findSlaClockByIdForUpdate(
                context.tenantId(), clock.slaClockId()).orElseThrow(
                        () -> new IllegalStateException("SLA clock disappeared after breach"));
        boolean inserted = ensureSlaTrigger(context, ticket, breached, stage, now);
        return new SlaProcessResult(inserted, inserted, inserted ? "BREACH_TRIGGERED" : "BREACH_DUPLICATE");
    }

    private void satisfySlaClock(TenantContext context, UUID ticketId, String clockType, Instant now) {
        // The caller has already changed/locked the Ticket row.  Lock the Clock
        // second and never use Workflow occurredAt or a client supplied time.
        TicketRepository.SlaClockRow clock = repository.findSlaClockForUpdate(
                        context.tenantId(), ticketId, clockType)
                .orElseThrow(() -> new IllegalStateException("SLA clock is missing: " + clockType));
        scheduleSlaIndexProjection(context.tenantId(), clock.slaClockId());
        if ("SATISFIED_ON_TIME".equals(clock.status()) || "SATISFIED_LATE".equals(clock.status())) {
            return;
        }
        if ("BREACHED".equals(clock.status())) {
            ensureSlaTrigger(context, repository.findTicket(context.tenantId(), ticketId).orElseThrow(),
                    clock, "BREACH", now);
            requireOneUpdate(repository.updateSlaClockSatisfiedLate(context.tenantId(),
                    clock.slaClockId(), clock.lockVersion(), "USER", TicketSupport.actorId(), now), clockType);
            appendSlaMilestoneFacts(context, clock, "SLA_SATISFIED_LATE", "USER",
                    TicketSupport.actorId(), now);
            return;
        }
        if (!now.isAfter(clock.deadlineAt())) {
            requireOneUpdate(repository.updateSlaClockSatisfiedOnTime(context.tenantId(),
                    clock.slaClockId(), clock.lockVersion(), "USER", TicketSupport.actorId(), now), clockType);
            appendSlaMilestoneFacts(context, clock, "SLA_SATISFIED_ON_TIME", "USER",
                    TicketSupport.actorId(), now);
            return;
        }

        // A late user action can itself win the RUNNING -> BREACHED race.  Keep
        // the immutable breach Trigger and then advance to SATISFIED_LATE in the
        // same local transaction; no intermediate commit is observable.
        requireOneUpdate(repository.updateSlaClockBreached(context.tenantId(),
                clock.slaClockId(), clock.lockVersion(), "USER", TicketSupport.actorId(), now), clockType);
        TicketRepository.SlaClockRow breached = repository.findSlaClockByIdForUpdate(
                context.tenantId(), clock.slaClockId()).orElseThrow();
        TicketRepository.TicketRow ticket = repository.findTicket(context.tenantId(), ticketId).orElseThrow();
        ensureSlaTrigger(context, ticket, breached, "BREACH", now);
        requireOneUpdate(repository.updateSlaClockSatisfiedLate(context.tenantId(),
                breached.slaClockId(), breached.lockVersion(), "USER", TicketSupport.actorId(), now), clockType);
        appendSlaMilestoneFacts(context, breached, "SLA_SATISFIED_LATE", "USER",
                TicketSupport.actorId(), now);
    }

    private boolean ensureSlaTrigger(TenantContext context, TicketRepository.TicketRow ticket,
                                     TicketRepository.SlaClockRow clock, String stage, Instant now) {
        scheduleSlaIndexProjection(context.tenantId(), clock.slaClockId());
        byte[] effectKey = IdempotencyCodec.derivedKey(
                "FD-SLA-TRIGGER-V1", context.tenantId(), clock.slaClockId(), stage);
        byte[] publicationKey = IdempotencyCodec.derivedKey(
                "FD-SLA-TRIGGER-PUBLICATION-V1", context.tenantId(), clock.slaClockId(), stage);
        Instant dueAt = "REMINDER".equals(stage) ? clock.reminderDueAt() : clock.deadlineAt();
        if (dueAt == null) {
            return false;
        }
        Optional<TicketRepository.SlaTriggerDetailRow> existing = repository.findSlaTrigger(
                context.tenantId(), clock.slaClockId(), stage, false);
        UUID triggerId = uuidFromKey(effectKey);
        boolean created = existing.isEmpty();
        List<UUID> recipients;
        boolean missingSnapshot = false;
        if (existing.isPresent()) {
            TicketRepository.SlaTriggerDetailRow row = existing.get();
            if (!Arrays.equals(effectKey, row.effectKey())
                    || !Arrays.equals(publicationKey, row.publicationKey())
                    || !dueAt.equals(row.dueAt())) {
                throw new IllegalStateException("SLA trigger identity/content conflict");
            }
            // Preserve an established recipient set. An empty set means delivery
            // is still pending and may be completed when qualification recovers.
            recipients = repository.findSlaTriggerRecipientSnapshot(
                    context.tenantId(), triggerId);
            if (recipients.isEmpty()) {
                recipients = resolveSlaRecipients(context, ticket, clock, stage, now);
                if (!recipients.isEmpty()) repository.insertSlaTriggerRecipientSnapshot(
                        context.tenantId(), triggerId, recipients, "SCHEDULER", null, now);
            }
            missingSnapshot = recipients.isEmpty();
            publicationKey = row.publicationKey();
        }
        else {
            repository.insertSlaTrigger(context.tenantId(), triggerId, clock.slaClockId(),
                    stage, effectKey, dueAt, now, publicationKey);
            // Reminder triggers do not change lifecycle status, so explicitly
            // advance the clock high-water timestamp for rebuild catch-up.
            if ("REMINDER".equals(stage)
                    && repository.updateSlaClockUpdatedAt(context.tenantId(), clock.slaClockId(), now) != 1) {
                throw new IllegalStateException("SLA reminder clock touch changed an unexpected number of rows");
            }
            recipients = resolveSlaRecipients(context, ticket, clock, stage, now);
            if (!recipients.isEmpty()) {
                repository.insertSlaTriggerRecipientSnapshot(context.tenantId(), triggerId,
                        recipients, "SCHEDULER", null, now);
            }
        }

        String event = "REMINDER".equals(stage)
                ? "SLA_REMINDER_TRIGGERED" : "SLA_BREACH_TRIGGERED";
        byte[] timelineKey = IdempotencyCodec.derivedKey(
                "FD-SLA-TRIGGER-TIMELINE-V1", context.tenantId(), clock.slaClockId(), stage);
        if (repository.findTimelineByEffectKey(context.tenantId(), ticket.ticketId(), timelineKey).isEmpty()) {
            repository.insertTimeline(context.tenantId(), uuidFromKey(timelineKey), ticket.ticketId(),
                    timelineKey, event, "SLA " + stage.toLowerCase(Locale.ROOT) + " 已触发。",
                    "SLA_CLOCK", clock.slaClockId(), "SCHEDULER", null, now,
                    context.correlationId(), null, now);
        }
        byte[] auditKey = IdempotencyCodec.derivedKey(
                "FD-SLA-TRIGGER-AUDIT-V1", context.tenantId(), clock.slaClockId(), stage);
        UUID auditId = uuidFromKey(auditKey);
        if (repository.findAudit(context.tenantId(), auditId).isEmpty()) {
            repository.insertAudit(context.tenantId(), auditId,
                    "SLA_" + stage + "_TRIGGERED", "SLA_CLOCK", clock.slaClockId(),
                    "SUCCEEDED", "SCHEDULER", null, "SLA trigger committed", null,
                    context.correlationId(), null, now, now);
        }
        if (missingSnapshot) {
            // The scanner retries this pending delivery independently of clock status.
            appendSlaNotificationSkipAudit(context, clock, stage,
                    "SLA_TRIGGER_RECIPIENT_SNAPSHOT_MISSING", now);
        }
        // The persisted trigger publication key is the immutable root for
        // recipient fan-out.  TicketNotificationIntent derives one child
        // effect/publication identity per concrete recipient from this root.
        if (!recipients.isEmpty()) {
            writeSlaNotifications(context, ticket, clock, stage, publicationKey, recipients,
                    existing.map(TicketRepository.SlaTriggerDetailRow::triggeredAt).orElse(now));
        }
        return created;
    }

    private List<UUID> resolveSlaRecipients(TenantContext context,
                                             TicketRepository.TicketRow ticket,
                                             TicketRepository.SlaClockRow clock,
                                             String stage, Instant now) {
        if (slaTargetConfigurationGate == null) {
            appendSlaNotificationSkipAudit(context, clock, stage,
                    "SLA_TARGET_GATE_UNAVAILABLE", now);
            return List.of();
        }
        // Resolve and lock the concrete target only at trigger creation.  The
        // resulting UserRefs are copied into the immutable trigger snapshot;
        // later target replacement or Ticket reassignment cannot rewrite it.
        SlaTargetConfigurationGate.GateResult gate = slaTargetConfigurationGate
                .evaluate(context.tenantId(), now, true);
        if (!gate.open() || gate.recipientUserId() == null) {
            appendSlaNotificationSkipAudit(context, clock, stage,
                    gate.reasonCode() == null ? "SLA_TARGET_NOT_QUALIFIED" : gate.reasonCode(), now);
            return List.of();
        }
        Set<UUID> recipients = new java.util.LinkedHashSet<>();
        if (ticket.assigneeUserId() != null) {
            recipients.add(ticket.assigneeUserId());
        }
        if ("REMINDER".equals(stage) && recipients.isEmpty()) {
            recipients.add(gate.recipientUserId());
        }
        if ("BREACH".equals(stage)) {
            recipients.add(gate.recipientUserId());
        }
        return List.copyOf(recipients);
    }

    private void appendReminderMissedAudit(TenantContext context,
                                           TicketRepository.SlaClockRow clock, Instant now) {
        UUID auditId = uuidFromKey(IdempotencyCodec.derivedKey(
                "FD-SLA-REMINDER-MISSED-AUDIT-V1", context.tenantId(), clock.slaClockId()));
        if (repository.findAudit(context.tenantId(), auditId).isEmpty()) {
            repository.insertAudit(context.tenantId(), auditId,
                    "SLA_REMINDER_MISSED_AFTER_DEADLINE", "SLA_CLOCK", clock.slaClockId(),
                    "SUCCEEDED", "SCHEDULER", null,
                    "Reminder window closed before the scanner observed it: "
                            + "REMINDER_MISSED_AFTER_DEADLINE", null,
                    context.correlationId(), null, now, now);
        }
    }

    private void appendSlaMilestoneFacts(TenantContext context, TicketRepository.SlaClockRow clock,
                                         String event, String actorType, UUID actorId, Instant now) {
        byte[] effectKey = IdempotencyCodec.derivedKey(
                "FD-SLA-MILESTONE-TIMELINE-V1", context.tenantId(), clock.slaClockId(), event);
        TicketRepository.TicketRow ticket = repository.findTicket(context.tenantId(), clock.ticketId()).orElseThrow();
        if (repository.findTimelineByEffectKey(context.tenantId(), ticket.ticketId(), effectKey).isEmpty()) {
            repository.insertTimeline(context.tenantId(), uuidFromKey(effectKey), ticket.ticketId(),
                    effectKey, event, "SLA 里程碑已接受。", "SLA_CLOCK", clock.slaClockId(),
                    actorType, actorId, now, context.correlationId(), null, now);
        }
        byte[] auditKey = IdempotencyCodec.derivedKey(
                "FD-SLA-MILESTONE-AUDIT-V1", context.tenantId(), clock.slaClockId(), event);
        UUID auditId = uuidFromKey(auditKey);
        if (repository.findAudit(context.tenantId(), auditId).isEmpty()) {
            repository.insertAudit(context.tenantId(), auditId, event, "SLA_CLOCK",
                    clock.slaClockId(), "SUCCEEDED", actorType, actorId,
                    "SLA milestone accepted", null, context.correlationId(), null, now, now);
        }
    }

    private void writeSlaNotifications(TenantContext context, TicketRepository.TicketRow ticket,
                                       TicketRepository.SlaClockRow clock, String stage,
                                       byte[] triggerEffectKey, List<UUID> recipients,
                                       Instant now) {
        if (notificationWriter == null) {
            return;
        }
        for (UUID recipient : recipients) {
            writeNotification(context, recipient,
                    "REMINDER".equals(stage) ? TicketNotificationIntent.SLA_REMINDER
                            : TicketNotificationIntent.SLA_BREACH,
                    triggerEffectKey, clock.slaClockId(),
                    "REMINDER".equals(stage) ? "工单处理期限提醒" : "工单处理已超时",
                    "第 " + (clock.cycleNo() + 1) + " 轮"
                            + ("FIRST_RESPONSE".equals(clock.clockType()) ? "响应" : "解决")
                            + ("REMINDER".equals(stage) ? "已达到提醒时间。" : "已超过处理期限。")
                            + "请查看工单当前状态。", now,
                    "SLA_CLOCK");
        }
    }

    private void appendSlaNotificationSkipAudit(TenantContext context,
                                                TicketRepository.SlaClockRow clock,
                                                String stage, String reason, Instant now) {
        if (clock == null || stage == null) {
            return;
        }
        UUID auditId = uuidFromKey(IdempotencyCodec.derivedKey(
                "FD-SLA-NOTIFICATION-SKIP-AUDIT-V1", context.tenantId(), clock.slaClockId(),
                stage, reason));
        if (repository.findAudit(context.tenantId(), auditId).isEmpty()) {
            repository.insertAudit(context.tenantId(), auditId, "SLA_" + stage + "_NOTIFICATION_SKIPPED",
                    "SLA_CLOCK", clock.slaClockId(), "SUCCEEDED", "SCHEDULER", null,
                    "Notification skipped by configuration: " + reason, null,
                    context.correlationId(), null, now, now);
        }
    }

    private static void requireOneUpdate(int updated, String clockType) {
        if (updated != 1) {
            throw new IllegalStateException("SLA clock transition lost the single-writer race: " + clockType);
        }
    }

    private void insertSlaClocks(TenantContext context, UUID ticketId, UUID slaPolicyId, Instant now) {
        TicketRepository.SlaPolicyRow policy = repository.findSlaPolicy(
                        context.tenantId(), slaPolicyId)
                .orElseThrow(() -> new IllegalStateException("SLA policy is missing"));
        insertSlaClock(context, ticketId, policy, "FIRST_RESPONSE", now, now, policy.firstResponseSeconds(),
                policy.firstResponseReminderBeforeSeconds());
        insertSlaClock(context, ticketId, policy, "RESOLUTION", now, now, policy.resolutionSeconds(),
                policy.resolutionReminderBeforeSeconds());
    }

    private void insertSlaClock(TenantContext context, UUID ticketId, TicketRepository.SlaPolicyRow policy,
                                String clockType, Instant startedAt, Instant now, long durationSeconds,
                                Long reminderBeforeSeconds) {
        Instant deadline = startedAt.plusSeconds(durationSeconds);
        Instant reminderDue = reminderBeforeSeconds == null ? null
                : deadline.minusSeconds(reminderBeforeSeconds);
        UUID clockId = uuidFromKey(IdempotencyCodec.derivedKey(
                "FD-TICKET-SLA-CLOCK-V1", context.tenantId(), ticketId, clockType));
        repository.insertSlaClock(context.tenantId(), clockId, ticketId,
                policy.slaPolicyId(), clockType, startedAt, deadline, reminderDue,
                durationSeconds, reminderBeforeSeconds, now);
        scheduleSlaIndexProjection(context.tenantId(), clockId);
    }

    private void scheduleSlaIndexProjection(UUID tenantId, UUID slaClockId) {
        if (slaIndexProjection != null) {
            slaIndexProjection.schedule(tenantId, slaClockId);
        }
    }

    private void insertWorkflowStartRequest(TenantContext context, UUID ticketId,
                                            TicketRepository.CatalogItemRow catalog, Instant now) {
        byte[] startIntentKey = IdempotencyCodec.derivedKey(
                TicketEventContract.WorkflowStartRequested.BUSINESS_INTENT_KEY_NAMESPACE,
                context.tenantId(), ticketId);
        repository.insertWorkflowStartLink(context.tenantId(), ticketId, startIntentKey, now,
                context.correlationId());
        byte[] bodyBytes = WorkflowStartV1PayloadCodec.encode(
                ticketId,
                catalog.workflowDefinitionId(),
                catalog.workflowVersionId(),
                catalog.workflowVersionNo(),
                TicketSupport.actorId());
        UUID messageId = UUID.randomUUID();
        repository.insertOutboxEvent(context.tenantId(), UUID.randomUUID(), messageId,
                IdempotencyCodec.publicationKey(
                        TicketEventContract.WorkflowStartRequested.PUBLICATION_KEY_NAMESPACE,
                        context.tenantId(), messageId),
                startIntentKey, TicketEventContract.WorkflowStartRequested.AGGREGATE_TYPE, ticketId,
                TicketEventContract.WorkflowStartRequested.CONTRACT_REF,
                TicketEventContract.WorkflowStartRequested.EVENT_VERSION, bodyBytes,
                sha256(bodyBytes), context.correlationId(), now);
    }

    private String nextTicketNo(UUID tenantId, Instant now) {
        LocalDate businessDate = LocalDate.ofInstant(now, ZoneOffset.UTC);
        String prefix = "FD-" + TICKET_DATE.format(businessDate) + "-";
        long sequence = repository.nextTicketSequence(tenantId, businessDate, now);
        return String.format(Locale.ROOT, "%s%04d", prefix, sequence);
    }

    private Object decodeCursorValue(TicketCursorTokenService.Cursor cursor, String sortColumn) {
        if (cursor == null) {
            return null;
        }
        if ("ticketNo".equals(sortColumn)) {
            return cursor.lastValue();
        }
        return Timestamp.from(Instant.parse(cursor.lastValue()));
    }

    private String cursorString(TicketRepository.TicketRow row, String sortColumn) {
        return switch (sortColumn) {
            case "ticketNo" -> row.ticketNo();
            case "updatedAt" -> row.updatedAt().toString();
            case "createdAt" -> row.createdAt().toString();
            default -> throw new IllegalArgumentException("Unsupported sort column: " + sortColumn);
        };
    }

    private static String normalizeSort(String sort, Set<String> allowed, String defaultValue) {
        if (sort == null || sort.isBlank()) {
            return defaultValue;
        }
        if (!allowed.contains(sort)) {
            throw ApiException.badRequest("SORT_UNSUPPORTED", "不支持的排序字段。");
        }
        return sort;
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static UUID uuidFromKey(byte[] key) {
        byte[] first = Arrays.copyOf(key, 16);
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(first);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    private void writeNotification(TenantContext context, UUID recipientUserId, String kind,
                                   byte[] sourceEffectKey, UUID sourceRefId,
                                   String title, String body, Instant occurredAt) {
        writeNotification(context, recipientUserId, kind, sourceEffectKey, sourceRefId,
                title, body, occurredAt, "TICKET");
    }

    private void writeNotification(TenantContext context, UUID recipientUserId, String kind,
                                   byte[] sourceEffectKey, UUID sourceRefId,
                                   String title, String body, Instant occurredAt,
                                   String sourceRefType) {
        if (notificationWriter == null || recipientUserId == null) {
            return;
        }
        // Published SLA messages keep their original text across application upgrades.
        // Their existing outbox row continues to own delivery and any broker retries.
        if ("SLA_CLOCK".equals(sourceRefType) && repository.hasNotificationPublication(context.tenantId(),
                TicketNotificationIntent.deriveChildPublicationKey(context.tenantId(), sourceEffectKey, recipientUserId, kind), sourceRefId)) return;
        UUID relatedTicketId = "TICKET".equals(sourceRefType) ? sourceRefId
                : repository.findSlaClockById(context.tenantId(), sourceRefId)
                        .map(TicketRepository.SlaClockRow::ticketId).orElse(null);
        if (relatedTicketId != null) {
            var related = repository.findTicket(context.tenantId(), relatedTicketId).orElse(null);
            if (related != null) body = related.ticketNo() + " · " + related.subject() + "\n" + body;
        }
        TicketNotificationIntent.Intent intent = TicketNotificationIntent.create(
                context.tenantId(), recipientUserId, kind, sourceEffectKey,
                sourceRefType, sourceRefId, title, body, context.correlationId(), null,
                occurredAt);
        notificationWriter.write(context.tenantId(), intent);
    }

    private static String requestDigest(String canonicalIntent) {
        return IdempotencyCodec.requestDigestHex(canonicalIntent);
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private TicketDtos.TicketResponse toResponse(TicketRepository.TicketRow row) {
        TicketDtos.WorkflowRef workflowRef = row.workflowDefinitionId() == null ? null
                : new TicketDtos.WorkflowRef(row.workflowDefinitionId(), row.workflowVersionId(),
                        row.workflowVersionNo());
        return new TicketDtos.TicketResponse(row.ticketId(), row.ticketNo(), row.originType(),
                row.catalogItemId(), row.requesterUserId(), row.previousAttemptTicketId(),
                row.subject(), row.description(), row.status(), row.assigneeUserId(), workflowRef,
                row.firstProcessableAt(), row.resolvedAt(), row.closedAt(), row.lastReopenedAt(),
                row.reopenCount(), row.lockVersion(), row.createdAt(), row.updatedAt());
    }

    private TicketDtos.TicketCommentResponse toResponse(TicketRepository.CommentRow row) {
        return new TicketDtos.TicketCommentResponse(row.commentId(), row.ticketId(), row.body(),
                row.authorUserId(), row.createdAt(), row.correlationId());
    }

    private TicketDtos.TimelineEntryResponse toResponse(TicketRepository.TimelineRow row) {
        return new TicketDtos.TimelineEntryResponse(row.timelineId(), row.ticketId(), row.eventType(),
                row.summary(), row.relatedRefType(), row.relatedRefId(), row.actorType(), row.actorId(),
                row.occurredAt(), row.correlationId());
    }

    private TicketDtos.SlaClockStatus toResponse(TicketRepository.SlaClockRow row,
                                                 List<TicketRepository.SlaTriggerRow> triggers) {
        List<TicketDtos.SlaTriggerStatus> triggerItems = triggers.stream()
                .map(trigger -> new TicketDtos.SlaTriggerStatus(trigger.stage(), trigger.dueAt(),
                        trigger.triggeredAt(), repository.findSlaTriggerRecipientSnapshot(
                                TicketSupport.context().tenantId(), uuidFromKey(IdempotencyCodec.derivedKey(
                                        "FD-SLA-TRIGGER-V1", TicketSupport.context().tenantId(), row.slaClockId(), trigger.stage())))
                                .isEmpty() ? "PENDING_DELIVERY" : "QUEUED"))
                .toList();
        return new TicketDtos.SlaClockStatus(row.slaClockId(), row.slaPolicyId(), row.clockType(),
                row.status(), row.startedAt(), row.deadlineAt(), row.reminderDueAt(),
                row.policyDurationSeconds(), row.policyReminderBeforeSeconds(), row.milestoneAt(),
                row.breachedAt(), row.satisfiedAt(), triggerItems, row.lockVersion(), row.cycleNo());
    }

    private record TicketReplayContract(String actionCode, String eventType,
                                        String relatedRefType, UUID relatedRefId) {
    }

    private record CreateReplayAnchors(Optional<TicketRepository.TicketRow> byId,
                                       Optional<TicketRepository.TicketRow> byIntent,
                                       Optional<TicketRepository.TimelineRow> timeline,
                                       Optional<TicketRepository.AuditRow> audit) {
        private boolean empty() {
            return byId.isEmpty() && byIntent.isEmpty() && timeline.isEmpty() && audit.isEmpty();
        }

        private boolean complete() {
            return byId.isPresent() && byIntent.isPresent()
                    && timeline.isPresent() && audit.isPresent();
        }

        private boolean partial() {
            return !empty() && !complete();
        }
    }

    private static final class TicketInsertDuplicateException extends RuntimeException {
        private final String ticketNo;

        private TicketInsertDuplicateException(String ticketNo, DuplicateKeyException cause) {
            super("Ticket insert violated a unique constraint", cause);
            this.ticketNo = ticketNo;
        }

        private String ticketNo() {
            return ticketNo;
        }
    }

    private static final class CommandWriteRaceException extends RuntimeException {
        private CommandWriteRaceException() {
            super("Ticket command lost the optimistic write race");
        }
    }

    private enum RoleScope {
        REQUESTER("mine"), AGENT("assigned"), MIXED("visible"), ADMIN("tenant");

        private final String defaultScope;

        RoleScope(String defaultScope) {
            this.defaultScope = defaultScope;
        }

        String defaultScope() {
            return defaultScope;
        }
    }

    public record CreateResult(TicketDtos.TicketResponse ticket, boolean replayed,
                               List<AsyncProgress> asyncEffects) {
    }

    public record TransitionResult(TicketDtos.TicketResponse ticket, boolean replayed) {
    }

    public record CommentResult(TicketDtos.TicketCommentResponse comment, boolean replayed) {
    }

    public record SlaProcessResult(boolean triggered, boolean committed, String code) {
    }
}
