package com.flowdesk.iam.service.impl;

import com.flowdesk.iam.model.*;
import com.flowdesk.iam.service.*;

import com.flowdesk.common.authz.PermissionCode;
import com.flowdesk.common.authz.RoleCode;
import com.flowdesk.common.tenant.TenantContext;
import com.flowdesk.common.web.ApiException;
import com.flowdesk.iam.dto.IamDtos;
import com.flowdesk.iam.idempotency.IdempotencyCodec;
import com.flowdesk.iam.mapper.AccessMapper;
import com.flowdesk.iam.mapper.AuditMapper;
import com.flowdesk.iam.mapper.CredentialMapper;
import com.flowdesk.iam.mapper.UserMapper;
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
 * 管理租户用户、候选人员和最小身份；将账户、凭证及创建审计放在同一事务内。
 */
@Service
public class UserManagementServiceImpl implements UserManagementService {
    /** 返回租户内最小身份信息，供业务页面展示姓名，不包含角色与凭证。 */
    public Identity identity(UUID userId) {
        IamSupport.requireAny(PermissionCode.IAM_USER_READ, PermissionCode.APPROVAL_READ_ASSIGNED,
                PermissionCode.TICKET_READ_TENANT);
        var row=repository.findUser(IamSupport.context().tenantId(),userId)
                .orElseThrow(() -> ApiException.notFound("RESOURCE_NOT_FOUND","用户不存在。"));
        return new Identity(row.userId(),row.username(),row.displayName());
    }


    private final UserMapper repository;
    private final CredentialMapper credentialRepository;
    private final AccessMapper accessRepository;
    private final AuditMapper auditRepository;
    private final PasswordHashService passwordHashService;
    private final CursorTokenService cursorTokenService;
    private final Clock clock;

    /**
     * 组装当前组件依赖并委托完整构造方法；使用 UTC 系统时钟等默认运行配置，显式依赖版本可用于确定性测试。
     */
    public UserManagementServiceImpl(UserMapper repository,
                                 CredentialMapper credentialRepository,
                                 AccessMapper accessRepository,
                                 AuditMapper auditRepository,
                                 PasswordHashService passwordHashService,
                                 CursorTokenService cursorTokenService) {
        this(repository, credentialRepository, accessRepository, auditRepository,
                passwordHashService, cursorTokenService, Clock.systemUTC());
    }

    /**
     * 注入用户、凭证、授权与审计 Mapper，以及密码派生、签名游标和时钟，支撑账户与初始凭证的原子创建。
     */
    @Autowired
    public UserManagementServiceImpl(UserMapper repository,
                                 CredentialMapper credentialRepository,
                                 AccessMapper accessRepository,
                                 AuditMapper auditRepository,
                                 PasswordHashService passwordHashService,
                                 CursorTokenService cursorTokenService,
                                 Clock clock) {
        this.repository = repository;
        this.credentialRepository = credentialRepository;
        this.accessRepository = accessRepository;
        this.auditRepository = auditRepository;
        this.passwordHashService = passwordHashService;
        this.cursorTokenService = cursorTokenService;
        this.clock = clock;
    }

    /** 仅向工单分派、转派操作提供有效 Agent 的最小身份，避免暴露账户管理数据。 */
    public PageResult<Identity> ticketAssigneeCandidates(int pageSize, String pageToken, String sort) {
        IamSupport.requireAny(PermissionCode.TICKET_ASSIGN_TENANT,
                PermissionCode.TICKET_TRANSFER_TENANT, PermissionCode.TICKET_TRANSFER_ASSIGNED);
        TenantContext context = IamSupport.context();
        String normalizedSort = normalizeUserSort(sort);
        String sortColumn = normalizedSort.startsWith("-") ? normalizedSort.substring(1) : normalizedSort;
        String scope = "ticket-assignee-candidates";
        CursorTokenService.Cursor cursor = cursorTokenService.decode(
                pageToken, context.tenantId(), scope, pageSize, normalizedSort);
        List<UserRow> rows = repository.findUsers(context.tenantId(), "ACTIVE", "AGENT",
                sortColumn, !normalizedSort.startsWith("-"),
                cursor == null ? null : cursor.lastName(), cursor == null ? null : cursor.lastId(), pageSize);
        boolean hasMore = rows.size() > pageSize;
        if (hasMore) rows = rows.subList(0, pageSize);
        String nextToken = null;
        if (hasMore && !rows.isEmpty()) {
            UserRow last = rows.get(rows.size() - 1);
            nextToken = cursorTokenService.encode(context.tenantId(), scope, pageSize,
                    normalizedSort, sortValue(last, sortColumn), last.userId());
        }
        return new PageResult<>(rows.stream()
                .map(row -> new Identity(row.userId(), row.username(), row.displayName())).toList(),
                nextToken, normalizedSort);
    }

