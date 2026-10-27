package com.flowdesk.ticket.sla;

import com.flowdesk.ticket.model.sla.*;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 业务用例契约，调用者依赖接口。 */
public interface SlaClockService {
    /**
     * 保存时钟与策略时间快照，重复写入须与已有身份及内容一致。
     */
    void insertSlaClock(UUID tenantId, UUID slaClockId, UUID ticketId, UUID slaPolicyId,
                               String clockType, Instant startedAt, Instant deadlineAt,
                               Instant reminderDueAt, long durationSeconds, Long reminderBeforeSeconds);

    /**
     * 保存时钟与策略时间快照，重复写入须与已有身份及内容一致。
     */
    void insertSlaClock(UUID tenantId, UUID slaClockId, UUID ticketId, UUID slaPolicyId,
                               String clockType, Instant startedAt, Instant deadlineAt,
                               Instant reminderDueAt, long durationSeconds, Long reminderBeforeSeconds,
                               Instant now);

    /**
     * 保存时钟与策略时间快照，重复写入须与已有身份及内容一致。
     */
    void insertSlaClock(UUID tenantId, UUID slaClockId, UUID ticketId, UUID slaPolicyId,
                               String clockType, Instant startedAt, Instant deadlineAt,
                               Instant reminderDueAt, long durationSeconds, Long reminderBeforeSeconds,
                               int cycleNo, Instant now);

    /**
     * 按当前租户和标识读取 SLA 时钟，无匹配时返回空结果。
     */
    Optional<SlaClockRow> findSlaClockById(UUID tenantId, UUID slaClockId);

    /**
     * 按预期版本结算时钟为按时达成，记录操作人与时间。
     */
    int updateSlaClockSatisfiedOnTime(UUID tenantId, UUID slaClockId, long expectedLockVersion,
                                             Instant now);

    /**
     * 按预期版本结算时钟为按时达成，记录操作人与时间。
     */
    int updateSlaClockSatisfiedOnTime(UUID tenantId, UUID slaClockId, long expectedLockVersion,
                                             String actorType, UUID actorId, Instant now);

    /**
     * 按预期版本标记时钟超时，防止并发扫描重复推进状态。
     */
    int updateSlaClockBreached(UUID tenantId, UUID slaClockId, long expectedLockVersion,
                                      Instant now);

    /**
     * 按预期版本标记时钟超时，防止并发扫描重复推进状态。
     */
    int updateSlaClockBreached(UUID tenantId, UUID slaClockId, long expectedLockVersion,
                                      String actorType, UUID actorId, Instant now);

    /**
     * 按预期版本结算逾期达成，保留此前超时事实。
     */
    int updateSlaClockSatisfiedLate(UUID tenantId, UUID slaClockId, long expectedLockVersion,
                                           Instant now);

    /**
     * 按预期版本结算逾期达成，保留此前超时事实。
     */
    int updateSlaClockSatisfiedLate(UUID tenantId, UUID slaClockId, long expectedLockVersion,
                                           String actorType, UUID actorId, Instant now);

    /**
     * 读取指定时钟与阶段的触发事实，供去重和通知重试。
     */
    Optional<SlaTriggerDetailRow> findSlaTrigger(UUID tenantId, UUID slaClockId, String stage,
                                                        boolean lock);

    /**
     * 以稳定效果键保存阶段触发事实，重复触发须与既有内容一致。
     */
    void insertSlaTrigger(UUID tenantId, UUID triggerId, UUID slaClockId, String stage,
                                 byte[] effectKey, Instant dueAt, Instant triggeredAt,
                                 byte[] publicationKey);

    /**
     * 保存并去重触发收件人快照，重试沿用首次确定的接收集合。
     */
    void insertSlaTriggerRecipientSnapshot(UUID tenantId, UUID triggerId,
                                                  Collection<UUID> recipients,
                                                  String createdByType, UUID createdById,
                                                  Instant now);

    /**
     * 读取首次触发确定的收件人，避免重试受后续配置变化影响。
     */
    List<UUID> findSlaTriggerRecipientSnapshot(UUID tenantId, UUID triggerId);

    /**
     * 推进时钟更新时间以驱动增量索引同步，并遵循当前版本约束。
     */
    int updateSlaClockUpdatedAt(UUID tenantId, UUID slaClockId, Instant now);
}
