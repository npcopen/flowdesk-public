package com.flowdesk.ticket.sla.impl;

import com.flowdesk.ticket.sla.*;

import com.flowdesk.ticket.model.sla.*;
import com.flowdesk.ticket.persistence.Uuids;
import org.springframework.dao.DuplicateKeyException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import static com.flowdesk.ticket.persistence.SlaPersistenceSupport.requireTenant;
import static com.flowdesk.ticket.persistence.SlaPersistenceSupport.requireTransaction;
import static com.flowdesk.ticket.persistence.SlaPersistenceSupport.timestamp;

import org.springframework.stereotype.Service;

/** SLA 时钟状态转换、不可变触发事实及收件人快照的业务协调。 */
@Service
public class SlaClockServiceImpl implements SlaClockService {
    private final SlaPersistenceMapper mapper;
    /**
     * 构造SLA组件并保存调用依赖。
     */
    public SlaClockServiceImpl(SlaPersistenceMapper mapper) { this.mapper = mapper; }

    /**
     * 保存时钟与策略时间快照，重复写入须与已有身份及内容一致。
     */
    public void insertSlaClock(UUID tenantId, UUID slaClockId, UUID ticketId, UUID slaPolicyId,
                               String clockType, Instant startedAt, Instant deadlineAt,
                               Instant reminderDueAt, long durationSeconds, Long reminderBeforeSeconds) {
        insertSlaClock(tenantId, slaClockId, ticketId, slaPolicyId, clockType, startedAt,
                deadlineAt, reminderDueAt, durationSeconds, reminderBeforeSeconds, startedAt);
    }

    /**
     * 保存时钟与策略时间快照，重复写入须与已有身份及内容一致。
     */
    public void insertSlaClock(UUID tenantId, UUID slaClockId, UUID ticketId, UUID slaPolicyId,
                               String clockType, Instant startedAt, Instant deadlineAt,
                               Instant reminderDueAt, long durationSeconds, Long reminderBeforeSeconds,
                               Instant now) {
        insertSlaClock(tenantId, slaClockId, ticketId, slaPolicyId, clockType, startedAt,
                deadlineAt, reminderDueAt, durationSeconds, reminderBeforeSeconds, 0, now);
    }

    /**
     * 保存时钟与策略时间快照，重复写入须与已有身份及内容一致。
     * 调用必须处于数据库事务中，以保持当前步骤与关联事实原子提交。
     * 传入租户必须与当前上下文一致。
     */
    public void insertSlaClock(UUID tenantId, UUID slaClockId, UUID ticketId, UUID slaPolicyId,
                               String clockType, Instant startedAt, Instant deadlineAt,
                               Instant reminderDueAt, long durationSeconds, Long reminderBeforeSeconds,
                               int cycleNo, Instant now) {
        requireTenant(tenantId);
        requireTransaction();
        if (now == null || startedAt == null || deadlineAt == null
                || !deadlineAt.isAfter(startedAt)) {
            throw new IllegalArgumentException("Invalid SLA clock timestamps");
        }
        try {
            mapper.insertSlaClockUpdate(Uuids.toBytes(tenantId), Uuids.toBytes(slaClockId), Uuids.toBytes(ticketId), Uuids.toBytes(slaPolicyId), clockType, Timestamp.from(startedAt), Timestamp.from(deadlineAt), timestamp(reminderDueAt), durationSeconds, reminderBeforeSeconds, Timestamp.from(now), Timestamp.from(now), cycleNo);
        }
        catch (DuplicateKeyException duplicate) {
            SlaClockRow existing = findSlaClockById(tenantId, slaClockId)
                    .orElseThrow(() -> duplicate);
            if (!slaClockId.equals(existing.slaClockId())
                    || !slaPolicyId.equals(existing.slaPolicyId())
                    || cycleNo != existing.cycleNo()
                    || !startedAt.equals(existing.startedAt())
                    || !deadlineAt.equals(existing.deadlineAt())
                    || !java.util.Objects.equals(reminderDueAt, existing.reminderDueAt())
                    || durationSeconds != existing.policyDurationSeconds()
                    || !java.util.Objects.equals(reminderBeforeSeconds,
                    existing.policyReminderBeforeSeconds())) {
                throw new IllegalStateException("SLA clock identity/content conflict", duplicate);
            }
        }
    }

    /**
     * 按当前租户和标识读取 SLA 时钟，无匹配时返回空结果。
     */
    public Optional<SlaClockRow> findSlaClockById(UUID tenantId, UUID slaClockId) {
        requireTenant(tenantId);
        return mapper.findSlaClockByIdSelect(Uuids.toBytes(tenantId), Uuids.toBytes(slaClockId))
                .stream().findFirst();
    }

