package com.flowdesk.ticket.notification.impl;

import com.flowdesk.ticket.notification.*;

import com.flowdesk.ticket.model.notification.*;
import com.flowdesk.ticket.persistence.Uuids;

import com.flowdesk.common.notification.NotificationEventContract;
import com.flowdesk.common.notification.NotificationWireCodec;
import com.flowdesk.common.tenant.TenantContextHolder;
import org.springframework.dao.DuplicateKeyException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** 按租户持久化工单通知 Outbox，管理领取租约、发送状态与人工恢复记录。 */
@Service
public final class TicketNotificationDispatchServiceImpl implements TicketNotificationDispatchService {
    private static final NotificationEventContract.Binding BINDING = NotificationEventContract.TICKET;
    private final TicketNotificationOutboxPersistenceMapper mapper;

    /**
     * 构造工单通知组件并保存调用依赖。
     */
    public TicketNotificationDispatchServiceImpl(TicketNotificationOutboxPersistenceMapper mapper) { this.mapper = mapper; }

    /**
     * 写入不可变通知意图；只有发布身份和载荷完全一致时，重复键才可视为幂等成功。
     * 首次提交拥有追踪元数据；后续重放即使使用新的请求关联标识，也保留原 correlation/causation。
     * 流程反向事件恢复和已有 SLA 触发重放均复用此边界。
     */


    public List<Candidate> findCandidates(Instant now, Instant leaseCutoff, int limit) {
        Objects.requireNonNull(now); Objects.requireNonNull(leaseCutoff);
        if (limit < 1 || limit > NotificationEventContract.OUTBOX_MAX_CLAIMS) throw new IllegalArgumentException("limit must be 1..50");
        return mapper.findCandidatesSelect(BINDING.contractRef(), NotificationEventContract.VERSION, NotificationEventContract.OUTBOX_MAX_ATTEMPTS, ts(now), ts(leaseCutoff), limit);
    }

    /**
     * 条件领取待发记录并建立租约，仅成功领取者可发起传输。
     * 调用必须处于数据库事务中，以保持当前步骤与关联事实原子提交。
     */
    public Optional<Claimed> claimForSend(Candidate candidate, String token, Instant now, Instant leaseCutoff) {
        requireTransaction(); requireTenant(candidate.tenantId()); requireToken(token);
        int updated;
        if ("PENDING".equals(candidate.status())) {
            updated = mapper.claimForSendUpdate(token, ts(now), ts(now), Uuids.toBytes(candidate.tenantId()), Uuids.toBytes(candidate.outboxId()), candidate.attemptCount(), NotificationEventContract.OUTBOX_MAX_ATTEMPTS, candidate.lockVersion(), ts(now));
        } else if ("PUBLISHING".equals(candidate.status()) && candidate.lockedBy() != null) {
            updated = mapper.claimForSendUpdate2(token, ts(now), ts(now), Uuids.toBytes(candidate.tenantId()), Uuids.toBytes(candidate.outboxId()), candidate.attemptCount(), NotificationEventContract.OUTBOX_MAX_ATTEMPTS, candidate.lockedBy(), candidate.lockVersion(), ts(leaseCutoff));
        } else return Optional.empty();
        if (updated != 1) return Optional.empty();
        return findClaimed(candidate.tenantId(), candidate.outboxId(), token, candidate.attemptCount() + 1, candidate.lockVersion() + 1);
    }

    /**
     * 领取需要恢复的记录，防止并发工作器重复建立恢复事实。
     * 调用必须处于数据库事务中，以保持当前步骤与关联事实原子提交。
     */
    public Optional<Claimed> claimForRecovery(Candidate candidate, String token, Instant now, Instant leaseCutoff) {
        requireTransaction(); requireTenant(candidate.tenantId()); requireToken(token);
        if (candidate.attemptCount() != NotificationEventContract.OUTBOX_MAX_ATTEMPTS) return Optional.empty();
        int updated;
        if ("PENDING".equals(candidate.status())) {
            updated = mapper.claimForRecoveryUpdate(token, ts(now), ts(now), Uuids.toBytes(candidate.tenantId()), Uuids.toBytes(candidate.outboxId()), NotificationEventContract.OUTBOX_MAX_ATTEMPTS, candidate.lockVersion(), ts(now));
        } else if ("PUBLISHING".equals(candidate.status()) && candidate.lockedBy() != null) {
            updated = mapper.claimForRecoveryUpdate2(token, ts(now), ts(now), Uuids.toBytes(candidate.tenantId()), Uuids.toBytes(candidate.outboxId()), NotificationEventContract.OUTBOX_MAX_ATTEMPTS, candidate.lockedBy(), candidate.lockVersion(), ts(leaseCutoff));
        } else return Optional.empty();
        if (updated != 1) return Optional.empty();
        return findClaimed(candidate.tenantId(), candidate.outboxId(), token,
                NotificationEventContract.OUTBOX_MAX_ATTEMPTS, candidate.lockVersion() + 1);
    }

