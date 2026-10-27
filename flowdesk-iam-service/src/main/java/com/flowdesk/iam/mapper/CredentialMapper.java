package com.flowdesk.iam.mapper;

import com.flowdesk.iam.model.*;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.time.Instant;
import java.util.*;

/**
 * 登录租户定位与用户密码凭据的数据访问接口。
 * 负责加载凭据及维护失败计数、临时锁定信息；密码验证和锁定阈值由服务层决定。
 */
@Mapper
public interface CredentialMapper {

    /** 按登录提供的租户编码定位租户 ID，按 ID 排序；此时尚未确定 tenantId。 */
    List<UUID> findTenantIdsByCode(@Param("tenantCode") String tenantCode);

    /** 检查指定租户 ID 是否存在；只检查记录存在性，不判断租户或用户状态。 */
    boolean tenantExists(@Param("tenantId") UUID tenantId);

    /**
     * 按租户和用户名联表读取用户状态、租户信息、密码哈希及登录失败/锁定状态。
     * 用户或凭据缺失时返回空 Optional；SQL 不提前过滤停用或锁定用户。
     */
    Optional<CredentialRow> findCredential(@Param("tenantId") UUID tenantId, @Param("username") String username);

    /** 登录成功后清零失败次数并解除临时锁定，更新时间由数据库生成，操作方记为服务。 */
    void updateLoginSuccess(@Param("tenantId") UUID tenantId, @Param("userId") UUID userId);

    /**
     * 为租户用户保存密码哈希及哈希方案，凭据类型固定为 PASSWORD。
     * 初始失败次数、锁版本为 0，锁定截止时间为空，并记录密码变更及创建信息。
     */
    void insertCredential(@Param("tenantId") UUID tenantId, @Param("userId") UUID userId, @Param("passwordHash") byte[] passwordHash, @Param("scheme") String scheme, @Param("actorId") UUID actorId, @Param("now") Instant now);

    /**
     * 原子累计一次登录失败，避免服务层读取后再写回造成计数覆盖。
     * 若原锁定已在 now 前到期，则以本次失败重新从 1 计数并清空过期锁定。
     */
    void incrementFailedAttempt(@Param("tenantId") UUID tenantId, @Param("userId") UUID userId,
                                @Param("now") Instant now);
    /**
     * 仅在当前失败次数达到 maxFailedAttempts 时，将凭据锁定至 lockUntil。
     * 阈值条件在 UPDATE 中判断，未达到阈值时不修改该用户凭据。
     */
    void lockAfterFailedAttempt(@Param("tenantId") UUID tenantId, @Param("userId") UUID userId,
                                @Param("maxFailedAttempts") int maxFailedAttempts,
                                @Param("lockUntil") Instant lockUntil);
}
