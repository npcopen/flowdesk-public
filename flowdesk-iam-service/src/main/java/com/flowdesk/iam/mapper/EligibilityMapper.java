package com.flowdesk.iam.mapper;

import com.flowdesk.iam.model.*;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.time.Instant;
import java.util.*;

/**
 * 用户业务资格检查所需事实的数据访问接口，所有查询限定在指定租户内。
 * 返回用户状态、角色状态和授权状态等数据库事实，由服务层组合判断处理人等业务资格。
 */
@Mapper
public interface EligibilityMapper {

    /** 查询租户用户的当前状态；用户不存在时返回空 Optional，不过滤停用用户。 */
    Optional<String> findUserStatus(@Param("tenantId") UUID tenantId, @Param("userId") UUID userId);

    /**
     * 查询用户已获授的指定编码角色的状态；绑定须为 GRANTED，但角色可以已停用。
     * 角色或有效绑定不存在时返回空 Optional，供服务层区分未授权与角色停用。
     */
    Optional<String> findGrantedRoleStatus(@Param("tenantId") UUID tenantId, @Param("userId") UUID userId, @Param("roleCode") String roleCode);

    /**
     * 一次查询用户、指定编码角色及其绑定的资格事实，角色和绑定通过左连接保留缺失情况。
     * qualificationVersion 汇总三类记录的存在性与锁版本；仅用户不存在时整体返回空 Optional。
     */
    Optional<EligibilityFacts> findEligibilityFacts(@Param("tenantId") UUID tenantId, @Param("userId") UUID userId, @Param("roleCode") String roleCode);

    /** 检查用户是否获授指定编码的启用角色；仅判断授权与角色状态，不额外检查用户状态。 */
    boolean hasGrantedRoleCode(@Param("tenantId") UUID tenantId, @Param("userId") UUID userId, @Param("roleCode") String roleCode);
}
