package com.flowdesk.iam.mapper;

import com.flowdesk.iam.model.*;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.time.Instant;
import java.util.*;

/**
 * 角色、权限及授权关系的数据访问接口，由 MyBatis 执行对应 XML 中的 SQL。
 * 查询和写入均限定 tenantId；有效授权查询只返回已授予且角色启用的数据，
 * 授权管理查询则保留状态信息，供服务层判断授予、撤销和并发更新。
 */
@Mapper
public interface AccessMapper {

    /**
     * 查询租户内用户已获授的启用角色编码，按角色编码排序。
     * 已撤销的绑定和停用角色不计入结果；无匹配授权时返回空列表。
     */
    List<String> findGrantedRoleCodes(@Param("tenantId") UUID tenantId, @Param("userId") UUID userId);

    /**
     * 沿用户角色和角色权限关系查询用户的有效权限编码。
     * 两级绑定均须为 GRANTED，角色须为 ACTIVE；结果去重后按权限编码排序。
     */
    List<String> findGrantedPermissionCodes(@Param("tenantId") UUID tenantId, @Param("userId") UUID userId);

    /** 按租户和角色 ID 读取角色详情及锁版本；不存在时返回 Optional.empty()。 */
    Optional<RoleRow> findRole(@Param("tenantId") UUID tenantId, @Param("roleId") UUID roleId);

    /**
     * 按租户和角色 ID 查询角色并通过 FOR UPDATE 锁定匹配行。
     * 应在事务中使用，使服务层的后续校验与写入基于受锁保护的数据。
     */
    Optional<RoleRow> findRoleForUpdate(@Param("tenantId") UUID tenantId, @Param("roleId") UUID roleId);

    /** 按租户内角色编码查询角色，包含启用和停用状态；不存在时返回空 Optional。 */
    Optional<RoleRow> findRoleByCode(@Param("tenantId") UUID tenantId, @Param("roleCode") String roleCode);

    /**
     * 新建租户角色，使用调用方给定的编码、名称和状态。
     * 初始锁版本为 0，创建和更新信息均记录为 actorId 在 now 执行的用户操作。
     */
    void insertRole(@Param("tenantId") UUID tenantId, @Param("roleId") UUID roleId, @Param("roleCode") String roleCode, @Param("roleName") String roleName, @Param("status") String status, @Param("actorId") UUID actorId, @Param("now") Instant now);

    /**
     * 按 expectedLockVersion 乐观锁更新角色，名称或状态为 null 时保留原值。
     * 成功时递增锁版本并记录操作人和更新时间；返回 false 表示没有匹配行被更新。
     */
    boolean updateRole(@Param("tenantId") UUID tenantId, @Param("roleId") UUID roleId, @Param("expectedLockVersion") long expectedLockVersion, @Param("roleName") String roleName, @Param("status") String status, @Param("actorId") UUID actorId, @Param("now") Instant now);

    /**
     * 按可选状态查询租户角色，按角色名称或编码及角色 ID 组合排序。
     * lastName、lastId 共同表示上一页末尾游标；最多读取 pageSize + 1 条以判断下一页。
     */
    List<RoleRow> findRoles(@Param("tenantId") UUID tenantId, @Param("status") String status, @Param("sortColumn") String sortColumn, @Param("ascending") boolean ascending, @Param("lastName") String lastName, @Param("lastId") UUID lastId, @Param("pageSize") int pageSize);

    /** 按租户和权限 ID 查询权限编码、名称及说明；不存在时返回空 Optional。 */
    Optional<PermissionRow> findPermission(@Param("tenantId") UUID tenantId, @Param("permissionId") UUID permissionId);

    /**
     * 按权限名称或编码及权限 ID 组合排序，游标分页读取租户权限。
     * lastName 为选中排序列的游标值；最多返回 pageSize + 1 条，供服务层判断是否还有数据。
     */
    List<PermissionRow> findPermissions(@Param("tenantId") UUID tenantId, @Param("sortColumn") String sortColumn, @Param("ascending") boolean ascending, @Param("lastName") String lastName, @Param("lastId") UUID lastId, @Param("pageSize") int pageSize);

    /** 查询租户内指定角色与权限的绑定，保留授予或撤销状态、时间及锁版本。 */
    Optional<BindingRow> findRolePermission(@Param("tenantId") UUID tenantId, @Param("roleId") UUID roleId, @Param("permissionId") UUID permissionId);

    /**
     * 查询角色权限绑定并对匹配行加 FOR UPDATE 锁。
     * 不过滤绑定状态，供事务内的重复授予、撤销及状态变更校验使用。
     */
    Optional<BindingRow> findRolePermissionForUpdate(@Param("tenantId") UUID tenantId, @Param("roleId") UUID roleId, @Param("permissionId") UUID permissionId);

    /** 查询租户内指定用户与角色的绑定，包含当前状态、授予/撤销时间及锁版本。 */
    Optional<BindingRow> findUserRole(@Param("tenantId") UUID tenantId, @Param("userId") UUID userId, @Param("roleId") UUID roleId);

