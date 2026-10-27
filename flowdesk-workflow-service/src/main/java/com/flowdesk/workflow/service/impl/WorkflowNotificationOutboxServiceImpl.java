package com.flowdesk.workflow.service.impl;

import com.flowdesk.workflow.notification.WorkflowNotificationOutboxMapper;
import com.flowdesk.workflow.service.WorkflowNotificationOutboxService;
import com.flowdesk.workflow.model.WorkflowNotificationOutboxModels.*;
import com.flowdesk.workflow.model.WorkflowNotificationOutboxModels;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.flowdesk.common.notification.NotificationEventContract;
import com.flowdesk.common.tenant.TenantContextHolder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 按租户维护审批通知 Outbox、发送重试及恢复记录的持久化边界。
 */
public final class WorkflowNotificationOutboxServiceImpl implements WorkflowNotificationOutboxService {
    private static final NotificationEventContract.Binding BINDING =
            NotificationEventContract.WORKFLOW;
    private static final int MAX_ATTEMPTS = NotificationEventContract.OUTBOX_MAX_ATTEMPTS;
    private static final int MAX_CLAIMS = NotificationEventContract.OUTBOX_MAX_CLAIMS;
    private final WorkflowNotificationOutboxMapper mapper;
    private final TransactionTemplate transactions;

    /**
     * 注入通知 Outbox 映射及事务管理器，领取、重试和恢复结算均通过短本地事务更新。
     */
    public WorkflowNotificationOutboxServiceImpl(WorkflowNotificationOutboxMapper mapper, PlatformTransactionManager manager) {
        this.mapper = mapper;
        this.transactions = new TransactionTemplate(manager);
    }

