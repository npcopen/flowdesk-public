package com.flowdesk.iam.service.impl;

import com.flowdesk.iam.model.*;
import com.flowdesk.iam.service.*;

import com.flowdesk.common.authz.PermissionCode;
import com.flowdesk.common.tenant.TenantContext;
import com.flowdesk.common.web.ApiException;
import com.flowdesk.iam.dto.IamDtos;
import com.flowdesk.iam.idempotency.IdempotencyCodec;
import com.flowdesk.iam.mapper.AccessMapper;
import com.flowdesk.iam.mapper.AuditMapper;
import com.flowdesk.iam.mapper.UserMapper;
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
 * 管理权限目录、角色权限和用户角色关系；结合 HTTP 前置条件、权限上限及审计锚点处理并发和幂等。
 */
@Service
public class BindingServiceImpl implements BindingService {

    private final AccessMapper repository;
    private final UserMapper userRepository;
    private final AuditMapper auditRepository;
    private final CursorTokenService cursorTokenService;
    private final Clock clock;

    /**
     * 组装当前组件依赖并委托完整构造方法；使用 UTC 系统时钟等默认运行配置，显式依赖版本可用于确定性测试。
     */
    public BindingServiceImpl(AccessMapper repository, UserMapper userRepository,
                          AuditMapper auditRepository, CursorTokenService cursorTokenService) {
        this(repository, userRepository, auditRepository, cursorTokenService, Clock.systemUTC());
    }