    /** 在事务内读取并锁定用户角色绑定；不存在时返回空 Optional，不过滤绑定状态。 */
    Optional<BindingRow> findUserRoleForUpdate(@Param("tenantId") UUID tenantId, @Param("userId") UUID userId, @Param("roleId") UUID roleId);

    /** 新建角色权限绑定，初始状态为 GRANTED、锁版本为 0，并记录授予人和授予时间。 */
    void insertRolePermission(@Param("tenantId") UUID tenantId, @Param("roleId") UUID roleId, @Param("permissionId") UUID permissionId, @Param("now") Instant now, @Param("actorId") UUID actorId);

    /** 新建用户角色绑定，初始状态为 GRANTED、锁版本为 0，撤销信息置空。 */
    void insertUserRole(@Param("tenantId") UUID tenantId, @Param("userId") UUID userId, @Param("roleId") UUID roleId, @Param("now") Instant now, @Param("actorId") UUID actorId);

    /**
     * 按锁版本更新角色权限绑定；GRANTED 分支重写授予信息并清空撤销信息，其他值走撤销分支。
     * 成功时递增版本；返回 false 表示绑定不存在或 expectedLockVersion 已失效。
     */
    boolean updateRolePermissionState(@Param("tenantId") UUID tenantId, @Param("roleId") UUID roleId, @Param("permissionId") UUID permissionId, @Param("expectedLockVersion") long expectedLockVersion, @Param("state") String state, @Param("now") Instant now, @Param("actorId") UUID actorId);

    /**
     * 按锁版本授予或撤销用户角色；重新授予时刷新授予信息并清空历史撤销信息。
     * 非 GRANTED 状态走撤销分支；成功递增锁版本，返回值表示是否更新到匹配行。
     */
    boolean updateUserRoleState(@Param("tenantId") UUID tenantId, @Param("userId") UUID userId, @Param("roleId") UUID roleId, @Param("expectedLockVersion") long expectedLockVersion, @Param("state") String state, @Param("now") Instant now, @Param("actorId") UUID actorId);

    /** 查询角色的权限绑定并按权限 ID 排序；state 为 null 时同时返回授予和撤销记录。 */
    List<BindingRow> findRolePermissions(@Param("tenantId") UUID tenantId, @Param("roleId") UUID roleId, @Param("state") String state);

    /** 查询用户的角色绑定并按角色 ID 排序；state 为 null 时不限制绑定状态。 */
    List<BindingRow> findUserRoles(@Param("tenantId") UUID tenantId, @Param("userId") UUID userId, @Param("state") String state);

    /**
     * 批量读取用户的有效角色编码，返回用户 ID 与角色编码的行投影。
     * 仅包含已授予的启用角色；userIds 须非空，调用方可使用下方默认方法处理空集合。
     */
    List<GrantedRoleRow> selectGrantedRoleCodesForUsers(@Param("tenantId") UUID tenantId,
                                                      @Param("userIds") Collection<UUID> userIds);
    /** 批量读取租户权限的 ID 与编码；permissionIds 须非空，不存在的 ID 不生成结果行。 */
    List<IdCodeRow> selectPermissionCodes(@Param("tenantId") UUID tenantId,
                                        @Param("permissionIds") Collection<UUID> permissionIds);
    /** 批量读取租户角色的 ID 与编码，不过滤角色状态；roleIds 须非空。 */
    List<IdCodeRow> selectRoleCodes(@Param("tenantId") UUID tenantId,
                                  @Param("roleIds") Collection<UUID> roleIds);

    /**
     * 将批量查询到的有效角色编码按用户 ID 分组，避免逐个用户查询。
     * 输入为空时直接返回空 Map；没有有效角色的用户不出现在返回 Map 中。
     */
    default Map<UUID, List<String>> findGrantedRoleCodesForUsers(UUID tenantId, Collection<UUID> userIds) {
        if (userIds.isEmpty()) return Map.of();
        Map<UUID, List<String>> result = new HashMap<>();
        for (GrantedRoleRow row : selectGrantedRoleCodesForUsers(tenantId, userIds)) {
            result.computeIfAbsent(row.userId(), ignored -> new ArrayList<>()).add(row.roleCode());
        }
        return result;
    }
    /** 将权限批量查询结果整理为 ID 到编码的映射；空集合不发起 SQL，缺失权限不填充占位值。 */
    default Map<UUID, String> findPermissionCodes(UUID tenantId, Collection<UUID> permissionIds) {
        if (permissionIds.isEmpty()) return Map.of();
        Map<UUID, String> result = new HashMap<>();
        for (IdCodeRow row : selectPermissionCodes(tenantId, permissionIds)) result.put(row.id(), row.code());
        return result;
    }
    /** 将角色批量查询结果整理为 ID 到编码的映射；空集合直接返回空 Map，包含停用角色。 */
    default Map<UUID, String> findRoleCodes(UUID tenantId, Collection<UUID> roleIds) {
        if (roleIds.isEmpty()) return Map.of();
        Map<UUID, String> result = new HashMap<>();
        for (IdCodeRow row : selectRoleCodes(tenantId, roleIds)) result.put(row.id(), row.code());
        return result;
    }
}
