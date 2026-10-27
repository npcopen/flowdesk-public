package com.flowdesk.ticket.sla;

import com.flowdesk.ticket.sla.SlaClockService;
import com.flowdesk.ticket.sla.SlaPersistenceMapper;
import static com.flowdesk.ticket.persistence.SlaPersistenceSupport.timestamp;
import static com.flowdesk.ticket.persistence.SlaPersistenceSupport.requireTransaction;
import static com.flowdesk.ticket.persistence.SlaPersistenceSupport.requireTenant;
import java.util.Optional;
import java.util.List;
import java.util.Collection;
import java.sql.Timestamp;
import org.springframework.dao.DuplicateKeyException;
import com.flowdesk.ticket.persistence.Uuids;
import com.flowdesk.ticket.model.sla.*;
import com.flowdesk.common.tenant.ActorRef;
import com.flowdesk.common.tenant.TenantContext;
import com.flowdesk.common.tenant.TenantContextHolder;
import com.flowdesk.common.tenant.TenantContextScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * 数据库提交后尽力将权威 SLA 时钟投影到 Redis 候选索引。
 * Redis 不参与工单写事务，遗漏或失败的投影由有界 MySQL 安全扫描补齐。
 */
public final class SlaIndexProjection {
    private static final Logger LOG = LoggerFactory.getLogger(SlaIndexProjection.class);

    private final SlaPersistenceMapper repository;
    private final SlaRebuildLeaseService leaseRepository;
    private final SlaDueIndex index;
    private final Clock clock;

    /**
     * 构造SLA组件并保存调用依赖；支持注入时钟以控制时间边界。
     */
    public SlaIndexProjection(SlaPersistenceMapper repository, SlaRebuildLeaseService leaseRepository,
                              SlaDueIndex index, Clock clock) {
        this.repository = repository;
        this.leaseRepository = leaseRepository;
        this.index = index;
        this.clock = clock;
    }

    /**
     * 注册提交后重新读取最终时钟状态的索引投影。
     * 同一事务多次调用不会改变正确性，每次投影均以提交后的 MySQL 事实为依据。
     */
    public void schedule(UUID tenantId, UUID slaClockId) {
        if (tenantId == null || slaClockId == null) {
            return;
        }
        Runnable work = () -> project(tenantId, slaClockId);
        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                /**
                 * 在业务事务成功提交后执行投影，避免回滚产生外部索引副作用。
                 */
                @Override
                public void afterCommit() {
                    work.run();
                }
            });
            return;
        }
        // 仅为少量内部调用提供立即执行的兜底；生产工单变更均在本地事务内注册提交回调。
        work.run();
    }

    /**
     * 安排时钟投影，事务内调用时延后至成功提交后执行。
     * 传入租户必须与当前上下文一致。
     */
    private void project(UUID tenantId, UUID slaClockId) {
        try {
            TenantContext current = TenantContextHolder.currentOrNull();
            if (current != null) {
                current.requireTenant(tenantId);
                projectInTenant(tenantId, slaClockId);
                return;
            }
            TenantContext task = TenantContext.ofPersistedTask(tenantId,
                    ActorRef.service("flowdesk-ticket-sla-projection"), UUID.randomUUID());
            try (TenantContextScope ignored = TenantContextScope.open(task)) {
                projectInTenant(tenantId, slaClockId);
            }
        }
        catch (RuntimeException unavailable) {
            // 索引失败由扫描器的 MySQL 安全扫描恢复，不能暴露基础设施细节，也不能使已提交的工单操作变为失败。
            LOG.warn("SLA Redis projection deferred; MySQL safety sweep will reconcile it");
        }
    }

    /**
     * 在目标租户上下文读取时钟，并同步其有效阶段候选。
     */
    private void projectInTenant(UUID tenantId, UUID slaClockId) {
        Instant observedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (SlaIndexRebuilder.isTenantRebuilding(tenantId)
                || leaseRepository.isSlaRebuildActive(tenantId, observedAt)) {
            return;
        }
        if (!index.available()) {
            return;
        }
        com.flowdesk.ticket.model.sla.SlaClockRow clockRow = findSlaClockById(tenantId, slaClockId)
                .orElse(null);
        if (clockRow == null || !"RUNNING".equals(clockRow.status())) {
            remove(tenantId, slaClockId, "REMINDER");
            remove(tenantId, slaClockId, "BREACH");
            return;
        }

        boolean reminderTriggered = findSlaTrigger(tenantId, slaClockId,
                "REMINDER", false).isPresent();
        if (clockRow.reminderDueAt() != null && !reminderTriggered
                && !observedAt.isAfter(clockRow.deadlineAt())) {
            upsert(tenantId, slaClockId, "REMINDER", clockRow.reminderDueAt());
        }
        else {
            remove(tenantId, slaClockId, "REMINDER");
        }

        boolean breachTriggered = findSlaTrigger(tenantId, slaClockId,
                "BREACH", false).isPresent();
        if (!breachTriggered) {
            upsert(tenantId, slaClockId, "BREACH", clockRow.deadlineAt());
        }
        else {
            remove(tenantId, slaClockId, "BREACH");
        }
    }

    /**
     * 写入或刷新到期候选，使扫描器按时间定位 SLA 阶段。
     */
    private void upsert(UUID tenantId, UUID slaClockId, String stage, Instant dueAt) {
        try {
            index.upsert(tenantId, slaClockId, stage, dueAt);
        }
        catch (RuntimeException unavailable) {
            LOG.warn("SLA Redis projection write deferred; MySQL safety sweep will reconcile it");
        }
    }

    /**
     * 移除已失效候选，避免已结算或已触发阶段继续被扫描。
     */
    private void remove(UUID tenantId, UUID slaClockId, String stage) {
        try {
            index.remove(tenantId, slaClockId, stage);
        }
        catch (RuntimeException unavailable) {
            LOG.warn("SLA Redis projection removal deferred; MySQL safety sweep will reconcile it");
        }
    }

    /**
     * 按当前租户和标识读取 SLA 时钟，无匹配时返回空结果。
     */
    private Optional<SlaClockRow> findSlaClockById(UUID tenantId, UUID slaClockId) {
        requireTenant(tenantId);
        return repository.findSlaClockByIdSelect(Uuids.toBytes(tenantId), Uuids.toBytes(slaClockId))
                .stream().findFirst();
    }

    /**
     * 读取指定时钟与阶段的触发事实，供去重和通知重试。
     * 调用必须处于数据库事务中，以保持当前步骤与关联事实原子提交。
     * 传入租户必须与当前上下文一致。
     */
    private Optional<SlaTriggerDetailRow> findSlaTrigger(UUID tenantId, UUID slaClockId, String stage,
                                                        boolean lock) {
        requireTenant(tenantId);
        if (lock) requireTransaction();
        return repository.findSlaTriggerSelect(Uuids.toBytes(tenantId), Uuids.toBytes(slaClockId), stage, lock).stream().findFirst();
    }
}