    /**
     * 在领取身份仍有效时标记发布成功，阻止过期工作器覆盖新结果。
     * 调用必须处于数据库事务中，以保持当前步骤与关联事实原子提交。
     */
    public boolean markPublished(Claimed row, Instant now, Instant leaseCutoff) {
        requireTransaction(); requireTenant(row.tenantId());
        int updated = mapper.markPublishedUpdate(ts(now), ts(now), Uuids.toBytes(row.tenantId()), Uuids.toBytes(row.outboxId()), row.attemptCount(), row.claimToken(), row.lockVersion(), ts(leaseCutoff));
        if (updated == 1) resolveReplay(row.tenantId(), row.outboxId(), now);
        return updated == 1;
    }

    /**
     * 将未确认成功的消息重新排队，记录退避后的下次尝试时间。
     * 调用必须处于数据库事务中，以保持当前步骤与关联事实原子提交。
     */
    public boolean markPending(Claimed row, Instant now, Instant leaseCutoff, Instant next, String errorCode) {
        requireTransaction(); requireTenant(row.tenantId());
        if (row.attemptCount() < 1 || row.attemptCount() >= NotificationEventContract.OUTBOX_MAX_ATTEMPTS) throw new IllegalArgumentException("Only attempts 1..7 may return to PENDING");
        return mapper.markPendingUpdate(ts(next), errorCode, ts(now), Uuids.toBytes(row.tenantId()), Uuids.toBytes(row.outboxId()), row.attemptCount(), row.claimToken(), row.lockVersion(), ts(leaseCutoff)) == 1;
    }

    /**
     * 将发件箱失败原子转入恢复，保留来源身份和安全摘要。
     * 调用必须处于数据库事务中，以保持当前步骤与关联事实原子提交。
     */
    public boolean moveToRecovery(Claimed row, Instant now, Instant leaseCutoff, String code, String summary, UUID recoveryId) {
        requireTransaction(); requireTenant(row.tenantId());
        if (row.attemptCount() != NotificationEventContract.OUTBOX_MAX_ATTEMPTS) throw new IllegalArgumentException("Recovery requires attempt 8");
        return moveAndRecord(row, now, leaseCutoff, code, summary, recoveryId);
    }

    /** 确定的持久化契约损坏只转入一次 Recovery，避免无限重试坏消息。 */
    public boolean rejectContract(Claimed row, Instant now, Instant leaseCutoff, String code, String summary, UUID recoveryId) {
        requireTransaction(); requireTenant(row.tenantId());
        return moveAndRecord(row, now, leaseCutoff, code, summary, recoveryId);
    }

    /**
     * 在同一事务中迁移恢复状态与写入审计，避免部分提交。
     */
    private boolean moveAndRecord(Claimed row, Instant now, Instant leaseCutoff, String errorCode, String summary, UUID recoveryId) {
        String code = stableCode(errorCode);
        int updated = mapper.moveAndRecordUpdate(ts(now), code, ts(now), Uuids.toBytes(row.tenantId()), Uuids.toBytes(row.outboxId()), row.attemptCount(), row.claimToken(), row.lockVersion(), ts(leaseCutoff));
        if (updated != 1) return false;
        // 恢复身份由来源记录和方向决定，同一条耗尽重试的 Outbox 不能在每次重试时生成新 UUID。
        ensureRecovery(row, now, code, clean(summary), TicketNotificationIdentity.recoveryId(row));
        mapper.moveAndRecordUpdate2(Uuids.toBytes(row.tenantId()), Uuids.toBytes(UUID.randomUUID()), "TICKET_NOTIFICATION_OUTBOX_RECOVERY_REQUIRED", Uuids.toBytes(row.outboxId()), clean(summary), code, Uuids.toBytes(row.correlationId()), Uuids.toBytes(row.causationId()), ts(now));
        return true;
    }

