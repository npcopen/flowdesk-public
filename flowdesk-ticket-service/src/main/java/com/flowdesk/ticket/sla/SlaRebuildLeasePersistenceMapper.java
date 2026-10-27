package com.flowdesk.ticket.sla;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
import java.sql.Timestamp;
import com.flowdesk.ticket.model.sla.SlaRebuildLeaseRow;

/**
 * 租户 SLA 索引重建租约的持久化接口，保存运行标识、高水位、阶段和过期时间。
 * 通过运行标识与递增 fencingEpoch 隔离新旧执行者，防止过期任务继续推进重建。
 */
@Mapper
public interface SlaRebuildLeasePersistenceMapper {
    /**
     * 按租户读取重建租约、高水位、运行阶段及版本，并追加 FOR UPDATE，供同一事务串行领取或决策。
     *
     * @return 符合条件的结果列表，未命中时为空列表。
     */
    List<SlaRebuildLeaseRow> findSlaRebuildLeaseForUpdateSelect(@Param("tenantId") byte[] tenantId);
    /**
     * 按租户读取当前重建租约和进度，不加行锁，供查询当前执行状态。
     *
     * @return 符合条件的结果列表，未命中时为空列表。
     */
    List<SlaRebuildLeaseRow> findSlaRebuildLeaseSelect(@Param("tenantId") byte[] tenantId);
    /**
     * 不存在时初始化租户的 IDLE 租约和 0 代 fencingEpoch；重复键时保留既有数据，不覆盖运行状态。
     *
     * @return 实际影响行数，调用方据此判断条件更新、插入或续租是否生效。
     */
    int ensureSlaRebuildLeaseUpdate(@Param("tenantId") byte[] tenantId, @Param("now") Timestamp now, @Param("now2") Timestamp now2);
    /**
     * 领取 IDLE、无运行标识的 DEGRADED 或已过期的执行租约，递增 fencingEpoch，写入 runId、高水位及有效期并转为 RUNNING。
     *
     * @return 实际影响行数，调用方据此判断条件更新、插入或续租是否生效。
     */
    int claimSlaRebuildLeaseUpdate(@Param("runId") byte[] runId, @Param("highWaterAt") Timestamp highWaterAt, @Param("highWaterClockId") byte[] highWaterClockId, @Param("expiresAt") Timestamp expiresAt, @Param("now") Timestamp now, @Param("tenantId") byte[] tenantId, @Param("now2") Timestamp now2);
    /**
     * 仅在租户、runId、fencingEpoch 匹配，阶段为 RUNNING/CATCHING_UP/SWAPPING 且租约仍有效时续期，并增加版本。
     *
     * @return 实际影响行数，调用方据此判断条件更新、插入或续租是否生效。
     */
    int renewSlaRebuildLeaseUpdate(@Param("expiresAt") Timestamp expiresAt, @Param("now") Timestamp now, @Param("tenantId") byte[] tenantId, @Param("runId") byte[] runId, @Param("fencingEpoch") long fencingEpoch, @Param("now2") Timestamp now2);
    /**
     * 检查租户是否存在有效 runId、未过期且处于 RUNNING/CATCHING_UP/SWAPPING 的租约；存在时返回含 true 的列表，无记录时为空。
     *
     * @return 存在有效重建租约时含 true，无有效租约时为空列表。
     */
    List<Boolean> isSlaRebuildActiveSelect(@Param("tenantId") byte[] tenantId, @Param("now") Timestamp now);
    /**
     * 仅在运行标识、隔离代次匹配且租约未过期、阶段非 IDLE 时更新阶段、有效期和错误码，阻止旧执行者推进状态。
     *
     * @return 实际影响行数，调用方据此判断条件更新、插入或续租是否生效。
     */
    int advanceSlaRebuildLeaseUpdate(@Param("state") String state, @Param("expiresAt") Timestamp expiresAt, @Param("errorCode") String errorCode, @Param("now") Timestamp now, @Param("tenantId") byte[] tenantId, @Param("runId") byte[] runId, @Param("fencingEpoch") long fencingEpoch, @Param("now2") Timestamp now2);
    /**
     * 匹配租户、runId 和 fencingEpoch 后写入结束状态及错误码，清空运行标识与有效期，避免释放其他执行者的租约。
     *
     * @return 实际影响行数，调用方据此判断条件更新、插入或续租是否生效。
     */
    int releaseSlaRebuildLeaseUpdate(@Param("state") String state, @Param("errorCode") String errorCode, @Param("now") Timestamp now, @Param("tenantId") byte[] tenantId, @Param("runId") byte[] runId, @Param("fencingEpoch") long fencingEpoch);
}
