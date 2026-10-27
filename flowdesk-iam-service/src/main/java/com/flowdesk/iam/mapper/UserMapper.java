package com.flowdesk.iam.mapper;

import com.flowdesk.iam.model.*;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.time.Instant;
import java.util.*;

/**
 * 租户用户账户的数据访问接口，负责用户详情、创建、更新及游标分页查询。
 * 所有用户操作均以 tenantId 隔离，状态校验和操作权限判断由服务层负责。
 */
@Mapper
public interface UserMapper {

    /** 联表读取当前用户及所属租户的展示信息；用户不存在时返回空 Optional。 */
    Optional<CurrentUserRow> findCurrentUser(@Param("tenantId") UUID tenantId, @Param("userId") UUID userId);

    /** 按租户和用户 ID 读取账户详情、锁版本及创建/更新时间，不过滤用户状态。 */
    Optional<UserRow> findUser(@Param("tenantId") UUID tenantId, @Param("userId") UUID userId);

    /**
     * 查询账户并使用 FOR UPDATE 锁定匹配行，供事务内校验和后续更新使用。
     * 不存在时返回空 Optional；行锁随调用方事务结束而释放。
     */
    Optional<UserRow> findUserForUpdate(@Param("tenantId") UUID tenantId, @Param("userId") UUID userId);

    /** 检查用户名在当前租户中是否已存在，包含停用账户，用于创建前的重名判断。 */
    boolean usernameExists(@Param("tenantId") UUID tenantId, @Param("username") String username);

    /** 新建用户账户并将锁版本初始化为 0，创建和更新审计信息使用 actorId 与 now。 */
    void insertUser(@Param("tenantId") UUID tenantId, @Param("userId") UUID userId, @Param("username") String username, @Param("displayName") String displayName, @Param("status") String status, @Param("actorId") UUID actorId, @Param("now") Instant now);

    /**
     * 按 expectedLockVersion 乐观锁更新展示名和状态，参数为 null 的字段保留原值。
     * 成功时递增锁版本并记录操作人；返回 false 表示用户不存在或锁版本不匹配。
     */
    boolean updateUser(@Param("tenantId") UUID tenantId, @Param("userId") UUID userId, @Param("expectedLockVersion") long expectedLockVersion, @Param("displayName") String displayName, @Param("status") String status, @Param("actorId") UUID actorId, @Param("now") Instant now);

    /**
     * 按可选账户状态及有效角色编码筛选用户，以展示名或用户名和用户 ID 组合排序。
     * lastName、lastId 为上一页末尾的排序值与 ID；最多读取 pageSize + 1 条以判断是否还有下一页。
     */
    List<UserRow> findUsers(@Param("tenantId") UUID tenantId, @Param("status") String status, @Param("roleCode") String roleCode, @Param("sortColumn") String sortColumn, @Param("ascending") boolean ascending, @Param("lastName") String lastName, @Param("lastId") UUID lastId, @Param("pageSize") int pageSize);
}
