package com.flowdesk.workflow.service.impl;


import com.flowdesk.workflow.outbox.WorkflowOutboxMapper;
import com.flowdesk.workflow.service.WorkflowOutboxService;
import com.flowdesk.workflow.model.WorkflowOutboxModels.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.flowdesk.common.tenant.TenantContextHolder;
import com.flowdesk.workflow.event.WorkflowReverseEventContract;
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
 * 扫描工作流反向 Outbox 时恢复持久化租户上下文，并按租户维护发送与恢复状态。
 */
public final class WorkflowOutboxServiceImpl implements WorkflowOutboxService {
    private final WorkflowOutboxMapper mapper;
    private final TransactionTemplate transactions;

    /**
     * 注入反向事件 Outbox 映射及事务管理器，发送领取与结果结算使用独立短事务，网络发送由上层执行。
     */
    public WorkflowOutboxServiceImpl(WorkflowOutboxMapper mapper, PlatformTransactionManager manager) {
        this.mapper = mapper;
        this.transactions = new TransactionTemplate(manager);
    }

    /**
     * 按时间、过期租约与数量上限扫描本轮可处理的 Outbox 候选消息。
     */
    public List<Candidate> findCandidates(Instant now, Instant leaseCutoff, int limit) {
        if (limit < 1 || limit > WorkflowReverseEventContract.OUTBOX_MAX_CLAIMS) {
            throw new IllegalArgumentException("limit must be 1..50");
        }
        return mapper.findCandidates(WorkflowReverseEventContract.InstanceEstablished.CONTRACT_REF,
                WorkflowReverseEventContract.ApprovalFinalized.CONTRACT_REF,
                WorkflowReverseEventContract.OUTBOX_MAX_ATTEMPTS, ts(now), ts(leaseCutoff), limit);
    }

    /**
     * 按状态、版本和租约条件领取发送权，竞争失败返回空值以防重复结算。
     */
    public Optional<Claimed> claimForSend(
            Candidate candidate, String token, Instant now, Instant leaseCutoff) {
        return transactions.execute(status -> {
            requireTransaction();
            requireTenant(candidate.tenantId());
            requireToken(token);
            int updated;
            if ("PENDING".equals(candidate.status())) {
                updated = mapper.claimPendingForSend(token, ts(now), bytes(candidate.tenantId()), bytes(candidate.outboxId()),
                        candidate.attemptCount(), WorkflowReverseEventContract.OUTBOX_MAX_ATTEMPTS,
                        candidate.lockVersion());
            }
            else if ("PUBLISHING".equals(candidate.status()) && candidate.lockedBy() != null) {
                updated = mapper.takeOverForSend(token, ts(now), bytes(candidate.tenantId()), bytes(candidate.outboxId()),
                        candidate.attemptCount(), WorkflowReverseEventContract.OUTBOX_MAX_ATTEMPTS,
                        candidate.lockedBy(), candidate.lockVersion(), ts(leaseCutoff));
            }
            else {
                return Optional.empty();
            }
            if (updated != 1) {
                return Optional.empty();
            }
            return findClaimed(candidate.tenantId(), candidate.outboxId(), token,
                    candidate.attemptCount() + 1, candidate.lockVersion() + 1);

        });
    }

    /**
     * 为达到发送上限的消息领取恢复权，核验当前版本和租约后继续处理。
     */
    public Optional<Claimed> claimForRecovery(
            Candidate candidate, String token, Instant now, Instant leaseCutoff) {
        return transactions.execute(status -> {
            requireTransaction();
            requireTenant(candidate.tenantId());
            requireToken(token);
            if (candidate.attemptCount() != WorkflowReverseEventContract.OUTBOX_MAX_ATTEMPTS) {
                return Optional.empty();
            }
            int updated;
            if ("PENDING".equals(candidate.status())) {
                updated = mapper.claimPendingForRecovery(token, ts(now), bytes(candidate.tenantId()), bytes(candidate.outboxId()),
                        WorkflowReverseEventContract.OUTBOX_MAX_ATTEMPTS, candidate.lockVersion());
            }
            else if ("PUBLISHING".equals(candidate.status()) && candidate.lockedBy() != null) {
                updated = mapper.takeOverForRecovery(token, ts(now), bytes(candidate.tenantId()), bytes(candidate.outboxId()),
                        WorkflowReverseEventContract.OUTBOX_MAX_ATTEMPTS, candidate.lockedBy(),
                        candidate.lockVersion(), ts(leaseCutoff));
            }
            else {
                return Optional.empty();
            }
            if (updated != 1) {
                return Optional.empty();
            }
            return findClaimed(candidate.tenantId(), candidate.outboxId(), token,
                    WorkflowReverseEventContract.OUTBOX_MAX_ATTEMPTS,
                    candidate.lockVersion() + 1);

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
            if (updated == 1) {
                mapper.resolveReplay(ts(now), bytes(row.tenantId()), bytes(row.outboxId()));
            }
            return updated == 1;

        });
    }

