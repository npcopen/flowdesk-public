package com.flowdesk.iam.service;

import com.flowdesk.common.authz.PermissionCode;
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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * API-011..015 permission catalog and role/user bindings.
 */
@Service
public class BindingService {

    private final IamRepository repository;
    private final CursorTokenService cursorTokenService;
    private final Clock clock;

    public BindingService(IamRepository repository, CursorTokenService cursorTokenService) {
        this(repository, cursorTokenService, Clock.systemUTC());
    }

    @Autowired
    public BindingService(IamRepository repository, CursorTokenService cursorTokenService,
                          Clock clock) {
        this.repository = repository;
        this.cursorTokenService = cursorTokenService;
        this.clock = clock;
    }

    // ----- permissions ---------------------------------------------------------

    public PageResult<IamDtos.PermissionResponse> listPermissions(int pageSize,
                                                                  String pageToken,
                                                                  String sort) {
        TenantContext context = IamSupport.context();
        IamSupport.require(PermissionCode.IAM_PERMISSION_READ);
        String normalizedSort = normalizePermissionSort(sort);
        boolean ascending = !normalizedSort.startsWith("-");
        String sortColumn = ascending ? normalizedSort : normalizedSort.substring(1);
        String scope = "permissions";
        CursorTokenService.Cursor cursor = cursorTokenService.decode(
                pageToken, context.tenantId(), scope, pageSize, normalizedSort);
        List<IamRepository.PermissionRow> rows = repository.findPermissions(context.tenantId(),
                sortColumn, ascending, cursor == null ? null : cursor.lastName(),
                cursor == null ? null : cursor.lastId(), pageSize);
        boolean hasMore = rows.size() > pageSize;
        if (hasMore) {
            rows = rows.subList(0, pageSize);
        }
        List<IamDtos.PermissionResponse> items = rows.stream().map(this::toPermission).toList();
        String nextToken = null;
        if (hasMore && !items.isEmpty()) {
            IamRepository.PermissionRow last = rows.get(rows.size() - 1);
            nextToken = cursorTokenService.encode(context.tenantId(), scope, pageSize,
                    normalizedSort, permissionSortValue(last, sortColumn), last.permissionId());
        }
        return new PageResult<>(items, nextToken, normalizedSort);
    }

