package com.flowdesk.iam.service;

import com.flowdesk.common.authz.PermissionCode;
import com.flowdesk.common.authz.RoleCode;
import com.flowdesk.common.tenant.TenantContext;
import com.flowdesk.common.web.ApiException;
import com.flowdesk.iam.api.IamDtos;
import com.flowdesk.iam.idempotency.IdempotencyCodec;
import com.flowdesk.iam.repository.IamRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * API-003..006 user management.
 */
@Service
public class UserManagementService {
    /** Minimal tenant-scoped identity for assigned approval work; omits roles and credentials. */
    public Identity identity(UUID userId) {
        IamSupport.requireAny(PermissionCode.IAM_USER_READ, PermissionCode.APPROVAL_READ_ASSIGNED,
                PermissionCode.TICKET_READ_TENANT);
        var row=repository.findUser(IamSupport.context().tenantId(),userId)
                .orElseThrow(() -> ApiException.notFound("RESOURCE_NOT_FOUND","用户不存在。"));
        return new Identity(row.userId(),row.username(),row.displayName());
    }
    public record Identity(UUID userId, String username, String displayName) { }

    private final IamRepository repository;
    private final PasswordHashService passwordHashService;
    private final CursorTokenService cursorTokenService;
    private final Clock clock;

    public UserManagementService(IamRepository repository,
                                 PasswordHashService passwordHashService,
                                 CursorTokenService cursorTokenService) {
        this(repository, passwordHashService, cursorTokenService, Clock.systemUTC());
    }

    @Autowired
    public UserManagementService(IamRepository repository,
                                 PasswordHashService passwordHashService,
                                 CursorTokenService cursorTokenService,
                                 Clock clock) {
        this.repository = repository;
        this.passwordHashService = passwordHashService;
        this.cursorTokenService = cursorTokenService;
        this.clock = clock;
    }

    public PageResult<IamDtos.UserSummaryResponse> list(String status,
                                                        String roleCode,
                                                        int pageSize,
                                                        String pageToken,
                                                        String sort) {
        TenantContext context = IamSupport.context();
        boolean tenantAdmin = IamSupport.hasAny(PermissionCode.IAM_USER_READ);
        boolean serviceDeskAdmin = IamSupport.hasAny(PermissionCode.IAM_ELIGIBILITY_CANDIDATE_READ);
        if (!tenantAdmin && !serviceDeskAdmin) {
            throw ApiException.forbidden("ACCESS_DENIED", "当前角色或权限不足。");
        }

        String normalizedSort = normalizeUserSort(sort);
        boolean ascending = !normalizedSort.startsWith("-");
        String sortColumn = ascending ? normalizedSort : normalizedSort.substring(1);
        String statusFilter = null;
        String roleFilter = null;
        if (tenantAdmin) {
            statusFilter = normalizeStatus(status);
            roleFilter = roleCode == null ? null : parseRoleCode(roleCode);
        } else {
            if (!"ACTIVE".equals(status) || roleCode == null
                    || (!"AGENT".equals(roleCode) && !"APPROVER".equals(roleCode))) {
                throw ApiException.forbidden("USER_FILTER_SCOPE_FORBIDDEN",
                        "ServiceDeskAdmin 只能查询 ACTIVE 的 Agent/Approver 候选。");
            }
            statusFilter = "ACTIVE";
            roleFilter = roleCode;
        }

        String scope = "users|" + (tenantAdmin ? "TENANT_ADMIN" : "SERVICE_DESK_ADMIN")
                + "|" + statusFilter + "|" + roleFilter;
        CursorTokenService.Cursor cursor = cursorTokenService.decode(
                pageToken, context.tenantId(), scope, pageSize, normalizedSort);
        List<IamRepository.UserRow> rows = repository.findUsers(context.tenantId(), statusFilter,
                roleFilter, sortColumn, ascending,
                cursor == null ? null : cursor.lastName(),
                cursor == null ? null : cursor.lastId(),
                pageSize);

        boolean hasMore = rows.size() > pageSize;
        if (hasMore) {
            rows = rows.subList(0, pageSize);
        }
        Map<UUID, List<RoleCode>> rolesByUser = batchRoles(context, rows);
        List<IamDtos.UserSummaryResponse> items = rows.stream()
                .map(row -> toSummary(context, row, rolesByUser.getOrDefault(row.userId(), List.of())))
                .toList();
        String nextToken = null;
        if (hasMore && !items.isEmpty()) {
            IamRepository.UserRow last = rows.get(rows.size() - 1);
            nextToken = cursorTokenService.encode(context.tenantId(), scope, pageSize,
                    normalizedSort, sortValue(last, sortColumn), last.userId());
        }
        return new PageResult<>(items, nextToken, normalizedSort);
    }

