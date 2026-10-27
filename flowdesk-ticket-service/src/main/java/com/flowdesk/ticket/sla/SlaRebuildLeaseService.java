package com.flowdesk.ticket.sla;

import com.flowdesk.ticket.model.sla.*;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** 业务能力契约；实现直接使用 MyBatis Mapper，保留调用方事务边界。 */
public interface SlaRebuildLeaseService {
    /**
     * 事务内锁定索引重建租约，供领取或阶段迁移。
     */
    Optional<SlaRebuildLeaseRow> findSlaRebuildLeaseForUpdate(UUID tenantId);

    /**
     * 读取租户重建租约，供所有者和状态核验。
     */
    Optional<SlaRebuildLeaseRow> findSlaRebuildLease(UUID tenantId);

    /**
     * 幂等建立重建租约锚点，供后续条件领取。
     */
    void ensureSlaRebuildLease(UUID tenantId, Instant now);

    /**
     * 在允许接管时条件领取重建任务，返回领取是否成功。
     */
    int claimSlaRebuildLease(UUID tenantId, UUID runId, Instant highWaterAt,
                                    UUID highWaterClockId, Instant expiresAt, Instant now);

    /**
     * 仅在令牌与版本匹配时续租，防止过期重建者修改新租约。
     */
    int renewSlaRebuildLease(UUID tenantId, UUID runId, long fencingEpoch,
                                    Instant expiresAt, Instant now);

    /**
     * 判断租户是否存在尚未过期的有效重建租约。
     */
    boolean isSlaRebuildActive(UUID tenantId, Instant now);

    /**
     * 按领取身份推进阶段或游标，阻止旧执行者覆盖新进度。
     */
    int advanceSlaRebuildLease(UUID tenantId, UUID runId, long fencingEpoch,
                                      String state, Instant expiresAt, String errorCode,
                                      Instant now);

    /**
     * 按领取身份推进阶段或游标，阻止旧执行者覆盖新进度。
     */
    int advanceSlaRebuildLease(UUID tenantId, UUID runId, String state,
                                      Instant expiresAt, String errorCode, Instant now);

    /**
     * 仅在所有权仍匹配时释放租约，避免释放已被接管的任务。
     */
    int releaseSlaRebuildLease(UUID tenantId, UUID runId, long fencingEpoch,
                                      String state, String errorCode, Instant now);

    /**
     * 仅在所有权仍匹配时释放租约，避免释放已被接管的任务。
     */
    int releaseSlaRebuildLease(UUID tenantId, UUID runId, String state,
                                      String errorCode, Instant now);
}
