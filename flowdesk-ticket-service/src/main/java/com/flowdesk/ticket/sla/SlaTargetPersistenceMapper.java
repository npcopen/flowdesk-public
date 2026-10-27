package com.flowdesk.ticket.sla;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
import java.util.UUID;
import java.sql.Timestamp;
import com.flowdesk.ticket.model.sla.SlaEscalationTargetRow;
import com.flowdesk.ticket.model.sla.SlaQualificationRow;

/**
 * SLA 升级通知接收目标及资格快照的持久化接口。
 * 按租户读取有效目标、保存 IAM 资格校验结果，并以目标版本保护资格状态和停用操作的并发更新。
 */
@Mapper
public interface SlaTargetPersistenceMapper {
    /**
     * 合并拥有 SLA 时钟或升级目标的租户，去重后按租户标识升序分页枚举，供后台维护遍历。
     *
     * @return 符合条件的结果列表，未命中时为空列表。
     */
    List<UUID> findTenantIdsForSlaMaintenanceSelect(@Param("afterTenantId") UUID afterTenantId, @Param("limit") int limit);
    /**
     * 查询租户内 purpose 为 SLA 且状态为 ACTIVE 的升级接收目标，返回资格状态和版本；lock 控制行锁。
     *
     * @return 符合条件的结果列表，未命中时为空列表。
     */
    List<SlaEscalationTargetRow> findActiveSlaEscalationTargetSelect(@Param("tenantId") byte[] tenantId, @Param("lock") boolean lock);
    /**
     * 按租户和目标标识读取接收目标及资格信息，不限制状态；lock 为 true 时追加 FOR UPDATE。
     *
     * @return 符合条件的结果列表，未命中时为空列表。
     */
    List<SlaEscalationTargetRow> findSlaEscalationTargetSelect(@Param("tenantId") byte[] tenantId, @Param("targetId") byte[] targetId, @Param("lock") boolean lock);
    /**
     * 创建升级接收目标，保存用途、用户、生效时间及资格版本和有效期，初始化版本为 0，并记录创建与更新主体。
     *
     * @return 实际影响行数，调用方据此判断条件更新、插入或续租是否生效。
     */
    int insertSlaEscalationTargetUpdate(@Param("tenantId") byte[] tenantId, @Param("targetId") byte[] targetId, @Param("purpose") String purpose, @Param("recipientUserId") byte[] recipientUserId, @Param("status") String status, @Param("effectiveAt") Timestamp effectiveAt, @Param("qualificationVersion") Long qualificationVersion, @Param("qualificationCheckedAt") Timestamp qualificationCheckedAt, @Param("qualificationValidUntil") Timestamp qualificationValidUntil, @Param("qualificationState") String qualificationState, @Param("qualificationReasonCode") String qualificationReasonCode, @Param("now") Timestamp now, @Param("actorType") String actorType, @Param("actorId") byte[] actorId, @Param("now2") Timestamp now2, @Param("actorType2") String actorType2, @Param("actorId2") byte[] actorId2);
    /**
     * 仅在目标仍为 ACTIVE 且 expectedLockVersion 匹配时停用，增加版本并记录操作主体。
     *
     * @return 实际影响行数，调用方据此判断条件更新、插入或续租是否生效。
     */
    int deactivateSlaEscalationTargetUpdate(@Param("now") Timestamp now, @Param("actorType") String actorType, @Param("actorId") byte[] actorId, @Param("tenantId") byte[] tenantId, @Param("targetId") byte[] targetId, @Param("expectedLockVersion") long expectedLockVersion);
    /**
     * 统计指定租户 purpose 为 SLA 且状态为 ACTIVE 的目标数量，供业务层校验有效目标配置。
     *
     * @return 符合条件的有效升级目标数量。
     */
    Integer countActiveSlaEscalationTargetsSelect(@Param("tenantId") byte[] tenantId);
    /**
     * 按租户及目标新增或覆盖 SLA 资格快照，保存收件人、资格版本、检查和过期时间、可用结论及原因码。
     *
     * @return 实际影响行数，调用方据此判断条件更新、插入或续租是否生效。
     */
    int upsertQualificationSnapshotUpdate(@Param("tenantId") byte[] tenantId, @Param("targetId") byte[] targetId, @Param("recipientUserId") byte[] recipientUserId, @Param("qualificationVersion") long qualificationVersion, @Param("checkedAt") Timestamp checkedAt, @Param("validUntil") Timestamp validUntil, @Param("eligible") boolean eligible, @Param("reasonCode") String reasonCode, @Param("now") Timestamp now);
    /**
     * 按租户和目标读取已保存的 SLA 资格校验快照；lock 为 true 时追加 FOR UPDATE。
     *
     * @return 符合条件的结果列表，未命中时为空列表。
     */
    List<SlaQualificationRow> findQualificationSnapshotSelect(@Param("tenantId") byte[] tenantId, @Param("targetId") byte[] targetId, @Param("lock") boolean lock);
    /**
     * 仅在目标用途为 SLA、状态为 ACTIVE 且 expectedVersion 匹配时刷新资格版本、有效期、状态和原因，并增加目标版本。
     *
     * @return 实际影响行数，调用方据此判断条件更新、插入或续租是否生效。
     */
    int updateSlaTargetQualificationUpdate(@Param("qualificationVersion") long qualificationVersion, @Param("checkedAt") Timestamp checkedAt, @Param("validUntil") Timestamp validUntil, @Param("state") String state, @Param("reason") String reason, @Param("now") Timestamp now, @Param("tenantId") byte[] tenantId, @Param("targetId") byte[] targetId, @Param("expectedVersion") long expectedVersion);
}