    private Map<UUID, List<RoleCode>> batchRoles(TenantContext context,
                                                 List<IamRepository.UserRow> rows) {
        Map<UUID, List<String>> codes = repository.findGrantedRoleCodesForUsers(context.tenantId(),
                rows.stream().map(IamRepository.UserRow::userId).toList());
        Map<UUID, List<RoleCode>> result = new java.util.HashMap<>();
        codes.forEach((userId, values) -> result.put(userId,
                values.stream().map(RoleCode::valueOf).sorted().toList()));
        return result;
    }

    public IamDtos.UserSummaryResponse get(UUID userId) {
        TenantContext context = IamSupport.context();
        IamSupport.requireAny(PermissionCode.IAM_USER_READ,
                PermissionCode.IAM_ELIGIBILITY_CANDIDATE_READ);
        IamRepository.UserRow row = repository.findUser(context.tenantId(), userId)
                .orElseThrow(() -> ApiException.notFound("RESOURCE_NOT_FOUND", "用户不存在。"));
        if (!IamSupport.hasAny(PermissionCode.IAM_USER_READ) && !isCandidate(context, row)) {
            throw ApiException.notFound("RESOURCE_NOT_FOUND", "用户不存在。");
        }
        return toSummary(context, row);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CreateUserResult create(IamDtos.UserCreateRequest request, String idempotencyKey) {
        TenantContext context = IamSupport.context();
        IamSupport.require(PermissionCode.IAM_USER_MANAGE);
        String key = IamSupport.requireIdempotencyKey(idempotencyKey);

        String intent = CanonicalIntent.of("username", request.username(), "displayName",
                request.displayName(), "status", request.status().name());
        String digest = IdempotencyCodec.requestDigestHex(intent);
        UUID stableUserId = IdempotencyCodec.effectKeyUuid(
                context, "API-004", "COLLECTION", key);

        var existingAudit = repository.findAuditReason(context.tenantId(), stableUserId);
        if (existingAudit.isPresent()) {
            if (!digest.equals(existingAudit.get())) {
                throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED",
                        "同一 Idempotency-Key 已用于不同业务意图。");
            }
            IamRepository.UserRow existing = repository.findUser(context.tenantId(), stableUserId)
                    .orElseThrow(() -> ApiException.conflict("IDEMPOTENCY_KEY_REUSED",
                            "幂等锚点存在但资源缺失，拒绝重试。"));
            return new CreateUserResult(toSummary(context, existing), true);
        }

        if (repository.usernameExists(context.tenantId(), request.username())) {
            throw ApiException.conflict("USERNAME_ALREADY_EXISTS", "用户名已存在。");
        }
        if (repository.findUser(context.tenantId(), stableUserId).isPresent()) {
            throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED",
                    "稳定资源标识已被占用，拒绝重试。");
        }

