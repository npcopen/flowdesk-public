package com.flowdesk.ticket.sla.impl;

import com.flowdesk.ticket.sla.*;

import com.flowdesk.ticket.model.sla.*;
import com.flowdesk.ticket.persistence.Uuids;

import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static com.flowdesk.ticket.persistence.SlaPersistenceSupport.requireTenant;
import static com.flowdesk.ticket.persistence.SlaPersistenceSupport.requireTransaction;
import static com.flowdesk.ticket.persistence.SlaPersistenceSupport.timestamp;

/** 持久化 Redis SLA 索引重建的所有权、进度与栅栏版本，防止旧任务覆盖新任务。 */
@Service
public final class SlaRebuildLeaseServiceImpl implements SlaRebuildLeaseService {
    private final SlaRebuildLeasePersistenceMapper mapper;

    /**
     * 构造SLA组件并保存调用依赖。
     */
    public SlaRebuildLeaseServiceImpl(SlaRebuildLeasePersistenceMapper mapper) {
        this.mapper = mapper;
    }

    // Redis 重建租约

    /**
     * 事务内锁定索引重建租约，供领取或阶段迁移。
     * 调用必须处于数据库事务中，以保持当前步骤与关联事实原子提交。
     * 传入租户必须与当前上下文一致。
     */
    public Optional<SlaRebuildLeaseRow> findSlaRebuildLeaseForUpdate(UUID tenantId) {
        requireTenant(tenantId);
        requireTransaction();
        return mapper.findSlaRebuildLeaseForUpdateSelect(Uuids.toBytes(tenantId)).stream().findFirst();
    }

    /**
     * 读取租户重建租约，供所有者和状态核验。
     */
    public Optional<SlaRebuildLeaseRow> findSlaRebuildLease(UUID tenantId) {
        requireTenant(tenantId);
        return mapper.findSlaRebuildLeaseSelect(Uuids.toBytes(tenantId)).stream().findFirst();
    }

    /**
     * 幂等建立重建租约锚点，供后续条件领取。
     * 调用必须处于数据库事务中，以保持当前步骤与关联事实原子提交。
     * 传入租户必须与当前上下文一致。
     */
    public void ensureSlaRebuildLease(UUID tenantId, Instant now) {
        requireTenant(tenantId);
        requireTransaction();
        mapper.ensureSlaRebuildLeaseUpdate(Uuids.toBytes(tenantId), Timestamp.from(now), Timestamp.from(now));
    }

    /**
     * 在允许接管时条件领取重建任务，返回领取是否成功。
     * 调用必须处于数据库事务中，以保持当前步骤与关联事实原子提交。
     * 传入租户必须与当前上下文一致。
     */
    public int claimSlaRebuildLease(UUID tenantId, UUID runId, Instant highWaterAt,
                                    UUID highWaterClockId, Instant expiresAt, Instant now) {
        requireTenant(tenantId);
        requireTransaction();
        if (tenantId == null || runId == null || expiresAt == null || now == null
                || !expiresAt.isAfter(now)) {
            throw new IllegalArgumentException("Invalid SLA rebuild lease claim");
        }
        return mapper.claimSlaRebuildLeaseUpdate(Uuids.toBytes(runId), timestamp(highWaterAt), Uuids.toBytes(highWaterClockId), Timestamp.from(expiresAt), Timestamp.from(now), Uuids.toBytes(tenantId), Timestamp.from(now));
    }

    /** 仅允许仍有效且 run 与栅栏 epoch 均匹配的持有者续租。 */
    public int renewSlaRebuildLease(UUID tenantId, UUID runId, long fencingEpoch,
                                    Instant expiresAt, Instant now) {
        requireTenant(tenantId);
        requireTransaction();
        if (runId == null || expiresAt == null || now == null || !expiresAt.isAfter(now)) {
            throw new IllegalArgumentException("Invalid SLA rebuild lease renewal");
        }
        return mapper.renewSlaRebuildLeaseUpdate(Timestamp.from(expiresAt), Timestamp.from(now), Uuids.toBytes(tenantId), Uuids.toBytes(runId), fencingEpoch, Timestamp.from(now));
    }

    /**
     * 判断租户是否存在尚未过期的有效重建租约。
     */
    public boolean isSlaRebuildActive(UUID tenantId, Instant now) {
        requireTenant(tenantId);
        return mapper.isSlaRebuildActiveSelect(Uuids.toBytes(tenantId), Timestamp.from(now))
                .stream().findFirst().orElse(Boolean.FALSE);
    }

    /** 带栅栏的状态推进；过期或被替换的持有者不能更新重建状态。 */
    public int advanceSlaRebuildLease(UUID tenantId, UUID runId, long fencingEpoch,
                                      String state, Instant expiresAt, String errorCode,
                                      Instant now) {
        requireTenant(tenantId);
        requireTransaction();
        if (!java.util.Set.of("RUNNING", "CATCHING_UP", "SWAPPING", "DEGRADED")
                .contains(state) || expiresAt == null || now == null || !expiresAt.isAfter(now)) {
            throw new IllegalArgumentException("Invalid SLA rebuild lease state transition");
        }
        return mapper.advanceSlaRebuildLeaseUpdate(state, Timestamp.from(expiresAt), errorCode, Timestamp.from(now), Uuids.toBytes(tenantId), Uuids.toBytes(runId), fencingEpoch, Timestamp.from(now));
    }

    /**
     * 按领取身份推进阶段或游标，阻止旧执行者覆盖新进度。
     */
    public int advanceSlaRebuildLease(UUID tenantId, UUID runId, String state,
                                      Instant expiresAt, String errorCode, Instant now) {
        SlaRebuildLeaseRow owner = findSlaRebuildLease(tenantId).orElse(null);
        if (owner == null || !runId.equals(owner.runId())) return 0;
        return advanceSlaRebuildLease(tenantId, runId, owner.fencingEpoch(), state,
                expiresAt, errorCode, now);
    }

    /**
     * 仅在所有权仍匹配时释放租约，避免释放已被接管的任务。
     * 调用必须处于数据库事务中，以保持当前步骤与关联事实原子提交。
     * 传入租户必须与当前上下文一致。
     */
    public int releaseSlaRebuildLease(UUID tenantId, UUID runId, long fencingEpoch,
                                      String state, String errorCode, Instant now) {
        requireTenant(tenantId);
        requireTransaction();
        if (!java.util.Set.of("IDLE", "DEGRADED").contains(state) || now == null) {
            throw new IllegalArgumentException("Invalid SLA rebuild lease release state");
        }
        return mapper.releaseSlaRebuildLeaseUpdate(state, errorCode, Timestamp.from(now), Uuids.toBytes(tenantId), Uuids.toBytes(runId), fencingEpoch);
    }

    /**
     * 仅在所有权仍匹配时释放租约，避免释放已被接管的任务。
     */
    public int releaseSlaRebuildLease(UUID tenantId, UUID runId, String state,
                                      String errorCode, Instant now) {
        SlaRebuildLeaseRow owner = findSlaRebuildLease(tenantId).orElse(null);
        if (owner == null || !runId.equals(owner.runId())) return 0;
        return releaseSlaRebuildLease(tenantId, runId, owner.fencingEpoch(), state,
                errorCode, now);
    }


}
