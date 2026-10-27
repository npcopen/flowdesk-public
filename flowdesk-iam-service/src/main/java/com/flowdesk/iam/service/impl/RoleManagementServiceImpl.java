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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * 租户角色的读写用例（API-007～010），使用审计锚点和版本号处理重复提交与并发修改。
 */
@Service
public class RoleManagementServiceImpl implements RoleManagementService {

    private final AccessMapper repository;
    private final AuditMapper auditRepository;
    private final CursorTokenService cursorTokenService;
    private final Clock clock;

    /**
     * 组装当前组件依赖并委托完整构造方法；使用 UTC 系统时钟等默认运行配置，显式依赖版本可用于确定性测试。
     */
    public RoleManagementServiceImpl(AccessMapper repository, AuditMapper auditRepository,
                                 CursorTokenService cursorTokenService) {
        this(repository, auditRepository, cursorTokenService, Clock.systemUTC());
    }

    /**
     * 注入角色授权 Mapper、审计 Mapper、签名游标及可替换时钟，统一角色查询和幂等写入所需依赖。
     */
    @Autowired
    public RoleManagementServiceImpl(AccessMapper repository, AuditMapper auditRepository,
                                 CursorTokenService cursorTokenService,
                                 Clock clock) {
        this.repository = repository;
        this.auditRepository = auditRepository;
        this.cursorTokenService = cursorTokenService;
        this.clock = clock;
    }

    /**
     * 校验角色读取权限和状态筛选，以排序字段与角色 ID 组合游标分页，额外读取一条判断下一页。
     */
    public PageResult<IamDtos.RoleResponse> list(String status, int pageSize,
                                                 String pageToken, String sort) {
        TenantContext context = IamSupport.context();
        IamSupport.require(PermissionCode.IAM_ROLE_READ);
        String normalizedSort = normalizeRoleSort(sort);
        boolean ascending = !normalizedSort.startsWith("-");
        String sortColumn = ascending ? normalizedSort : normalizedSort.substring(1);
        String statusFilter = status == null ? null
                : switch (status) {
                    case "ACTIVE", "INACTIVE" -> status;
                    default -> throw ApiException.badRequest(
                            "INVALID_QUERY_PARAMETER", "status 只接受 ACTIVE/INACTIVE。");
                };
        String scope = "roles|" + statusFilter;
        CursorTokenService.Cursor cursor = cursorTokenService.decode(
                pageToken, context.tenantId(), scope, pageSize, normalizedSort);
        List<RoleRow> rows = repository.findRoles(context.tenantId(), statusFilter,
                sortColumn, ascending, cursor == null ? null : cursor.lastName(),
                cursor == null ? null : cursor.lastId(), pageSize);
        boolean hasMore = rows.size() > pageSize;
        if (hasMore) {
            rows = rows.subList(0, pageSize);
        }
        List<IamDtos.RoleResponse> items = rows.stream().map(this::toResponse).toList();
        String nextToken = null;
        if (hasMore && !items.isEmpty()) {
            RoleRow last = rows.get(rows.size() - 1);
            nextToken = cursorTokenService.encode(context.tenantId(), scope, pageSize,
                    normalizedSort, sortValue(last, sortColumn), last.roleId());
        }
        return new PageResult<>(items, nextToken, normalizedSort);
    }

    /**
     * 要求角色读取权限后查询当前租户的角色，不存在时返回资源未找到。
     */
    public IamDtos.RoleResponse get(UUID roleId) {
        TenantContext context = IamSupport.context();
        IamSupport.require(PermissionCode.IAM_ROLE_READ);
        return repository.findRole(context.tenantId(), roleId)
                .map(this::toResponse)
                .orElseThrow(() -> ApiException.notFound("RESOURCE_NOT_FOUND", "角色不存在。"));
    }