        Instant now = now();
        try {
            repository.insertUser(context.tenantId(), stableUserId, request.username(),
                    request.displayName(), request.status().name(), IamSupport.actorId(), now);
        } catch (DuplicateKeyException e) {
            return resolveInsertDuplicate(context, stableUserId, request.username(), digest);
        }
        try {
            repository.insertCredential(context.tenantId(), stableUserId,
                    passwordHashService.encode(request.initialPassword()),
                    PasswordHashService.SCHEME, IamSupport.actorId(), now);
        } catch (DuplicateKeyException e) {
            throw ApiException.internal("凭证写入失败。");
        }
        try {
            repository.insertAudit(context.tenantId(), stableUserId, "IAM_USER_CREATE", "USER",
                    stableUserId, "SUCCEEDED", "USER", IamSupport.actorId(), digest, null,
                    context.correlationId(), now);
        } catch (DuplicateKeyException e) {
            throw ApiException.internal("审计记录写入失败。");
        }
        return new CreateUserResult(toSummary(context,
                repository.findUser(context.tenantId(), stableUserId).orElseThrow()), false);
    }

    private CreateUserResult resolveInsertDuplicate(TenantContext context, UUID stableUserId,
                                                    String username, String digest) {
        var existingAudit = repository.findAuditReasonAfterDuplicate(context.tenantId(), stableUserId);
        if (existingAudit.isPresent()) {
            if (!digest.equals(existingAudit.get())) {
                throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED",
                        "同一 Idempotency-Key 已用于不同业务意图。");
            }
            return replayExisting(context, stableUserId, digest);
        }
        if (repository.usernameExists(context.tenantId(), username)) {
            throw ApiException.conflict("USERNAME_ALREADY_EXISTS", "用户名已存在。");
        }
        if (repository.findUser(context.tenantId(), stableUserId).isPresent()) {
            throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED", "稳定资源标识已被占用，拒绝重试。");
        }
        throw ApiException.internal("用户写入失败。");
    }

    private CreateUserResult replayExisting(TenantContext context, UUID stableUserId, String digest) {
        String existingDigest = repository.findAuditReasonAfterDuplicate(context.tenantId(), stableUserId)
                .orElseThrow(() -> ApiException.conflict("IDEMPOTENCY_KEY_REUSED",
                        "幂等锚点缺失，拒绝重试。"));
        if (!digest.equals(existingDigest)) {
            throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED",
                    "同一 Idempotency-Key 已用于不同业务意图。");
        }
        IamRepository.UserRow existing = repository.findUserForUpdate(context.tenantId(), stableUserId)
                .orElseThrow(() -> ApiException.conflict("IDEMPOTENCY_KEY_REUSED",
                        "幂等锚点存在但资源缺失，拒绝重试。"));
        return new CreateUserResult(toSummary(context, existing), true);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public IamDtos.UserSummaryResponse patch(UUID userId, IamDtos.UserPatchRequest request,
                                             long expectedLockVersion) {
        TenantContext context = IamSupport.context();
        IamSupport.require(PermissionCode.IAM_USER_MANAGE);
        if (userId.equals(IamSupport.actorId()) && request.status() == IamDtos.UserPatchRequest.Status.INACTIVE)
            throw ApiException.conflict("IAM_SELF_ACCOUNT_REQUIRED", "不能停用当前登录账号，请由另一位组织管理员办理交接。");
        if (request.displayName() == null && request.status() == null) {
            throw ApiException.unprocessable("VALIDATION_FAILED", "至少提供一个可修改字段。",
                    List.of(new com.flowdesk.common.web.FieldViolation(
                            "/body", "EMPTY_PATCH", "至少提供一个可修改字段。")));
        }
        IamRepository.UserRow existing = repository.findUser(context.tenantId(), userId)
                .orElseThrow(() -> ApiException.notFound("RESOURCE_NOT_FOUND", "用户不存在。"));
        if (existing.lockVersion() != expectedLockVersion) {
            throw IamSupport.versionConflict(IamSupport.etagHeaders(existing.lockVersion()));
        }
        Instant now = now();
        boolean updated = repository.updateUser(context.tenantId(), userId, expectedLockVersion,
                request.displayName(), request.status() == null ? null : request.status().name(),
                IamSupport.actorId(), now);
        if (!updated) {
            IamRepository.UserRow refreshed = repository.findUser(context.tenantId(), userId)
                    .orElseThrow(() -> ApiException.notFound("RESOURCE_NOT_FOUND", "用户不存在。"));
            throw IamSupport.versionConflict(IamSupport.etagHeaders(refreshed.lockVersion()));
        }
        IamRepository.UserRow updatedRow = repository.findUser(context.tenantId(), userId)
                .orElseThrow(() -> ApiException.notFound("RESOURCE_NOT_FOUND", "用户不存在。"));
        try {
            repository.insertAudit(context.tenantId(), UUID.randomUUID(), "IAM_USER_UPDATE", "USER",
                    userId, "SUCCEEDED", "USER", IamSupport.actorId(), null, null,
                    context.correlationId(), now);
        } catch (DuplicateKeyException e) {
            throw ApiException.internal("审计记录写入失败。");
        }
        return toSummary(context, updatedRow);
    }

    private boolean isCandidate(TenantContext context, IamRepository.UserRow row) {
        if (!"ACTIVE".equals(row.status())) {
            return false;
        }
        List<String> roles = repository.findGrantedRoleCodes(context.tenantId(), row.userId());
        return roles.contains("AGENT") || roles.contains("APPROVER");
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private IamDtos.UserSummaryResponse toSummary(TenantContext context,
                                                  IamRepository.UserRow row) {
        return toSummary(context, row, singleRoles(context, row.userId()));
    }

    private IamDtos.UserSummaryResponse toSummary(TenantContext context,
                                                  IamRepository.UserRow row,
                                                  List<RoleCode> roles) {
        return new IamDtos.UserSummaryResponse(row.userId(), row.username(), row.displayName(),
                row.status(), roles, row.lockVersion(), row.createdAt(), row.updatedAt());
    }

    private List<RoleCode> singleRoles(TenantContext context, UUID userId) {
        return repository.findGrantedRoleCodes(context.tenantId(), userId)
                .stream().map(RoleCode::valueOf).sorted().toList();
    }

    private static String normalizeUserSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return "displayName";
        }
        return switch (sort) {
            case "displayName", "-displayName", "username", "-username" -> sort;
            default -> throw ApiException.badRequest("SORT_UNSUPPORTED", "不支持的排序字段。");
        };
    }

    private static String normalizeStatus(String status) {
        if (status == null) {
            return null;
        }
        if ("ACTIVE".equals(status) || "INACTIVE".equals(status)) {
            return status;
        }
        throw ApiException.badRequest("INVALID_QUERY_PARAMETER", "status 只接受 ACTIVE/INACTIVE。");
    }

    private static String parseRoleCode(String roleCode) {
        try {
            return RoleCode.valueOf(roleCode).name();
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("INVALID_QUERY_PARAMETER", "roleCode 不是 P0 角色。");
        }
    }

    private static String sortValue(IamRepository.UserRow row, String column) {
        return "username".equals(column) ? row.username() : row.displayName();
    }

    public record CreateUserResult(IamDtos.UserSummaryResponse user, boolean replayed) {
    }
}