    /**
     * 查询或幂等建立恢复记录，已有记录必须与来源身份一致。
     */
    private void ensureRecovery(Claimed row, Instant now, String code, String summary, UUID recoveryId) {
        Optional<Recovery> existing = findRecovery(row);
        if (existing.isPresent()) {
            reopenRecovery(row, existing.get(), recoveryId, now, code, summary);
            return;
        }
        try {
            mapper.ensureRecoveryUpdate(Uuids.toBytes(row.tenantId()), Uuids.toBytes(recoveryId), Uuids.toBytes(row.outboxId()), row.businessIntentKey(), row.attemptCount(), code, summary, ts(now), ts(now), ts(now));
        } catch (DuplicateKeyException duplicate) {
            Recovery raced = findRecovery(row).orElseThrow(() -> duplicate);
            reopenRecovery(row, raced, recoveryId, now, code, summary);
        }
    }

    /**
     * 读取来源对应恢复记录，供去重、状态推进和审计检查。
     */
    private Optional<Recovery> findRecovery(Claimed row) {
        return mapper.findRecoverySelect(Uuids.toBytes(row.tenantId()), Uuids.toBytes(row.outboxId())).stream().findFirst();
    }

    /**
     * 仅在本次重放租约仍匹配时，将未完成恢复项退回待处理状态。
     */
    private void reopenRecovery(Claimed row, Recovery existing, UUID recoveryId,
                                Instant now, String code, String summary) {
        if (!recoveryId.equals(existing.recoveryId())
                || !Arrays.equals(existing.intent(), row.businessIntentKey())
                || !("OPEN".equals(existing.state()) || "REPLAYING".equals(existing.state()))) {
            throw new IllegalStateException("Conflicting Ticket Notification recovery");
        }
        if ("OPEN".equals(existing.state())) {
            if (existing.attemptCount() != row.attemptCount()) {
                throw new IllegalStateException("Conflicting Ticket Notification recovery attempts");
            }
            return;
        }
        // 人工重放获得新的有界发送预算；再次失败时重新打开同一来源的恢复记录，并保留人工重放历史。
        int reopened = mapper.reopenRecoveryUpdate(row.attemptCount(), code, summary, ts(now), Uuids.toBytes(row.tenantId()), Uuids.toBytes(recoveryId), existing.lockVersion());
        if (reopened != 1) throw new IllegalStateException("Ticket Notification recovery reopen CAS lost");
    }

    /**
     * 在本次消息发布完成后收敛关联恢复项，保持原来源和租约条件。
     */
    private void resolveReplay(UUID tenantId, UUID outboxId, Instant now) {
        mapper.resolveReplayUpdate(ts(now), ts(now), Uuids.toBytes(tenantId), Uuids.toBytes(outboxId));
    }

    /**
     * 读取本次领取身份对应快照，避免使用过期任务的旧状态。
     */
    private Optional<Claimed> findClaimed(UUID tenantId, UUID outboxId, String token, int attempts, long version) {
        return mapper.findClaimedSelect(Uuids.toBytes(tenantId), Uuids.toBytes(outboxId), attempts, token, version).stream().findFirst();
    }

    /** 单条工单通知 Outbox 来源对应的稳定 Recovery 主键。 */


    private static String stableCode(String value) { return value != null && value.matches("[A-Z][A-Z0-9_]{0,95}") ? value : "BROKER_RESULT_UNKNOWN"; }
    /**
     * 清理控制字符与多余空白，并限制错误摘要长度。
     */
    private static String clean(String value) { if (value == null) return null; String v = value.replaceAll("[\\p{Cntrl}\\s]+", " ").trim(); return v.isEmpty() ? null : v.substring(0, Math.min(1000, v.length())); }
    /**
     * 要求调用处于数据库事务中，保证关联事实可以原子提交或回滚。
     */
    private static void requireTransaction() { if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Ticket Notification Outbox mutation requires a transaction"); }
    /**
     * 校验传入租户与当前上下文一致，阻止跨租户持久化操作。
     */
    private static void requireTenant(UUID id) { TenantContextHolder.current().requireTenant(id); }
    /**
     * 校验领取令牌，避免缺少所有权身份时结算他人的任务。
     */
    private static void requireToken(String token) { if (token == null || token.isBlank() || token.length() > 96) throw new IllegalArgumentException("claim token must be 1..96 characters"); }
    /**
     * 解析时间文本或转换数据库时间值，遵循空值约定并保留业务精度。
     */
    private static Timestamp ts(Instant value) { return Timestamp.from(Objects.requireNonNull(value)); }







}