    /**
     * 按预期版本结算时钟为按时达成，记录操作人与时间。
     */
    public int updateSlaClockSatisfiedOnTime(UUID tenantId, UUID slaClockId, long expectedLockVersion,
                                             Instant now) {
        return updateSlaClockSatisfiedOnTime(tenantId, slaClockId, expectedLockVersion,
                "SCHEDULER", null, now);
    }

    /**
     * 按预期版本结算时钟为按时达成，记录操作人与时间。
     * 调用必须处于数据库事务中，以保持当前步骤与关联事实原子提交。
     * 传入租户必须与当前上下文一致。
     */
    public int updateSlaClockSatisfiedOnTime(UUID tenantId, UUID slaClockId, long expectedLockVersion,
                                             String actorType, UUID actorId, Instant now) {
        requireTenant(tenantId);
        requireTransaction();
        validateSlaActor(actorType, actorId);
        return mapper.updateSlaClockSatisfiedOnTimeUpdate(Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), actorType, Uuids.toBytes(actorId), Uuids.toBytes(tenantId), Uuids.toBytes(slaClockId), expectedLockVersion);
    }

    /**
     * 按预期版本标记时钟超时，防止并发扫描重复推进状态。
     */
    public int updateSlaClockBreached(UUID tenantId, UUID slaClockId, long expectedLockVersion,
                                      Instant now) {
        return updateSlaClockBreached(tenantId, slaClockId, expectedLockVersion,
                "SCHEDULER", null, now);
    }

    /**
     * 按预期版本标记时钟超时，防止并发扫描重复推进状态。
     * 调用必须处于数据库事务中，以保持当前步骤与关联事实原子提交。
     * 传入租户必须与当前上下文一致。
     */
    public int updateSlaClockBreached(UUID tenantId, UUID slaClockId, long expectedLockVersion,
                                      String actorType, UUID actorId, Instant now) {
        requireTenant(tenantId);
        requireTransaction();
        validateSlaActor(actorType, actorId);
        return mapper.updateSlaClockBreachedUpdate(Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), actorType, Uuids.toBytes(actorId), Uuids.toBytes(tenantId), Uuids.toBytes(slaClockId), expectedLockVersion);
    }

    /**
     * 按预期版本结算逾期达成，保留此前超时事实。
     */
    public int updateSlaClockSatisfiedLate(UUID tenantId, UUID slaClockId, long expectedLockVersion,
                                           Instant now) {
        return updateSlaClockSatisfiedLate(tenantId, slaClockId, expectedLockVersion,
                "SCHEDULER", null, now);
    }

    /**
     * 按预期版本结算逾期达成，保留此前超时事实。
     * 调用必须处于数据库事务中，以保持当前步骤与关联事实原子提交。
     * 传入租户必须与当前上下文一致。
     */
    public int updateSlaClockSatisfiedLate(UUID tenantId, UUID slaClockId, long expectedLockVersion,
                                           String actorType, UUID actorId, Instant now) {
        requireTenant(tenantId);
        requireTransaction();
        validateSlaActor(actorType, actorId);
        return mapper.updateSlaClockSatisfiedLateUpdate(Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), actorType, Uuids.toBytes(actorId), Uuids.toBytes(tenantId), Uuids.toBytes(slaClockId), expectedLockVersion);
    }

    /**
     * 读取指定时钟与阶段的触发事实，供去重和通知重试。
     * 调用必须处于数据库事务中，以保持当前步骤与关联事实原子提交。
     * 传入租户必须与当前上下文一致。
     */
    public Optional<SlaTriggerDetailRow> findSlaTrigger(UUID tenantId, UUID slaClockId, String stage,
                                                        boolean lock) {
        requireTenant(tenantId);
        if (lock) requireTransaction();
        return mapper.findSlaTriggerSelect(Uuids.toBytes(tenantId), Uuids.toBytes(slaClockId), stage, lock).stream().findFirst();
    }

    /**
     * 以稳定效果键保存阶段触发事实，重复触发须与既有内容一致。
     * 调用必须处于数据库事务中，以保持当前步骤与关联事实原子提交。
     */
    public void insertSlaTrigger(UUID tenantId, UUID triggerId, UUID slaClockId, String stage,
                                 byte[] effectKey, Instant dueAt, Instant triggeredAt,
                                 byte[] publicationKey) {
        requireTransaction();
        try {
            mapper.insertSlaTriggerUpdate(Uuids.toBytes(tenantId), Uuids.toBytes(triggerId), Uuids.toBytes(slaClockId), stage, effectKey, Timestamp.from(dueAt), Timestamp.from(triggeredAt), publicationKey, Timestamp.from(triggeredAt));
        }
        catch (DuplicateKeyException duplicate) {
            SlaTriggerDetailRow existing = findSlaTrigger(tenantId, slaClockId, stage, false)
                    .orElseThrow(() -> duplicate);
            if (!triggerId.equals(uuidFromKey(existing.effectKey()))
                    || !java.util.Arrays.equals(effectKey, existing.effectKey())
                    || !dueAt.equals(existing.dueAt())
                    || !java.util.Arrays.equals(publicationKey, existing.publicationKey())) {
                throw new IllegalStateException("SLA trigger identity/content conflict", duplicate);
            }
        }
    }

    /**
     * 保存并去重触发收件人快照，重试沿用首次确定的接收集合。
     * 调用必须处于数据库事务中，以保持当前步骤与关联事实原子提交。
     * 传入租户必须与当前上下文一致。
     * 唯一约束竞争时回读既有事实并核验内容，避免重复产生业务效果。
     */
    public void insertSlaTriggerRecipientSnapshot(UUID tenantId, UUID triggerId,
                                                  Collection<UUID> recipients,
                                                  String createdByType, UUID createdById,
                                                  Instant now) {
        requireTenant(tenantId);
        requireTransaction();
        if (triggerId == null || recipients == null || recipients.isEmpty()
                || createdByType == null || now == null) {
            throw new IllegalArgumentException("SLA trigger recipient snapshot is incomplete");
        }
        java.util.LinkedHashSet<UUID> expected = new java.util.LinkedHashSet<>();
        for (UUID recipient : recipients) {
            if (recipient == null || (recipient.getMostSignificantBits() == 0L
                    && recipient.getLeastSignificantBits() == 0L)) {
                throw new IllegalArgumentException("SLA trigger recipient must be concrete");
            }
            expected.add(recipient);
        }
        List<UUID> existing = findSlaTriggerRecipientSnapshot(tenantId, triggerId);
        if (!existing.isEmpty()) {
            if (!new java.util.LinkedHashSet<>(existing).equals(expected)) {
                throw new IllegalStateException("SLA trigger recipient snapshot content conflict");
            }
            return;
        }
        for (UUID recipient : expected) {
            try {
                mapper.insertSlaTriggerRecipientSnapshotUpdate(Uuids.toBytes(tenantId), Uuids.toBytes(triggerId), Uuids.toBytes(recipient), Timestamp.from(now), createdByType, Uuids.toBytes(createdById));
            }
            catch (DuplicateKeyException duplicate) {
                // 并发扫描器可能已插入同一不可变成员；下方重新读取完整集合并验证。
            }
        }
        List<UUID> actual = findSlaTriggerRecipientSnapshot(tenantId, triggerId);
        if (!new java.util.LinkedHashSet<>(actual).equals(expected)) {
            throw new IllegalStateException("SLA trigger recipient snapshot write was incomplete");
        }
    }

    /**
     * 读取首次触发确定的收件人，避免重试受后续配置变化影响。
     * 传入租户必须与当前上下文一致。
     */
    public List<UUID> findSlaTriggerRecipientSnapshot(UUID tenantId, UUID triggerId) {
        requireTenant(tenantId);
        // 收件人表是不可变子集合，运行账号仅有 SELECT/INSERT 权限。
        // 调用方已通过父工单与时钟锁串行化写入，子表再加 FOR UPDATE 既多余，也违反真实 MySQL 的最小权限约束。
        return mapper.findSlaTriggerRecipientSnapshotSelect(Uuids.toBytes(tenantId), Uuids.toBytes(triggerId));
    }

    /**
     * 推进时钟更新时间以驱动增量索引同步，并遵循当前版本约束。
     * 调用必须处于数据库事务中，以保持当前步骤与关联事实原子提交。
     * 传入租户必须与当前上下文一致。
     */
    public int updateSlaClockUpdatedAt(UUID tenantId, UUID slaClockId, Instant now) {
        requireTenant(tenantId);
        requireTransaction();
        if (now == null) throw new IllegalArgumentException("SLA clock update time is required");
        requireTransaction();
        return mapper.updateSlaClockUpdatedAtUpdate(Timestamp.from(now), Timestamp.from(now), Uuids.toBytes(tenantId), Uuids.toBytes(slaClockId));
    }

    /**
     * 检查操作主体类型与标识组合，保证 SLA 审计归属有效。
     */
    private static void validateSlaActor(String actorType, UUID actorId) {
        if (!("USER".equals(actorType) || "SCHEDULER".equals(actorType)
                || "SERVICE".equals(actorType))) {
            throw new IllegalArgumentException("Unsupported SLA clock actor type");
        }
        if ("USER".equals(actorType) && actorId == null) {
            throw new IllegalArgumentException("User SLA clock updates require actorId");
        }
        if (!"USER".equals(actorType) && actorId != null) {
            throw new IllegalArgumentException("Non-user SLA clock updates cannot carry actorId");
        }
    }

    /**
     * 将稳定幂等键转为 UUID，保持业务事实与关联记录身份一致。
     */
    private static UUID uuidFromKey(byte[] key) {
        byte[] first = java.util.Arrays.copyOf(key, 16);
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(first);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}
