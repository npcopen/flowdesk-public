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
import java.util.UUID;

/**
 * API-007..010 role management.
 */
@Service
public class RoleManagementService {

    private final IamRepository repository;
    private final CursorTokenService cursorTokenService;
    private final Clock clock;

    public RoleManagementService(IamRepository repository, CursorTokenService cursorTokenService) {
        this(repository, cursorTokenService, Clock.systemUTC());
    }

    @Autowired
    public RoleManagementService(IamRepository repository, CursorTokenService cursorTokenService,
                                 Clock clock) {
        this.repository = repository;
        this.cursorTokenService = cursorTokenService;
        this.clock = clock;
    }

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
        List<IamRepository.RoleRow> rows = repository.findRoles(context.tenantId(), statusFilter,
                sortColumn, ascending, cursor == null ? null : cursor.lastName(),
                cursor == null ? null : cursor.lastId(), pageSize);
        boolean hasMore = rows.size() > pageSize;
        if (hasMore) {
            rows = rows.subList(0, pageSize);
        }
        List<IamDtos.RoleResponse> items = rows.stream().map(this::toResponse).toList();
        String nextToken = null;
        if (hasMore && !items.isEmpty()) {
            IamRepository.RoleRow last = rows.get(rows.size() - 1);
            nextToken = cursorTokenService.encode(context.tenantId(), scope, pageSize,
                    normalizedSort, sortValue(last, sortColumn), last.roleId());
        }
        return new PageResult<>(items, nextToken, normalizedSort);
    }

    public IamDtos.RoleResponse get(UUID roleId) {
        TenantContext context = IamSupport.context();
        IamSupport.require(PermissionCode.IAM_ROLE_READ);
        return repository.findRole(context.tenantId(), roleId)
                .map(this::toResponse)
                .orElseThrow(() -> ApiException.notFound("RESOURCE_NOT_FOUND", "角色不存在。"));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CreateRoleResult create(IamDtos.RoleCreateRequest request, String idempotencyKey) {
        TenantContext context = IamSupport.context();
        IamSupport.require(PermissionCode.IAM_ROLE_MANAGE);
        String key = IamSupport.requireIdempotencyKey(idempotencyKey);
        String intent = CanonicalIntent.of("roleCode", request.roleCode().name(),
                "roleName", request.roleName(), "status", request.status().name());
        String digest = IdempotencyCodec.requestDigestHex(intent);
        UUID stableRoleId = IdempotencyCodec.effectKeyUuid(
                context, "API-008", "COLLECTION", key);

        var existingAudit = repository.findAuditReason(context.tenantId(), stableRoleId);
        if (existingAudit.isPresent()) {
            if (!digest.equals(existingAudit.get())) {
                throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED",
                        "同一 Idempotency-Key 已用于不同业务意图。");
            }
            IamRepository.RoleRow existing = repository.findRoleForUpdate(context.tenantId(), stableRoleId)
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
            repository.insertAudit(context.tenantId(), stableRoleId, "IAM_ROLE_CREATE", "ROLE",
                    stableRoleId, "SUCCEEDED", "USER", IamSupport.actorId(), digest, null,
                    context.correlationId(), now);
        } catch (DuplicateKeyException e) {
            throw ApiException.internal("审计记录写入失败。");
        }
        return new CreateRoleResult(toResponse(
                repository.findRole(context.tenantId(), stableRoleId).orElseThrow()), false);
    }

    private CreateRoleResult resolveInsertDuplicate(TenantContext context, UUID stableRoleId,
                                                    String roleCode, String digest) {
        var existingAudit = repository.findAuditReasonAfterDuplicate(context.tenantId(), stableRoleId);
        if (existingAudit.isPresent()) {
            if (!digest.equals(existingAudit.get())) {
                throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED",
                        "同一 Idempotency-Key 已用于不同业务意图。");
            }
            IamRepository.RoleRow existing = repository.findRoleForUpdate(context.tenantId(), stableRoleId)
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
        IamRepository.RoleRow existing = repository.findRole(context.tenantId(), roleId)
                .orElseThrow(() -> ApiException.notFound("RESOURCE_NOT_FOUND", "角色不存在。"));
        if (existing.lockVersion() != expectedLockVersion) {
            throw IamSupport.versionConflict(IamSupport.etagHeaders(existing.lockVersion()));
        }
        Instant now = now();
        boolean updated = repository.updateRole(context.tenantId(), roleId, expectedLockVersion,
                request.roleName(), request.status() == null ? null : request.status().name(),
                IamSupport.actorId(), now);
        if (!updated) {
            IamRepository.RoleRow refreshed = repository.findRole(context.tenantId(), roleId)
                    .orElseThrow(() -> ApiException.notFound("RESOURCE_NOT_FOUND", "角色不存在。"));
            throw IamSupport.versionConflict(IamSupport.etagHeaders(refreshed.lockVersion()));
        }
        IamRepository.RoleRow updatedRow = repository.findRole(context.tenantId(), roleId)
                .orElseThrow(() -> ApiException.notFound("RESOURCE_NOT_FOUND", "角色不存在。"));
        try {
            repository.insertAudit(context.tenantId(), UUID.randomUUID(), "IAM_ROLE_UPDATE", "ROLE",
                    roleId, "SUCCEEDED", "USER", IamSupport.actorId(), null, null,
                    context.correlationId(), now);
        } catch (DuplicateKeyException e) {
            throw ApiException.internal("审计记录写入失败。");
        }
        return toResponse(updatedRow);
    }

    private IamDtos.RoleResponse toResponse(IamRepository.RoleRow row) {
        return new IamDtos.RoleResponse(row.roleId(), RoleCode.valueOf(row.roleCode()),
                row.roleName(), row.status(), row.lockVersion(), row.createdAt(), row.updatedAt());
    }

    private static String normalizeRoleSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return "roleName";
        }
        return switch (sort) {
            case "roleName", "-roleName", "roleCode", "-roleCode" -> sort;
            default -> throw ApiException.badRequest("SORT_UNSUPPORTED", "不支持的排序字段。");
        };
    }

    private static String sortValue(IamRepository.RoleRow row, String column) {
        return "roleCode".equals(column) ? row.roleCode() : row.roleName();
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    public record CreateRoleResult(IamDtos.RoleResponse role, boolean replayed) {
    }
}