    /**
     * 校验角色管理权限和幂等键，用稳定角色标识与请求摘要锚定创建结果，角色和创建审计在同一事务提交。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CreateRoleResult create(IamDtos.RoleCreateRequest request, String idempotencyKey) {
        TenantContext context = IamSupport.context();
        IamSupport.require(PermissionCode.IAM_ROLE_MANAGE);
        String key = IamSupport.requireIdempotencyKey(idempotencyKey);
        String intent = CanonicalIntent.of("roleCode", request.roleCode().name(),
                "roleName", request.roleName(), "status", request.status().name());
        String digest = IdempotencyCodec.requestDigestHex(intent);
        // 角色主键与创建审计共用稳定幂等身份，使并发重试能核对已提交结果。
        UUID stableRoleId = IdempotencyCodec.effectKeyUuid(
                context, "API-008", "COLLECTION", key);

        var existingAudit = auditRepository.findAuditReason(context.tenantId(), stableRoleId);
        if (existingAudit.isPresent()) {
            if (!digest.equals(existingAudit.get())) {
                throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED",
                        "同一 Idempotency-Key 已用于不同业务意图。");
            }
            RoleRow existing = repository.findRoleForUpdate(context.tenantId(), stableRoleId)
                    .orElseThrow(() -> ApiException.conflict("IDEMPOTENCY_KEY_REUSED",
                            "幂等锚点存在但资源缺失，拒绝重试。"));
            return new CreateRoleResult(toResponse(existing), true);
        }

        if (repository.findRoleByCode(context.tenantId(), request.roleCode().name()).isPresent()) {
            throw ApiException.conflict("ROLE_CODE_ALREADY_EXISTS", "角色代码已存在。");
        }
        if (repository.findRole(context.tenantId(), stableRoleId).isPresent()) {
            throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED", "稳定角色标识已被占用，拒绝重试。");
        }

        Instant now = now();
        try {
            repository.insertRole(context.tenantId(), stableRoleId, request.roleCode().name(),
                    request.roleName(), request.status().name(), IamSupport.actorId(), now);
        } catch (DuplicateKeyException e) {
            return resolveInsertDuplicate(context, stableRoleId, request.roleCode().name(), digest);
        }
        try {
            auditRepository.insertAudit(context.tenantId(), stableRoleId, "IAM_ROLE_CREATE", "ROLE",
                    stableRoleId, "SUCCEEDED", "USER", IamSupport.actorId(), digest, null,
                    context.correlationId(), now);
        } catch (DuplicateKeyException e) {
            throw ApiException.internal("审计记录写入失败。");
        }
        return new CreateRoleResult(toResponse(
                repository.findRole(context.tenantId(), stableRoleId).orElseThrow()), false);
    }

    /**
     * 处理资源插入的唯一键竞争，回读已提交审计及资源，区分安全幂等重试、业务重名和标识冲突。
     */
    private CreateRoleResult resolveInsertDuplicate(TenantContext context, UUID stableRoleId,
                                                    String roleCode, String digest) {
        var existingAudit = auditRepository.findAuditReasonAfterDuplicate(context.tenantId(), stableRoleId);
        if (existingAudit.isPresent()) {
            if (!digest.equals(existingAudit.get())) {
                throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED",
                        "同一 Idempotency-Key 已用于不同业务意图。");
            }
            RoleRow existing = repository.findRoleForUpdate(context.tenantId(), stableRoleId)
                    .orElseThrow(() -> ApiException.conflict("IDEMPOTENCY_KEY_REUSED",
                            "幂等锚点存在但资源缺失，拒绝重试。"));
            return new CreateRoleResult(toResponse(existing), true);
        }
        if (repository.findRoleByCode(context.tenantId(), roleCode).isPresent()) {
            throw ApiException.conflict("ROLE_CODE_ALREADY_EXISTS", "角色代码已存在。");
        }
        if (repository.findRole(context.tenantId(), stableRoleId).isPresent()) {
            throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED", "稳定角色标识已被占用，拒绝重试。");
        }
        throw ApiException.internal("角色写入失败。");
    }

    /**
     * 读取当前租户角色并核对预期版本，使用乐观锁更新名称或状态；变更和审计在同一事务提交，竞争失败返回当前版本。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public IamDtos.RoleResponse patch(UUID roleId, IamDtos.RolePatchRequest request,
                                      long expectedLockVersion) {
        TenantContext context = IamSupport.context();
        IamSupport.require(PermissionCode.IAM_ROLE_MANAGE);
        if (request.roleName() == null && request.status() == null) {
            throw ApiException.unprocessable("VALIDATION_FAILED", "至少提供一个可修改字段。",
                    List.of(new com.flowdesk.common.web.FieldViolation(
                            "/body", "EMPTY_PATCH", "至少提供一个可修改字段。")));
        }
        RoleRow existing = repository.findRole(context.tenantId(), roleId)
                .orElseThrow(() -> ApiException.notFound("RESOURCE_NOT_FOUND", "角色不存在。"));
        if (existing.lockVersion() != expectedLockVersion) {
            throw IamSupport.versionConflict(IamSupport.etagHeaders(existing.lockVersion()));
        }
        Instant now = now();
        boolean updated = repository.updateRole(context.tenantId(), roleId, expectedLockVersion,
                request.roleName(), request.status() == null ? null : request.status().name(),
                IamSupport.actorId(), now);
        if (!updated) {
            RoleRow refreshed = repository.findRole(context.tenantId(), roleId)
                    .orElseThrow(() -> ApiException.notFound("RESOURCE_NOT_FOUND", "角色不存在。"));
            throw IamSupport.versionConflict(IamSupport.etagHeaders(refreshed.lockVersion()));
        }
        RoleRow updatedRow = repository.findRole(context.tenantId(), roleId)
                .orElseThrow(() -> ApiException.notFound("RESOURCE_NOT_FOUND", "角色不存在。"));
        try {
            auditRepository.insertAudit(context.tenantId(), UUID.randomUUID(), "IAM_ROLE_UPDATE", "ROLE",
                    roleId, "SUCCEEDED", "USER", IamSupport.actorId(), null, null,
                    context.correlationId(), now);
        } catch (DuplicateKeyException e) {
            throw ApiException.internal("审计记录写入失败。");
        }
        return toResponse(updatedRow);
    }

    /**
     * 将角色数据库投影转换为角色响应，使用固定领域角色编码并保留资源版本。
     */
    private IamDtos.RoleResponse toResponse(RoleRow row) {
        return new IamDtos.RoleResponse(row.roleId(), RoleCode.valueOf(row.roleCode()),
                row.roleName(), row.status(), row.lockVersion(), row.createdAt(), row.updatedAt());
    }

    /**
     * 校验角色排序字段并使用契约默认值，防止未知排序参与 SQL 或分页签名。
     */
    private static String normalizeRoleSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return "roleName";
        }
        return switch (sort) {
            case "roleName", "-roleName", "roleCode", "-roleCode" -> sort;
            default -> throw ApiException.badRequest("SORT_UNSUPPORTED", "不支持的排序字段。");
        };
    }

    /**
     * 从数据库投影取出当前排序字段的值，作为下一页签名游标的位置。
     */
    private static String sortValue(RoleRow row, String column) {
        return "roleCode".equals(column) ? row.roleCode() : row.roleName();
    }

    /**
     * 读取当前时间并截断到微秒，使写入与数据库时间精度一致。
     */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }


}