    /**
     * 注入授权、用户与审计查询，签名游标服务及统一时钟，使授权写入和追加审计处于同一事务边界。
     */
    @Autowired
    public BindingServiceImpl(AccessMapper repository, UserMapper userRepository,
                          AuditMapper auditRepository, CursorTokenService cursorTokenService,
                          Clock clock) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.auditRepository = auditRepository;
        this.cursorTokenService = cursorTokenService;
        this.clock = clock;
    }

    // 权限目录与授权关系用例。

    /**
     * 校验权限目录读取权限，以白名单排序和签名游标查询当前租户的权限列表。
     */
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
        List<PermissionRow> rows = repository.findPermissions(context.tenantId(),
                sortColumn, ascending, cursor == null ? null : cursor.lastName(),
                cursor == null ? null : cursor.lastId(), pageSize);
        boolean hasMore = rows.size() > pageSize;
        if (hasMore) {
            rows = rows.subList(0, pageSize);
        }
        List<IamDtos.PermissionResponse> items = rows.stream().map(this::toPermission).toList();
        String nextToken = null;
        if (hasMore && !items.isEmpty()) {
            PermissionRow last = rows.get(rows.size() - 1);
            nextToken = cursorTokenService.encode(context.tenantId(), scope, pageSize,
                    normalizedSort, permissionSortValue(last, sortColumn), last.permissionId());
        }
        return new PageResult<>(items, nextToken, normalizedSort);
    }

    /**
     * 确认角色属于当前租户，按绑定状态筛选并按权限编码分页返回角色的权限关系。
     */
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
        List<BindingRow> rows = repository.findRolePermissions(
                context.tenantId(), roleId, stateFilter);
        Map<UUID, String> codes = repository.findPermissionCodes(context.tenantId(),
                rows.stream().map(BindingRow::targetId).toList());
        List<SortableBinding> all = rows.stream()
                .map(row -> new SortableBinding(toBinding(row),
                        codes.getOrDefault(row.targetId(), "UNKNOWN")))
                .toList();
        return pageInMemory(all, pageSize, pageToken, normalizedSort,
                "rolePermissions|" + stateFilter);
    }

    /**
     * 校验权限上限、写入前提和幂等意图后授予或撤销角色权限；保留组织管理员的基础 IAM 能力。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PutBindingResult putRolePermission(UUID roleId, UUID permissionId,
                                              IamDtos.BindingWriteRequest request,
                                              String ifMatch,
                                              String ifNoneMatch,
                                              String idempotencyKey) {
        TenantContext context = IamSupport.context();
        IamSupport.require(PermissionCode.IAM_BINDING_MANAGE);
        String key = IamSupport.requireIdempotencyKey(idempotencyKey);

        RoleRow role = repository.findRole(context.tenantId(), roleId)
                .orElseThrow(() -> ApiException.notFound("RESOURCE_NOT_FOUND", "角色不存在。"));
        PermissionRow permission = repository.findPermission(context.tenantId(), permissionId)
                .orElseThrow(() -> ApiException.notFound("RESOURCE_NOT_FOUND", "权限不存在。"));
        PermissionCode permissionCode = PermissionCode.valueOf(permission.permissionCode());
        // 保留组织管理员的 IAM 基础能力，避免撤权后租户失去维护授权关系的入口。
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

    /**
     * 确认用户属于当前租户，按绑定状态筛选并以角色编码分页返回用户的角色关系。
     */
    public PageResult<IamDtos.AccessBindingResponse> listUserRoles(UUID userId,
                                                                   String state,
                                                                   int pageSize,
                                                                   String pageToken,
                                                                   String sort) {
        TenantContext context = IamSupport.context();
        IamSupport.require(PermissionCode.IAM_BINDING_READ);
        if (userRepository.findUser(context.tenantId(), userId).isEmpty()) {
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
        List<BindingRow> rows = repository.findUserRoles(
                context.tenantId(), userId, stateFilter);
        Map<UUID, String> codes = repository.findRoleCodes(context.tenantId(),
                rows.stream().map(BindingRow::targetId).toList());
        List<SortableBinding> all = rows.stream()
                .map(row -> new SortableBinding(toBinding(row),
                        codes.getOrDefault(row.targetId(), "UNKNOWN")))
                .toList();
        return pageInMemory(all, pageSize, pageToken, normalizedSort,
                "userRoles|" + stateFilter);
    }

    /**
     * 按资源前提与幂等意图授予或撤销用户角色，禁止管理员撤销自身的组织管理入口。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PutBindingResult putUserRole(UUID userId, UUID roleId,
                                        IamDtos.BindingWriteRequest request,
                                        String ifMatch,
                                        String ifNoneMatch,
                                        String idempotencyKey) {
        TenantContext context = IamSupport.context();
        IamSupport.require(PermissionCode.IAM_BINDING_MANAGE);
        String key = IamSupport.requireIdempotencyKey(idempotencyKey);

        if (userRepository.findUser(context.tenantId(), userId).isEmpty()) {
            throw ApiException.notFound("RESOURCE_NOT_FOUND", "用户不存在。");
        }
        if (repository.findRole(context.tenantId(), roleId).isEmpty()) {
            throw ApiException.notFound("RESOURCE_NOT_FOUND", "角色不存在。");
        }

        // 禁止管理员自行撤销本人的管理角色，人员交接必须由另一位管理员办理。
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

    /**
     * 统一处理绑定新建、同状态重试与版本更新；核对幂等审计后将状态变更和操作审计一起提交。
     */
    private PutBindingResult putBinding(TenantContext context,
                                        String digest,
                                        UUID effectUuid,
                                        UUID subjectId,
                                        UUID targetId,
                                        String desiredState,
                                        BindingRow existing,
                                        String ifMatch,
                                        String ifNoneMatch,
                                        Runnable insert,
                                        java.util.function.Predicate<Long> update,
                                        String actionCode,
                                        String targetType) {
        // 先检查已提交的幂等审计；安全重试可以直接返回当前关系，不重复创建或变更。
        var existingAudit = auditRepository.findAuditReason(context.tenantId(), effectUuid);
        if (existingAudit.isPresent()) {
            if (!digest.equals(existingAudit.get())) {
                throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED",
                        "同一 Idempotency-Key 已用于不同业务意图。");
            }
            BindingRow current = reRead(context, subjectId, targetId, targetType);
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
                // 唯一键冲突可能来自同一请求的并发重试，也可能是其他请求先创建了关系。
                // 必须核对持久化审计锚点，不能把无关的唯一约束冲突当作幂等成功。
                return resolveInsertDuplicate(context, digest, effectUuid, subjectId, targetId,
                        targetType);
            }
            try {
                auditRepository.insertAudit(context.tenantId(), effectUuid, actionCode, targetType,
                        targetId, "SUCCEEDED", "USER", IamSupport.actorId(), digest, null,
                        context.correlationId(), now());
            } catch (DuplicateKeyException e) {
                throw ApiException.internal("审计记录写入失败。");
            }
            BindingRow created = reRead(context, subjectId, targetId, targetType);
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
            BindingRow refreshed = reRead(context, subjectId, targetId, targetType);
            long version = refreshed == null ? existing.lockVersion() : refreshed.lockVersion();
            throw IamSupport.versionConflict(IamSupport.etagHeaders(version));
        }
        try {
            auditRepository.insertAudit(context.tenantId(), effectUuid, actionCode, targetType,
                    targetId, "SUCCEEDED", "USER", IamSupport.actorId(), digest, null,
                    context.correlationId(), now());
        } catch (DuplicateKeyException e) {
            throw ApiException.internal("审计记录写入失败。");
        }
        BindingRow updated = reRead(context, subjectId, targetId, targetType);
        if (updated == null) {
            throw ApiException.internal("关系更新后不可见。");
        }
        return new PutBindingResult(toBinding(updated), false, false);
    }

    /**
     * 在并发写入后重新读取目标绑定，用数据库已提交事实决定重放结果或前提冲突。
     */
    private BindingRow reRead(TenantContext context, UUID subjectId,
                                            UUID targetId, String targetType) {
        if ("PERMISSION".equals(targetType)) {
            return repository.findRolePermission(context.tenantId(), subjectId, targetId).orElse(null);
        }
        return repository.findUserRole(context.tenantId(), subjectId, targetId).orElse(null);
    }

    /**
     * 读取当前时间并截断到微秒，使写入与数据库时间精度一致。
     */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    /** 重放路径使用当前读，确保能看到并发竞争中已经提交成功的关系。 */
    private BindingRow reReadForUpdate(TenantContext context, UUID subjectId,
                                                     UUID targetId, String targetType) {
        if ("PERMISSION".equals(targetType)) {
            return repository.findRolePermissionForUpdate(
                    context.tenantId(), subjectId, targetId).orElse(null);
        }
        return repository.findUserRoleForUpdate(context.tenantId(), subjectId, targetId).orElse(null);
    }

    /**
     * 处理资源插入的唯一键竞争，回读已提交审计及资源，区分安全幂等重试、业务重名和标识冲突。
     */
    private PutBindingResult resolveInsertDuplicate(TenantContext context, String digest,
                                                     UUID effectUuid, UUID subjectId,
                                                     UUID targetId, String targetType) {
        // 并发请求使用相同键重试时，只有意图摘要一致才允许返回既有结果。
        var existingAudit = auditRepository.findAuditReasonAfterDuplicate(context.tenantId(), effectUuid);
        if (existingAudit.isPresent()) {
            String existingDigest = existingAudit.get();
            if (!digest.equals(existingDigest)) {
                throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED",
                        "同一 Idempotency-Key 已用于不同业务意图。");
            }
            BindingRow current = reReadForUpdate(context, subjectId, targetId,
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

    /**
     * 将授权绑定快照映射为响应，保留授予、撤销时间及锁版本。
     */
    private IamDtos.AccessBindingResponse toBinding(BindingRow row) {
        // 仓储已按主体与目标的语义顺序映射字段，此处直接投影到响应。
        return new IamDtos.AccessBindingResponse(row.subjectId(), row.targetId(), row.state(),
                row.grantedAt(), row.revokedAt(), row.lockVersion());
    }

    /**
     * 按业务编码和目标标识建立稳定顺序，核对游标作用域后截取一页并生成续查游标。
     */
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

    /**
     * 比较绑定排序编码和目标标识，判断记录是否位于所给游标之后，兼容正向与反向排序。
     */
    private static boolean afterCursor(SortableBinding item, String lastName, UUID lastId,
                                       boolean ascending) {
        int valueComparison = item.sortValue().compareTo(lastName);
        if (valueComparison != 0) {
            return ascending ? valueComparison > 0 : valueComparison < 0;
        }
        int idComparison = item.binding().targetId().compareTo(lastId);
        return ascending ? idComparison > 0 : idComparison < 0;
    }

    /**
     * 创建与请求排序方向一致的绑定比较器，用资源标识打破同编码排序平局。
     */
    private static Comparator<SortableBinding> bindingComparator(String sort) {
        Comparator<SortableBinding> comparator = Comparator
                .comparing(SortableBinding::sortValue)
                .thenComparing(item -> item.binding().targetId());
        return sort.startsWith("-") ? comparator.reversed() : comparator;
    }

    /**
     * 把权限数据库投影转换为响应，使用领域枚举解释固定权限编码。
     */
    private IamDtos.PermissionResponse toPermission(PermissionRow row) {
        return new IamDtos.PermissionResponse(row.permissionId(),
                PermissionCode.valueOf(row.permissionCode()), row.permissionName(), row.description());
    }

    /**
     * 将权限排序字段限制在契约白名单内，缺省使用默认排序，拒绝任意数据库列名。
     */
    private static String normalizePermissionSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return "permissionCode";
        }
        return switch (sort) {
            case "permissionCode", "-permissionCode", "permissionName", "-permissionName" -> sort;
            default -> throw ApiException.badRequest("SORT_UNSUPPORTED", "不支持的排序字段。");
        };
    }

    /**
     * 按选中的权限排序字段读取游标值，使下一页延续同一物理排序列。
     */
    private static String permissionSortValue(PermissionRow row, String column) {
        return "permissionCode".equals(column) ? row.permissionCode() : row.permissionName();
    }

    /**
     * 绑定响应与业务排序编码的组合，供内存分页按编码及目标标识建立稳定顺序。
     */
    private record SortableBinding(IamDtos.AccessBindingResponse binding, String sortValue) {
    }


}