    public PageResult<IamDtos.AccessBindingResponse> listRolePermissions(UUID roleId,
                                                                         String state,
                                                                         int pageSize,
                                                                         String pageToken,
                                                                         String sort) {
        TenantContext context = IamSupport.context();
        IamSupport.require(PermissionCode.IAM_BINDING_READ);
        if (repository.findRole(context.tenantId(), roleId).isEmpty()) {
            throw ApiException.notFound("RESOURCE_NOT_FOUND", "角色不存在。");
        }
        if (sort != null && !"permissionCode".equals(sort) && !"-permissionCode".equals(sort)) {
            throw ApiException.badRequest("SORT_UNSUPPORTED", "不支持的排序字段。");
        }
        String normalizedSort = sort == null || sort.isBlank() ? "permissionCode" : sort;
        String stateFilter = state == null ? null
                : switch (state) {
                    case "GRANTED", "REVOKED" -> state;
                    default -> throw ApiException.badRequest(
                            "INVALID_QUERY_PARAMETER", "state 只接受 GRANTED/REVOKED。");
                };
        List<IamRepository.BindingRow> rows = repository.findRolePermissions(
                context.tenantId(), roleId, stateFilter);
        Map<UUID, String> codes = repository.findPermissionCodes(context.tenantId(),
                rows.stream().map(IamRepository.BindingRow::targetId).toList());
        List<SortableBinding> all = rows.stream()
                .map(row -> new SortableBinding(toBinding(row),
                        codes.getOrDefault(row.targetId(), "UNKNOWN")))
                .toList();
        return pageInMemory(all, pageSize, pageToken, normalizedSort,
                "rolePermissions|" + stateFilter);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PutBindingResult putRolePermission(UUID roleId, UUID permissionId,
                                              IamDtos.BindingWriteRequest request,
                                              String ifMatch,
                                              String ifNoneMatch,
                                              String idempotencyKey) {
        TenantContext context = IamSupport.context();
        IamSupport.require(PermissionCode.IAM_BINDING_MANAGE);
        String key = IamSupport.requireIdempotencyKey(idempotencyKey);

        IamRepository.RoleRow role = repository.findRole(context.tenantId(), roleId)
                .orElseThrow(() -> ApiException.notFound("RESOURCE_NOT_FOUND", "角色不存在。"));
        IamRepository.PermissionRow permission = repository.findPermission(context.tenantId(), permissionId)
                .orElseThrow(() -> ApiException.notFound("RESOURCE_NOT_FOUND", "权限不存在。"));
        PermissionCode permissionCode = PermissionCode.valueOf(permission.permissionCode());
        if ("TENANT_ADMIN".equals(role.roleCode()) && permissionCode.name().startsWith("IAM_")
                && request.state() == IamDtos.BindingWriteRequest.BindingState.REVOKED) {
            throw ApiException.conflict("IAM_CORE_PERMISSION_REQUIRED", "组织管理员的基础管理权限需要保留，请通过成员角色分配调整管理人员。");
        }
        if (!permissionCode.isAllowedFor(com.flowdesk.common.authz.RoleCode.valueOf(role.roleCode()))) {
            throw ApiException.unprocessable("PERMISSION_CEILING_VIOLATION",
                    "权限超出目标角色固定上限。",
                    List.of(new com.flowdesk.common.web.FieldViolation(
                            "/body/state", "CEILING_VIOLATION", "权限超出目标角色固定上限。")));
        }

        String intent = CanonicalIntent.of("subjectId", roleId.toString(), "targetId",
                permissionId.toString(), "state", request.state().name());
        String digest = IdempotencyCodec.requestDigestHex(intent);
        String resourceScope = roleId + "|" + permissionId;
        UUID effectUuid = IdempotencyCodec.effectKeyUuid(context, "API-013", resourceScope, key);

        return putBinding(context, digest, effectUuid,
                roleId, permissionId, request.state().name(),
                repository.findRolePermission(context.tenantId(), roleId, permissionId).orElse(null),
                ifMatch, ifNoneMatch,
                () -> repository.insertRolePermission(context.tenantId(), roleId, permissionId,
                        now(), IamSupport.actorId()),
                (expectedVersion) -> repository.updateRolePermissionState(context.tenantId(), roleId,
                        permissionId, expectedVersion, request.state().name(),
                        now(), IamSupport.actorId()),
                "IAM_ROLE_PERMISSION_PUT", "PERMISSION");
    }

    public PageResult<IamDtos.AccessBindingResponse> listUserRoles(UUID userId,
                                                                   String state,
                                                                   int pageSize,
                                                                   String pageToken,
                                                                   String sort) {
        TenantContext context = IamSupport.context();
        IamSupport.require(PermissionCode.IAM_BINDING_READ);
        if (repository.findUser(context.tenantId(), userId).isEmpty()) {
            throw ApiException.notFound("RESOURCE_NOT_FOUND", "用户不存在。");
        }
        if (sort != null && !"roleCode".equals(sort) && !"-roleCode".equals(sort)) {
            throw ApiException.badRequest("SORT_UNSUPPORTED", "不支持的排序字段。");
        }
        String normalizedSort = sort == null || sort.isBlank() ? "roleCode" : sort;
        String stateFilter = state == null ? null
                : switch (state) {
                    case "GRANTED", "REVOKED" -> state;
                    default -> throw ApiException.badRequest(
                            "INVALID_QUERY_PARAMETER", "state 只接受 GRANTED/REVOKED。");
                };
        List<IamRepository.BindingRow> rows = repository.findUserRoles(
                context.tenantId(), userId, stateFilter);
        Map<UUID, String> codes = repository.findRoleCodes(context.tenantId(),
                rows.stream().map(IamRepository.BindingRow::targetId).toList());
        List<SortableBinding> all = rows.stream()
                .map(row -> new SortableBinding(toBinding(row),
                        codes.getOrDefault(row.targetId(), "UNKNOWN")))
                .toList();
        return pageInMemory(all, pageSize, pageToken, normalizedSort,
                "userRoles|" + stateFilter);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PutBindingResult putUserRole(UUID userId, UUID roleId,
                                        IamDtos.BindingWriteRequest request,
                                        String ifMatch,
                                        String ifNoneMatch,
                                        String idempotencyKey) {
        TenantContext context = IamSupport.context();
        IamSupport.require(PermissionCode.IAM_BINDING_MANAGE);
        String key = IamSupport.requireIdempotencyKey(idempotencyKey);

        if (repository.findUser(context.tenantId(), userId).isEmpty()) {
            throw ApiException.notFound("RESOURCE_NOT_FOUND", "用户不存在。");
        }
        if (repository.findRole(context.tenantId(), roleId).isEmpty()) {
            throw ApiException.notFound("RESOURCE_NOT_FOUND", "角色不存在。");
        }

        if (userId.equals(IamSupport.actorId()) && request.state() == IamDtos.BindingWriteRequest.BindingState.REVOKED
                && repository.findRole(context.tenantId(),roleId).filter(r -> "TENANT_ADMIN".equals(r.roleCode())).isPresent()) {
            throw ApiException.conflict("IAM_SELF_ADMIN_REQUIRED", "不能移除自己的组织管理员角色，请由另一位组织管理员办理交接。");
        }
        String intent = CanonicalIntent.of("subjectId", userId.toString(), "targetId",
                roleId.toString(), "state", request.state().name());
        String digest = IdempotencyCodec.requestDigestHex(intent);
        String resourceScope = userId + "|" + roleId;
        UUID effectUuid = IdempotencyCodec.effectKeyUuid(context, "API-015", resourceScope, key);

        return putBinding(context, digest, effectUuid,
                userId, roleId, request.state().name(),
                repository.findUserRole(context.tenantId(), userId, roleId).orElse(null),
                ifMatch, ifNoneMatch,
                () -> repository.insertUserRole(context.tenantId(), userId, roleId,
                        now(), IamSupport.actorId()),
                (expectedVersion) -> repository.updateUserRoleState(context.tenantId(), userId, roleId,
                        expectedVersion, request.state().name(), now(), IamSupport.actorId()),
                "IAM_USER_ROLE_PUT", "ROLE");
    }

    private PutBindingResult putBinding(TenantContext context,
                                        String digest,
                                        UUID effectUuid,
                                        UUID subjectId,
                                        UUID targetId,
                                        String desiredState,
                                        IamRepository.BindingRow existing,
                                        String ifMatch,
                                        String ifNoneMatch,
                                        Runnable insert,
                                        java.util.function.Predicate<Long> update,
                                        String actionCode,
                                        String targetType) {
        var existingAudit = repository.findAuditReason(context.tenantId(), effectUuid);
        if (existingAudit.isPresent()) {
            if (!digest.equals(existingAudit.get())) {
                throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED",
                        "同一 Idempotency-Key 已用于不同业务意图。");
            }
            IamRepository.BindingRow current = reRead(context, subjectId, targetId, targetType);
            if (current == null) {
                throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED", "幂等锚点存在但关系缺失，拒绝重试。");
            }
            return new PutBindingResult(toBinding(current), false, true);
        }

        if (existing == null) {
            if (!"GRANTED".equals(desiredState)) {
                throw ApiException.conflict("BINDING_STATE_CONFLICT", "新关系只能为 GRANTED。");
            }
            IamSupport.requireCreatePrecondition(ifMatch, ifNoneMatch);
            try {
                insert.run();
            } catch (DuplicateKeyException e) {
                // A duplicate from the binding insert can be either the same
                // idempotent request winning a race or another request creating the
                // relationship first.  Inspect the durable anchors before choosing
                // the replay path; an unrelated unique constraint must not masquerade
                // as an idempotent replay.
                return resolveInsertDuplicate(context, digest, effectUuid, subjectId, targetId,
                        targetType);
            }
            try {
                repository.insertAudit(context.tenantId(), effectUuid, actionCode, targetType,
                        targetId, "SUCCEEDED", "USER", IamSupport.actorId(), digest, null,
                        context.correlationId(), now());
            } catch (DuplicateKeyException e) {
                throw ApiException.internal("审计记录写入失败。");
            }
            IamRepository.BindingRow created = reRead(context, subjectId, targetId, targetType);
            if (created == null) {
                throw ApiException.internal("关系创建后不可见。");
            }
            return new PutBindingResult(toBinding(created), true, false);
        }

        IamSupport.requireUpdatePrecondition(ifMatch, ifNoneMatch);
        long expectedVersion = IamSupport.parseIfMatch(ifMatch);
        if (existing.lockVersion() != expectedVersion) {
            throw IamSupport.versionConflict(IamSupport.etagHeaders(existing.lockVersion()));
        }
        if (existing.state().equals(desiredState)) {
            return new PutBindingResult(toBinding(existing), false, false);
        }
        if (!update.test(expectedVersion)) {
            IamRepository.BindingRow refreshed = reRead(context, subjectId, targetId, targetType);
            long version = refreshed == null ? existing.lockVersion() : refreshed.lockVersion();
            throw IamSupport.versionConflict(IamSupport.etagHeaders(version));
        }
        try {
            repository.insertAudit(context.tenantId(), effectUuid, actionCode, targetType,
                    targetId, "SUCCEEDED", "USER", IamSupport.actorId(), digest, null,
                    context.correlationId(), now());
        } catch (DuplicateKeyException e) {
            throw ApiException.internal("审计记录写入失败。");
        }
        IamRepository.BindingRow updated = reRead(context, subjectId, targetId, targetType);
        if (updated == null) {
            throw ApiException.internal("关系更新后不可见。");
        }
        return new PutBindingResult(toBinding(updated), false, false);
    }

    private IamRepository.BindingRow reRead(TenantContext context, UUID subjectId,
                                            UUID targetId, String targetType) {
        if ("PERMISSION".equals(targetType)) {
            return repository.findRolePermission(context.tenantId(), subjectId, targetId).orElse(null);
        }
        return repository.findUserRole(context.tenantId(), subjectId, targetId).orElse(null);
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    /** Current read used by replay paths so a competing committed winner is always visible. */
    private IamRepository.BindingRow reReadForUpdate(TenantContext context, UUID subjectId,
                                                     UUID targetId, String targetType) {
        if ("PERMISSION".equals(targetType)) {
            return repository.findRolePermissionForUpdate(
                    context.tenantId(), subjectId, targetId).orElse(null);
        }
        return repository.findUserRoleForUpdate(context.tenantId(), subjectId, targetId).orElse(null);
    }

    private PutBindingResult resolveInsertDuplicate(TenantContext context, String digest,
                                                     UUID effectUuid, UUID subjectId,
                                                     UUID targetId, String targetType) {
        // Same intent under the same key replayed by a concurrent request.
        var existingAudit = repository.findAuditReasonAfterDuplicate(context.tenantId(), effectUuid);
        if (existingAudit.isPresent()) {
            String existingDigest = existingAudit.get();
            if (!digest.equals(existingDigest)) {
                throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED",
                        "同一 Idempotency-Key 已用于不同业务意图。");
            }
            IamRepository.BindingRow current = reReadForUpdate(context, subjectId, targetId,
                    targetType);
            if (current == null) {
                throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED",
                        "幂等锚点存在但关系缺失，拒绝重试。");
            }
            return new PutBindingResult(toBinding(current), false, true);
        }

        if (reRead(context, subjectId, targetId, targetType) != null) {
            throw ApiException.preconditionFailed("RESOURCE_PRECONDITION_FAILED",
                    "If-None-Match:* 前提失败，目标关系已存在。", Map.of());
        }
        throw ApiException.internal("关系写入失败。");
    }

