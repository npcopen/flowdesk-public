package com.flowdesk.alert.service.impl;
import com.flowdesk.alert.service.AlertOutboxDeliveryService;
import com.flowdesk.alert.event.AlertEventContract;
import com.flowdesk.alert.model.*;

import com.flowdesk.common.tenant.TenantContextHolder;
import org.springframework.dao.DuplicateKeyException;
import com.flowdesk.alert.mapper.AlertOutboxMapper;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.Objects;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 管理 Alert 两类出站事件的数据库投递状态，供发布器执行领取、重试、成功确认和恢复结算。
 *
 * <p>跨租户扫描只用于发现候选；后续操作必须匹配线程租户，本能力为领取和结算各自创建短事务。
 * 领取时同时检查状态、版本和旧租约，结算时再检查本次 token、尝试次数及有效租约，
 * 避免多个实例重复取得同一轮结算权。这里的 PUBLISHED 只记录 Broker 确认，工单最终关联由结果消费者建立。
 */
public final class AlertOutboxDeliveryServiceImpl implements AlertOutboxDeliveryService {
    private final AlertOutboxMapper mapper;
    private final TransactionTemplate transactions;

    /**
     * 绑定告警发件箱 Mapper，并用事务管理器建立短事务模板，使领取、确认、重试及恢复各自原子提交。
     */
    public AlertOutboxDeliveryServiceImpl(AlertOutboxMapper mapper, PlatformTransactionManager tx) {
        this.mapper = mapper;
        this.transactions = new TransactionTemplate(tx);
    }

    /**
     * 有界扫描已到重试时间的 PENDING，以及租约已到期的 PUBLISHING；每轮最多返回五十条。
     * 此时不持锁也不承诺归属，返回后仍需条件领取；达到第八次的记录也要被发现，以免崩溃后无法结算恢复。
     */
    public List<AlertOutboxCandidate> candidates(Instant now, Instant cutoff) {
        return mapper.findCandidates(AlertEventContract.TICKET_CREATE_REQUESTED.contractRef(), AlertEventContract.INCIDENT_RECOVERED.contractRef(), AlertEventContract.MAX_ATTEMPTS, now, cutoff);
    }

    /**
     * 对待发送记录或租约已过期的发布记录执行条件更新，成功后保存新 token 并增加尝试次数。
     * 接管旧租约也消耗一次机会，因为无法判断上一个线程是否已经把消息送达 Broker。
     * 快照过期或竞争失败返回空结果，调用方跳过即可；不允许因此仍然发送。
     */
    public Optional<AlertOutboxClaim> claim(AlertOutboxCandidate c, String token, Instant now, Instant cutoff) {
        return Objects.requireNonNull(transactions.execute(tx -> {
            requireTx();
            requireTenant(c.tenantId());
            int changed;
            if ("PENDING".equals(c.status()))
                changed =
                        mapper.claimPending(token, now, c.tenantId(), c.outboxId(), c.attemptCount(), AlertEventContract.MAX_ATTEMPTS, c.lockVersion());
            else
                changed =
                        mapper.claimExpired(token, now, c.tenantId(), c.outboxId(), c.attemptCount(), AlertEventContract.MAX_ATTEMPTS, c.lockedBy(), c.lockVersion(), cutoff);
            return changed == 1
                    ? claimed(
                            c.tenantId(),
                            c.outboxId(),
                            token,
                            c.attemptCount() + 1,
                            c.lockVersion() + 1)
                    : Optional.empty();
        }));
    }

    /**
     * 八次投递机会已耗尽时，只更新租约取得恢复结算权，不再增加次数或授权发送。
     * 覆盖第八次领取后进程崩溃的场景：接管者可记录未知结果，重启不会重新获得投递额度。
     */
    public Optional<AlertOutboxClaim> claimRecovery(AlertOutboxCandidate c, String token, Instant now, Instant cutoff) {
        return Objects.requireNonNull(transactions.execute(tx -> {
            requireTx();
            requireTenant(c.tenantId());
            if (c.attemptCount() != 8) return Optional.empty();
            int changed;
            if ("PENDING".equals(c.status()))
                changed =
                        mapper.claimRecoveryPending(token, now, c.tenantId(), c.outboxId(), c.lockVersion());
            else
                changed =
                        mapper.claimRecoveryExpired(token, now, c.tenantId(), c.outboxId(), c.lockedBy(), c.lockVersion(), cutoff);
            return changed == 1
                    ? claimed(c.tenantId(), c.outboxId(), token, 8, c.lockVersion() + 1)
                    : Optional.empty();
        }));
    }