    /**
     * 将当前持有消息退回待发送状态，保存下次重试时间与错误码。
     */
    public boolean markPending(Claimed row, Instant now, Instant leaseCutoff,
                               Instant next, String errorCode) {
        return transactions.execute(status -> {
            requireTransaction();
            requireTenant(row.tenantId());
            return mapper.markPending(ts(next), errorCode, ts(now), bytes(row.tenantId()), bytes(row.outboxId()),
                    row.attemptCount(), row.claimToken(), row.lockVersion(), ts(leaseCutoff)) == 1;

        });
    }

    /**
     * 将耗尽自动发送次数的消息转入恢复状态，保存恢复事实与排障信息。
     */
    public boolean moveToRecovery(Claimed row, Instant now, Instant leaseCutoff,
                                  String errorCode, String summary, UUID recoveryId) {
        return transactions.execute(status -> {
            requireTransaction();
            requireTenant(row.tenantId());
            if (row.attemptCount() != WorkflowReverseEventContract.OUTBOX_MAX_ATTEMPTS) {
                throw new IllegalArgumentException("Outbox recovery requires attempt 8");
            }
            int updated = mapper.moveToRecovery(ts(now), errorCode, bytes(row.tenantId()), bytes(row.outboxId()),
                    WorkflowReverseEventContract.OUTBOX_MAX_ATTEMPTS, row.claimToken(), row.lockVersion(),
                    ts(leaseCutoff));
            if (updated != 1) {
                return false;
            }
            ensureRecovery(row, now, errorCode, summary, recoveryId);
            mapper.insertRecoveryAudit(bytes(row.tenantId()), bytes(UUID.randomUUID()), bytes(recoveryId), summary,
                    errorCode, bytes(row.correlationId()), ts(now));
            return true;

        });
    }

    /**
     * 创建或核对恢复记录，唯一键竞争只接受相同意图的既有事实。
     */
    private void ensureRecovery(Claimed row, Instant now, String code,
                                String summary, UUID recoveryId) {
        try {
            mapper.insertRecovery(bytes(row.tenantId()), bytes(recoveryId), bytes(row.outboxId()),
                    row.businessIntentKey(), WorkflowReverseEventContract.OUTBOX_MAX_ATTEMPTS, code,
                    summary, ts(now));
        }
        catch (DuplicateKeyException duplicate) {
            Recovery existing = mapper.findRecovery(bytes(row.tenantId()), bytes(row.outboxId())).stream().findFirst()
                    .orElseThrow(() -> duplicate);
            if (!Arrays.equals(existing.intent(), row.businessIntentKey())
                    || !"OPEN".equals(existing.state())) {
                throw new IllegalStateException("Conflicting Workflow Outbox recovery", duplicate);
            }
        }
    }

    /**
     * 按声明的租户与业务条件查询当前令牌持有的消息，供调用方核对可见性、状态或幂等关系。
     */
    private Optional<Claimed> findClaimed(UUID tenantId, UUID outboxId, String token,
                                          int attempt, long lockVersion) {
        return mapper.findClaimed(bytes(tenantId), bytes(outboxId), attempt, token, lockVersion).stream().findFirst();
    }

    /**
     * 确认当前线程有活动数据库事务，防止需要原子性的写入在事务外执行。
     */
    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Workflow Outbox mutation requires a transaction");
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
                .putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits())
                .array();
    }

    /**
     * 将非空时间点转换为 JDBC 时间戳，供 MyBatis 参数绑定使用。
     */
    private static Timestamp ts(Instant value) {
        return Timestamp.from(Objects.requireNonNull(value));
    }

}