    private IamDtos.AccessBindingResponse toBinding(IamRepository.BindingRow row) {
        // subjectId/targetId are already stored by the row mapper in the correct order.
        return new IamDtos.AccessBindingResponse(row.subjectId(), row.targetId(), row.state(),
                row.grantedAt(), row.revokedAt(), row.lockVersion());
    }

    private PageResult<IamDtos.AccessBindingResponse> pageInMemory(
            List<SortableBinding> all, int pageSize, String pageToken,
            String sort, String scope) {
        TenantContext context = IamSupport.context();
        CursorTokenService.Cursor cursor = cursorTokenService.decode(
                pageToken, context.tenantId(), scope, pageSize, sort);
        boolean ascending = !sort.startsWith("-");
        List<SortableBinding> sorted = all.stream().sorted(bindingComparator(sort)).toList();
        List<SortableBinding> filtered = sorted;
        if (cursor != null) {
            UUID lastId = cursor.lastId();
            String lastName = cursor.lastName();
            filtered = sorted.stream()
                    .filter(item -> afterCursor(item, lastName, lastId, ascending))
                    .toList();
        }
        boolean hasMore = filtered.size() > pageSize;
        if (hasMore) {
            filtered = filtered.subList(0, pageSize);
        }
        List<IamDtos.AccessBindingResponse> items = filtered.stream()
                .map(SortableBinding::binding).toList();
        String nextToken = null;
        if (hasMore && !items.isEmpty()) {
            SortableBinding last = filtered.get(filtered.size() - 1);
            nextToken = cursorTokenService.encode(context.tenantId(), scope, pageSize, sort,
                    last.sortValue(), last.binding().targetId());
        }
        return new PageResult<>(items, nextToken, sort);
    }