    /**
     * 按当前权限限制账户列表或有效候选范围，将筛选条件绑定游标，并批量补齐本页用户的有效角色。
     */
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

        // 游标同时绑定权限范围和筛选条件，不能拿管理列表的游标继续查询候选列表。
        String scope = "users|" + (tenantAdmin ? "TENANT_ADMIN" : "SERVICE_DESK_ADMIN")
                + "|" + statusFilter + "|" + roleFilter;
        CursorTokenService.Cursor cursor = cursorTokenService.decode(
                pageToken, context.tenantId(), scope, pageSize, normalizedSort);
        List<UserRow> rows = repository.findUsers(context.tenantId(), statusFilter,
                roleFilter, sortColumn, ascending,
                cursor == null ? null : cursor.lastName(),
                cursor == null ? null : cursor.lastId(),
                pageSize);

        boolean hasMore = rows.size() > pageSize;
        if (hasMore) {
            rows = rows.subList(0, pageSize);
        }
        // 按当前页批量查询角色，避免逐用户访问数据库造成 N+1 查询。
        Map<UUID, List<RoleCode>> rolesByUser = batchRoles(context, rows);
        List<IamDtos.UserSummaryResponse> items = rows.stream()
                .map(row -> toSummary(context, row, rolesByUser.getOrDefault(row.userId(), List.of())))
                .toList();
        String nextToken = null;
        if (hasMore && !items.isEmpty()) {
            UserRow last = rows.get(rows.size() - 1);
            nextToken = cursorTokenService.encode(context.tenantId(), scope, pageSize,
                    normalizedSort, sortValue(last, sortColumn), last.userId());
        }
        return new PageResult<>(items, nextToken, normalizedSort);
    }

    /**
     * 按当前页用户批量查询有效角色并分组，避免用户列表逐行触发额外数据库查询。
     */
    private Map<UUID, List<RoleCode>> batchRoles(TenantContext context,
                                                 List<UserRow> rows) {
        Map<UUID, List<String>> codes = accessRepository.findGrantedRoleCodesForUsers(context.tenantId(),
                rows.stream().map(UserRow::userId).toList());
        Map<UUID, List<RoleCode>> result = new java.util.HashMap<>();
        codes.forEach((userId, values) -> result.put(userId,
                values.stream().map(RoleCode::valueOf).sorted().toList()));
        return result;
    }

    /**
     * 查询同租户用户及有效角色；只有候选读取权限时，额外限制用户必须是可用的业务候选人。
     */
    public IamDtos.UserSummaryResponse get(UUID userId) {
        TenantContext context = IamSupport.context();
        IamSupport.requireAny(PermissionCode.IAM_USER_READ,
                PermissionCode.IAM_ELIGIBILITY_CANDIDATE_READ);
        UserRow row = repository.findUser(context.tenantId(), userId)
                .orElseThrow(() -> ApiException.notFound("RESOURCE_NOT_FOUND", "用户不存在。"));
        if (!IamSupport.hasAny(PermissionCode.IAM_USER_READ) && !isCandidate(context, row)) {
            throw ApiException.notFound("RESOURCE_NOT_FOUND", "用户不存在。");
        }
        return toSummary(context, row);
    }

    /**
     * 校验用户管理权限、用户名唯一性及幂等意图，在同一事务创建账户、加盐凭证和创建审计。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CreateUserResult create(IamDtos.UserCreateRequest request, String idempotencyKey) {
        TenantContext context = IamSupport.context();
        IamSupport.require(PermissionCode.IAM_USER_MANAGE);
        String key = IamSupport.requireIdempotencyKey(idempotencyKey);

        String intent = CanonicalIntent.of("username", request.username(), "displayName",
                request.displayName(), "status", request.status().name());
        String digest = IdempotencyCodec.requestDigestHex(intent);
        // 用请求键派生稳定用户标识，并以创建审计中的意图摘要识别安全重试。
        UUID stableUserId = IdempotencyCodec.effectKeyUuid(
                context, "API-004", "COLLECTION", key);

        var existingAudit = auditRepository.findAuditReason(context.tenantId(), stableUserId);
        if (existingAudit.isPresent()) {
            if (!digest.equals(existingAudit.get())) {
                throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED",
                        "同一 Idempotency-Key 已用于不同业务意图。");
            }
            UserRow existing = repository.findUser(context.tenantId(), stableUserId)
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
            credentialRepository.insertCredential(context.tenantId(), stableUserId,
                    passwordHashService.encode(request.initialPassword()),
                    PasswordHashService.SCHEME, IamSupport.actorId(), now);
        } catch (DuplicateKeyException e) {
            throw ApiException.internal("凭证写入失败。");
        }
        try {
            auditRepository.insertAudit(context.tenantId(), stableUserId, "IAM_USER_CREATE", "USER",
                    stableUserId, "SUCCEEDED", "USER", IamSupport.actorId(), digest, null,
                    context.correlationId(), now);
        } catch (DuplicateKeyException e) {
            throw ApiException.internal("审计记录写入失败。");
        }
        return new CreateUserResult(toSummary(context,
                repository.findUser(context.tenantId(), stableUserId).orElseThrow()), false);
    }

    /**
     * 处理资源插入的唯一键竞争，回读已提交审计及资源，区分安全幂等重试、业务重名和标识冲突。
     */
    private CreateUserResult resolveInsertDuplicate(TenantContext context, UUID stableUserId,
                                                    String username, String digest) {
        var existingAudit = auditRepository.findAuditReasonAfterDuplicate(context.tenantId(), stableUserId);
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

    /**
     * 核对创建审计的请求摘要并返回原稳定资源；审计或资源不一致时拒绝把异常状态当作重试成功。
     */
    private CreateUserResult replayExisting(TenantContext context, UUID stableUserId, String digest) {
        String existingDigest = auditRepository.findAuditReasonAfterDuplicate(context.tenantId(), stableUserId)
                .orElseThrow(() -> ApiException.conflict("IDEMPOTENCY_KEY_REUSED",
                        "幂等锚点缺失，拒绝重试。"));
        if (!digest.equals(existingDigest)) {
            throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED",
                    "同一 Idempotency-Key 已用于不同业务意图。");
        }
        UserRow existing = repository.findUserForUpdate(context.tenantId(), stableUserId)
                .orElseThrow(() -> ApiException.conflict("IDEMPOTENCY_KEY_REUSED",
                        "幂等锚点存在但资源缺失，拒绝重试。"));
        return new CreateUserResult(toSummary(context, existing), true);
    }

    /**
     * 读取同租户账户并核对预期版本，以乐观锁更新允许字段及审计；禁止当前管理员使自身登录账户停用。
     */
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
        UserRow existing = repository.findUser(context.tenantId(), userId)
                .orElseThrow(() -> ApiException.notFound("RESOURCE_NOT_FOUND", "用户不存在。"));
        if (existing.lockVersion() != expectedLockVersion) {
            throw IamSupport.versionConflict(IamSupport.etagHeaders(existing.lockVersion()));
        }
        Instant now = now();
        boolean updated = repository.updateUser(context.tenantId(), userId, expectedLockVersion,
                request.displayName(), request.status() == null ? null : request.status().name(),
                IamSupport.actorId(), now);
        if (!updated) {
            UserRow refreshed = repository.findUser(context.tenantId(), userId)
                    .orElseThrow(() -> ApiException.notFound("RESOURCE_NOT_FOUND", "用户不存在。"));
            throw IamSupport.versionConflict(IamSupport.etagHeaders(refreshed.lockVersion()));
        }
        UserRow updatedRow = repository.findUser(context.tenantId(), userId)
                .orElseThrow(() -> ApiException.notFound("RESOURCE_NOT_FOUND", "用户不存在。"));
        try {
            auditRepository.insertAudit(context.tenantId(), UUID.randomUUID(), "IAM_USER_UPDATE", "USER",
                    userId, "SUCCEEDED", "USER", IamSupport.actorId(), null, null,
                    context.correlationId(), now);
        } catch (DuplicateKeyException e) {
            throw ApiException.internal("审计记录写入失败。");
        }
        return toSummary(context, updatedRow);
    }

    /**
     * 判断用户是否为启用的 Agent 或 Approver，以约束仅有候选查询权限的调用者可见范围。
     */
    private boolean isCandidate(TenantContext context, UserRow row) {
        if (!"ACTIVE".equals(row.status())) {
            return false;
        }
        List<String> roles = accessRepository.findGrantedRoleCodes(context.tenantId(), row.userId());
        return roles.contains("AGENT") || roles.contains("APPROVER");
    }

    /**
     * 读取当前时间并截断到微秒，使写入与数据库时间精度一致。
     */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * 将账户投影和有效角色组合为用户摘要，保留版本及创建、更新时间。
     */
    private IamDtos.UserSummaryResponse toSummary(TenantContext context,
                                                  UserRow row) {
        return toSummary(context, row, singleRoles(context, row.userId()));
    }

    /**
     * 将账户投影和有效角色组合为用户摘要，保留版本及创建、更新时间。
     */
    private IamDtos.UserSummaryResponse toSummary(TenantContext context,
                                                  UserRow row,
                                                  List<RoleCode> roles) {
        return new IamDtos.UserSummaryResponse(row.userId(), row.username(), row.displayName(),
                row.status(), roles, row.lockVersion(), row.createdAt(), row.updatedAt());
    }

    /**
     * 读取单个用户当前有效的角色代码，并转换为排序后的领域枚举集合。
     */
    private List<RoleCode> singleRoles(TenantContext context, UUID userId) {
        return accessRepository.findGrantedRoleCodes(context.tenantId(), userId)
                .stream().map(RoleCode::valueOf).sorted().toList();
    }

    /**
     * 将用户排序限制为支持字段并补全默认值，保证数据库排序列及分页游标的含义一致。
     */
    private static String normalizeUserSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return "displayName";
        }
        return switch (sort) {
            case "displayName", "-displayName", "username", "-username" -> sort;
            default -> throw ApiException.badRequest("SORT_UNSUPPORTED", "不支持的排序字段。");
        };
    }

    /**
     * 校验可选启停状态筛选；未提供时不增加状态限制，非法值返回查询参数错误。
     */
    private static String normalizeStatus(String status) {
        if (status == null) {
            return null;
        }
        if ("ACTIVE".equals(status) || "INACTIVE".equals(status)) {
            return status;
        }
        throw ApiException.badRequest("INVALID_QUERY_PARAMETER", "status 只接受 ACTIVE/INACTIVE。");
    }

    /**
     * 把查询中的角色编码校验为固定角色枚举，未知代码转为对外参数错误。
     */
    private static String parseRoleCode(String roleCode) {
        try {
            return RoleCode.valueOf(roleCode).name();
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("INVALID_QUERY_PARAMETER", "roleCode 不是 P0 角色。");
        }
    }

    /**
     * 从数据库投影取出当前排序字段的值，作为下一页签名游标的位置。
     */
    private static String sortValue(UserRow row, String column) {
        return "username".equals(column) ? row.username() : row.displayName();
    }


}