    /**
     * 按时间、过期租约与数量上限扫描本轮可处理的 Outbox 候选消息。
     */
    public List<Candidate> findCandidates(Instant now, Instant leaseCutoff, int limit) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(leaseCutoff, "leaseCutoff");
        if (limit < 1 || limit > MAX_CLAIMS) {
            throw new IllegalArgumentException("limit must be 1..50");
        }
        return mapper.findCandidates(BINDING.contractRef(), NotificationEventContract.VERSION, "NOTIFICATION_REQUEST",
                MAX_ATTEMPTS, ts(now), ts(leaseCutoff), limit);
    }

    /**
     * 按状态、版本和租约条件领取发送权，竞争失败返回空值以防重复结算。
     */
    public Optional<Claimed> claimForSend(Candidate candidate, String token,
                                          Instant now, Instant leaseCutoff) {
        return transactions.execute(status -> {
            requireTransaction();
            requireTenant(candidate.tenantId());
            requireToken(token);
            int updated;
            if ("PENDING".equals(candidate.status())) {
                updated = mapper.claimPendingForSend(token, ts(now), bytes(candidate.tenantId()), bytes(candidate.outboxId()),
                        candidate.attemptCount(), MAX_ATTEMPTS, candidate.lockVersion());
            }
            else if ("PUBLISHING".equals(candidate.status()) && candidate.lockedBy() != null) {
                updated = mapper.takeOverForSend(token, ts(now), bytes(candidate.tenantId()), bytes(candidate.outboxId()),
                        candidate.attemptCount(), MAX_ATTEMPTS, candidate.lockedBy(), candidate.lockVersion(),
                        ts(leaseCutoff));
            }
            else {
                return Optional.empty();
            }
            if (updated != 1) return Optional.empty();
            return findClaimed(candidate.tenantId(), candidate.outboxId(), token,
                    candidate.attemptCount() + 1, candidate.lockVersion() + 1);

        });
    }

    /**
     * 接管已耗尽发送次数的记录，不再消耗额外的 Broker 发送尝试。
     */
    public Optional<Claimed> claimForRecovery(Candidate candidate, String token,
                                               Instant now, Instant leaseCutoff) {
        return transactions.execute(status -> {
            requireTransaction();
            requireTenant(candidate.tenantId());
            requireToken(token);
            if (candidate.attemptCount() != MAX_ATTEMPTS) return Optional.empty();
            int updated;
            if ("PENDING".equals(candidate.status())) {
                updated = mapper.claimPendingForRecovery(token, ts(now), bytes(candidate.tenantId()), bytes(candidate.outboxId()),
                        MAX_ATTEMPTS, candidate.lockVersion());
            }
            else if ("PUBLISHING".equals(candidate.status()) && candidate.lockedBy() != null) {
                updated = mapper.takeOverForRecovery(token, ts(now), bytes(candidate.tenantId()), bytes(candidate.outboxId()),
                        MAX_ATTEMPTS, candidate.lockedBy(), candidate.lockVersion(), ts(leaseCutoff));
            }
            else return Optional.empty();
            if (updated != 1) return Optional.empty();
            return findClaimed(candidate.tenantId(), candidate.outboxId(), token,
                    MAX_ATTEMPTS, candidate.lockVersion() + 1);

        });
    }

    /**
     * 仅按当前领取身份标记发布完成，避免过期处理者覆盖其他实例的发送结果。
     */
    public boolean markPublished(Claimed row, Instant now, Instant leaseCutoff) {
        return transactions.execute(status -> {
            requireTransaction();
            requireTenant(row.tenantId());
            int updated = mapper.markPublished(ts(now), bytes(row.tenantId()), bytes(row.outboxId()), row.attemptCount(),
                    row.claimToken(), row.lockVersion(), ts(leaseCutoff));
            if (updated == 1) resolveReplay(row.tenantId(), row.outboxId(), now);
            return updated == 1;

        });
    }

    /**
     * 将当前持有消息退回待发送状态，保存下次重试时间与错误码。
     */
    public boolean markPending(Claimed row, Instant now, Instant leaseCutoff,
                               Instant nextAttemptAt, String errorCode) {
        return transactions.execute(status -> {
            requireTransaction();
            requireTenant(row.tenantId());
            if (row.attemptCount() < 1 || row.attemptCount() >= MAX_ATTEMPTS) {
                throw new IllegalArgumentException("Only attempts 1..7 may return to PENDING");
            }
            return mapper.markPending(ts(nextAttemptAt), errorCode, ts(now), bytes(row.tenantId()), bytes(row.outboxId()),
                    row.attemptCount(), row.claimToken(), row.lockVersion(), ts(leaseCutoff)) == 1;

        });
    }

    /**
     * 将耗尽重试预算的记录转入恢复状态，并在同一事务内创建恢复事实。
     */
    public boolean moveToRecovery(Claimed row, Instant now, Instant leaseCutoff,
                                  String errorCode, String summary, UUID recoveryId) {
        return transactions.execute(status -> {
            requireTransaction();
            requireTenant(row.tenantId());
            if (row.attemptCount() != MAX_ATTEMPTS) {
                throw new IllegalArgumentException("Recovery requires attempt 8");
            }
            return moveAndRecord(row, now, leaseCutoff, errorCode, summary, recoveryId);

        });
    }

    /**
     * 确定性的契约损坏直接终止发布并进入恢复，避免对无法成功的消息无限重试。
     */
    public boolean rejectContract(Claimed row, Instant now, Instant leaseCutoff,
                                  String errorCode, String summary, UUID recoveryId) {
        return transactions.execute(status -> {
            requireTransaction();
            requireTenant(row.tenantId());
            return moveAndRecord(row, now, leaseCutoff, errorCode, summary, recoveryId);

        });
    }

    /**
     * 在同一事务迁移 Outbox 并登记恢复事实，防止状态与恢复记录脱节。
     */
    private boolean moveAndRecord(Claimed row, Instant now, Instant leaseCutoff,
                                  String errorCode, String summary, UUID recoveryId) {
        String code = validCode(errorCode);
        int updated = mapper.moveToRecovery(ts(now), code, bytes(row.tenantId()), bytes(row.outboxId()), row.attemptCount(),
                row.claimToken(), row.lockVersion(), ts(leaseCutoff));
        if (updated != 1) return false;
        // 恢复身份由来源 Outbox 确定，避免同一耗尽事件因随机主键
        // 被拆成多条恢复事实。
        ensureRecovery(row, now, code, sanitize(summary), WorkflowNotificationOutboxModels.recoveryId(row));
        mapper.insertRecoveryAudit(bytes(row.tenantId()), bytes(UUID.randomUUID()),
                "WORKFLOW_NOTIFICATION_OUTBOX_RECOVERY_REQUIRED", bytes(row.outboxId()), sanitize(summary),
                code, bytes(row.correlationId()), bytes(row.causationId()), ts(now));
        return true;
    }

    /**
     * 创建或核对恢复记录，唯一键竞争只接受相同意图的既有事实。
     */
    private void ensureRecovery(Claimed row, Instant now, String code, String summary, UUID recoveryId) {
        Optional<Recovery> existing = findRecovery(row);
        if (existing.isPresent()) {
            reopenRecovery(row, existing.get(), recoveryId, now, code, summary);
            return;
        }
        try {
            mapper.insertRecovery(bytes(row.tenantId()), bytes(recoveryId), bytes(row.outboxId()),
                    row.businessIntentKey(), row.attemptCount(), code, summary, ts(now));
        }
        catch (DuplicateKeyException duplicate) {
            Recovery raced = findRecovery(row).orElseThrow(() -> duplicate);
            reopenRecovery(row, raced, recoveryId, now, code, summary);
        }
    }

    /**
     * 按声明的租户与业务条件查询恢复记录，供调用方核对可见性、状态或幂等关系。
     */
    private Optional<Recovery> findRecovery(Claimed row) {
        return mapper.findRecovery(bytes(row.tenantId()), bytes(row.outboxId())).stream().findFirst();
    }

    /**
     * 重新开放仍可恢复的失败记录，保留本次失败详情。
     */
    private void reopenRecovery(Claimed row, Recovery existing, UUID recoveryId,
                                Instant now, String code, String summary) {
        if (!recoveryId.equals(existing.recoveryId())
                || !Arrays.equals(existing.intent(), row.businessIntentKey())
                || !("OPEN".equals(existing.state()) || "REPLAYING".equals(existing.state()))) {
            throw new IllegalStateException("Conflicting Workflow Notification recovery");
        }
        if ("OPEN".equals(existing.state())) {
            if (existing.attemptCount() != row.attemptCount()) {
                throw new IllegalStateException("Conflicting Workflow Notification recovery attempts");
            }
            return;
        }
        // 人工重放后的新一轮发送预算再次耗尽时，仍保留同一恢复身份
        // 及此前人工尝试记录。
        int reopened = mapper.reopenRecovery(row.attemptCount(), code, summary, ts(now), bytes(row.tenantId()), bytes(recoveryId),
                existing.lockVersion());
        if (reopened != 1) throw new IllegalStateException("Workflow Notification recovery reopen CAS lost");
    }

    /**
     * 发送成功后结清关联重放，使恢复状态与原 Outbox 终态一致。
     */
    private void resolveReplay(UUID tenantId, UUID outboxId, Instant now) {
        mapper.resolveReplay(ts(now), bytes(tenantId), bytes(outboxId));
    }

    /**
     * 按声明的租户与业务条件查询当前令牌持有的消息，供调用方核对可见性、状态或幂等关系。
     */
    private Optional<Claimed> findClaimed(UUID tenantId, UUID outboxId, String token,
                                          int attempts, long lockVersion) {
        return mapper.findClaimed(bytes(tenantId), bytes(outboxId), attempts, token, lockVersion)
                .stream().findFirst();
    }

    /**
     * 同一工作流通知 Outbox 来源始终派生同一恢复记录主键。
     */

    private static String validCode(String code) {
        if (code == null || !code.matches("[A-Z][A-Z0-9_]{0,95}")) return "BROKER_RESULT_UNKNOWN";
        return code;
    }

    /**
     * 清理并缩减错误摘要，避免把无界或超长异常文本写入恢复记录。
     */
    private static String sanitize(String value) {
        if (value == null) return null;
        String result = value.replaceAll("[\\p{Cntrl}\\s]+", " ").trim();
        return result.isEmpty() ? null : result.substring(0, Math.min(1000, result.length()));
    }

    /**
     * 确认当前线程有活动数据库事务，防止需要原子性的写入在事务外执行。
     */
    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Workflow Notification Outbox mutation requires a transaction");
        }
    }

    /**
     * 核验目标租户与当前上下文一致，防止持久化操作跨出租户边界。
     */
    private static void requireTenant(UUID tenantId) {
        TenantContextHolder.current().requireTenant(tenantId);
    }

    /**
     * 核验领取令牌非空且长度满足存储限制，确保租约持有者可被可靠识别。
     */
    private static void requireToken(String token) {
        if (token == null || token.isBlank() || token.length() > 96) {
            throw new IllegalArgumentException("claim token must be 1..96 characters");
        }
    }

    /**
     * 将 UUID 编码为数据库使用的二进制值，空标识保持为空。
     */
    private static byte[] bytes(UUID value) {
        return value == null ? null : ByteBuffer.allocate(16)
                .putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits()).array();
    }

    /**
     * 将非空时间点转换为 JDBC 时间戳，供 MyBatis 参数绑定使用。
     */
    private static Timestamp ts(Instant value) { return Timestamp.from(Objects.requireNonNull(value)); }

}