    private static boolean afterCursor(SortableBinding item, String lastName, UUID lastId,
                                       boolean ascending) {
        int valueComparison = item.sortValue().compareTo(lastName);
        if (valueComparison != 0) {
            return ascending ? valueComparison > 0 : valueComparison < 0;
        }
        int idComparison = item.binding().targetId().compareTo(lastId);
        return ascending ? idComparison > 0 : idComparison < 0;
    }

    private static Comparator<SortableBinding> bindingComparator(String sort) {
        Comparator<SortableBinding> comparator = Comparator
                .comparing(SortableBinding::sortValue)
                .thenComparing(item -> item.binding().targetId());
        return sort.startsWith("-") ? comparator.reversed() : comparator;
    }

    private IamDtos.PermissionResponse toPermission(IamRepository.PermissionRow row) {
        return new IamDtos.PermissionResponse(row.permissionId(),
                PermissionCode.valueOf(row.permissionCode()), row.permissionName(), row.description());
    }

    private static String normalizePermissionSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return "permissionCode";
        }
        return switch (sort) {
            case "permissionCode", "-permissionCode", "permissionName", "-permissionName" -> sort;
            default -> throw ApiException.badRequest("SORT_UNSUPPORTED", "不支持的排序字段。");
        };
    }

    private static String permissionSortValue(IamRepository.PermissionRow row, String column) {
        return "permissionCode".equals(column) ? row.permissionCode() : row.permissionName();
    }

    private record SortableBinding(IamDtos.AccessBindingResponse binding, String sortValue) {
    }

    public record PutBindingResult(IamDtos.AccessBindingResponse binding, boolean created,
                                   boolean replayed) {
    }
}