    /**
     * 按刚取得的 token、次数和版本回读领取结果，避免把旧快照误作为当前发送凭据。
     */
    private Optional<AlertOutboxClaim> claimed(
            UUID tenant, UUID outbox, String token, int attempt, long version) {
        return mapper.findClaimed(tenant, outbox, attempt, token, version)
                .stream()
                .findFirst();
    }

    /**
     * Broker 明确确认后，只有本轮持有者且租约仍有效才能标为 PUBLISHED。
     * locked_at 必须严格晚于 cutoff；恰好到期的租约不能结算。成功时同步结束正在重放的 Outbox 恢复记录。
     */
    public boolean published(AlertOutboxClaim r, Instant now, Instant cutoff) {
        return Objects.requireNonNull(transactions.execute(tx -> {
            requireTx();
            requireTenant(r.tenantId());
            int changed =
                    mapper.markPublished(now, r.tenantId(), r.outboxId(), r.attemptCount(), r.token(), r.lockVersion(), cutoff);
            if (changed == 1) {
                mapper.resolvePublishedRecovery(now, r.tenantId(), r.outboxId());
            }
            return changed == 1;
        }));
    }

    /**
     * 发送失败或结果未知时，在有效租约内释放领取并持久化下次重试时间。
     * 建单请求还同步更新故障关联上的重试信息；若已被接管，所有这些写入都应跳过，避免旧线程回写。
     */
    public boolean pending(AlertOutboxClaim r, Instant now, Instant cutoff, Instant next, String code) {
        return Objects.requireNonNull(transactions.execute(tx -> {
            requireTx();
            requireTenant(r.tenantId());
            int changed =
                    mapper.releasePending(next, code, now, r.tenantId(), r.outboxId(), r.attemptCount(), r.token(), r.lockVersion(), cutoff);
            if (changed == 1 && isCreate(r))
                mapper.updateLinkRetry(r.attemptCount(), now, r.tenantId(), r.incidentId());
            return changed == 1;
        }));
    }

    /**
     * 第八次尝试结束或结果未知时，将 Outbox、必要的建单关联及恢复记录一起转为待人工处理。
     * 人工重放再次耗尽会重开原恢复记录；首次耗尽才创建记录。唯一键竞争时必须核对业务意图和 OPEN 状态，
     * 不允许把其他业务的恢复记录当作本次成功，任何不一致都交由调用方回滚。
     */
    public boolean recover(AlertOutboxClaim r, Instant now, Instant cutoff, String code) {
        return Objects.requireNonNull(transactions.execute(tx -> {
            requireTx();
            requireTenant(r.tenantId());
            int changed =
                    mapper.markRecoveryRequired(now, code, r.tenantId(), r.outboxId(), r.token(), r.lockVersion(), cutoff);
            if (changed != 1) return false;
            if (isCreate(r))
                mapper.markLinkRecoveryRequired(now, code, r.tenantId(), r.incidentId());
            int reopened =
                    mapper.reopenRecovery(code, now, r.tenantId(), r.outboxId(), r.intent());
            if (reopened == 0)
                try {
                    mapper.insertRecovery(r.tenantId(), UUID.randomUUID(), r.outboxId(), r.intent(), code, now);
                } catch (DuplicateKeyException duplicate) {
                    var existing =
                            mapper.findRecovery(r.tenantId(), r.outboxId())
                                    .stream()
                                    .findFirst()
                                    .orElseThrow();
                    if (!Arrays.equals(existing.intent(), r.intent())
                            || !"OPEN".equals(existing.state())) throw duplicate;
                }
            mapper.insertRecoveryAudit(r.tenantId(), UUID.randomUUID(), r.outboxId(), code, r.correlationId(), now);
            return true;
        }));
    }

    /**
     * 判断发件箱是否属于建单请求契约，决定是否同时维护故障到工单的关联状态。
     */
    private static boolean isCreate(AlertOutboxClaim r) {
        return AlertEventContract.TICKET_CREATE_REQUESTED.contractRef().equals(r.contractRef());
    }

    /**
     * 要求当前线程已处于真实数据库事务，防止状态、恢复记录和审计被拆分提交。
     */
    private static void requireTx() {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Alert Outbox mutation requires transaction");
    }

    /**
     * 核对目标租户与线程可信上下文一致，阻止内部调用跨越租户边界。
     */
    private static void requireTenant(UUID tenant) {
        TenantContextHolder.current().requireTenant(tenant);
    }










}
