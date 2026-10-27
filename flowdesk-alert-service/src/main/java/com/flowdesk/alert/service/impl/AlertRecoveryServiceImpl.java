package com.flowdesk.alert.service.impl;
import com.flowdesk.alert.recovery.*;
import com.flowdesk.alert.service.*;
import com.flowdesk.alert.mapper.AlertRecoveryMapper;
import com.flowdesk.alert.model.*;

import com.flowdesk.common.recovery.RecoveryReplayResumer;
import com.flowdesk.alert.event.AlertEventContract;
import com.flowdesk.alert.service.AlertCursorTokenService;
import com.flowdesk.common.authz.PermissionCode;
import com.flowdesk.common.authz.PermissionPolicy;
import com.flowdesk.common.tenant.ActorRef;
import com.flowdesk.common.tenant.TenantContext;
import com.flowdesk.common.tenant.TenantContextHolder;
import com.flowdesk.common.web.ApiException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 告警消息人工恢复的应用入口，负责查询恢复记录，以及决定一次人工重放是否可以被接纳。
 *
 * <p>阅读重放流程时从 {@link #replay} 开始：先在事务内锁定原消息和恢复记录，验证版本、当前状态及幂等意图，
 * 再一起提交接纳状态和操作审计。Inbox 在提交后交给 {@link AlertRecoveryExecutionService} 执行；Outbox 则重新进入正常发布器的队列。
 *
 * <p>这里裁决的是“是否接受人工尝试”，消息最终是否成功仍由原消费者或发布器决定。Mapper 执行 SQL，
 * 本类持有接纳事务的边界，避免恢复记录、原消息与审计分别提交。
 */
public final class AlertRecoveryServiceImpl implements AlertRecoveryService {
    private static final Set<String> DIRECTIONS = Set.of("INBOX", "OUTBOX"),
            STATES = Set.of("OPEN", "REPLAYING", "RESOLVED"),
            SORTS = Set.of("-recoveryRequiredAt", "recoveryRequiredAt", "-deadLetteredAt");
    private final AlertRecoveryMapper mapper;
    private final TransactionTemplate transactions;
    private final AlertRecoveryExecutionService worker;

    /**
     * 绑定恢复 Mapper、事务管理器及重放工作器，使接纳裁决先原子提交，之后再执行 Inbox 重放。
     */
    public AlertRecoveryServiceImpl(
            AlertRecoveryMapper r,
            PlatformTransactionManager tx,
            AlertRecoveryExecutionService w) {
        mapper = r;
        transactions = new TransactionTemplate(tx);
        worker = w;
    }






    /**
     * 校验人工操作者、原因、幂等键和 If-Match，然后在一个数据库事务内完成接纳。
     *
     * <p>工作线程只能在事务提交后启动，否则可能读不到接纳记录，或在事务回滚后仍执行消息。
     * 提交后进程若中断，message_recovery 中 INBOX 方向记录的租约会让后台扫描继续同一次人工尝试。
     */
    public ReplayResult replay(UUID id, String reason, String key, String ifMatch) {
        PermissionPolicy.requireCurrentPrincipal(PermissionCode.RECOVERY_REPLAY_TENANT);
        TenantContext c = TenantContextHolder.current();
        if (!(c.actor() instanceof ActorRef.Human h))
            throw new IllegalStateException("Recovery requires human actor");
        String acceptedReason = AlertRecoverySupport.reason(reason),
                acceptedKey = AlertRecoverySupport.key(key);
        long version = AlertRecoverySupport.version(ifMatch);
        Acceptance a =
                Objects.requireNonNull(
                        transactions.execute(
                                tx ->
                                        accept(
                                                c,
                                                id,
                                                h.userId(),
                                                acceptedReason,
                                                acceptedKey,
                                                version)));
        if (!a.replayed() && a.work() != null) worker.submit(a.work());
        return new ReplayResult(a.view(), a.replayed());
    }

    /**
     * 在 replay 创建的事务中裁决接纳。先无锁读取路由，再按“原消息 → 恢复记录”的顺序取得行锁，
     * 加锁后重新核对方向和来源，避免把首次读取的快照当成最终裁决依据。
     *
     * <p>幂等审计先于版本和可重放状态校验：原请求已提交但响应丢失时，相同请求应返回已有结果，
     * 不能因为接纳操作自己推进了版本而被拒绝。同键但原因等意图不同，则明确返回冲突。
     */
    private Acceptance accept(
            TenantContext c, UUID id, UUID actor, String reason, String key, long expected) {
        UUID audit = AlertRecoveryIdempotency.auditId(c, id, key);
        String digest = AlertRecoveryIdempotency.requestDigest(id, reason);
        var candidate =
                mapper.findRecovery(c.tenantId(), id, false).stream().findFirst()
                        .orElseThrow(AlertRecoveryServiceImpl::routeMismatch);
        if ("INBOX".equals(candidate.direction())) {
            var source =
                    mapper.findInbox(c.tenantId(), candidate.source(), true).stream().findFirst()
                            .filter(
                                    s ->
                                            s.contractVersion() == 1
                                                    && AlertEventContract.TICKET_ESTABLISHED
                                                            .contractRef()
                                                            .equals(s.contractRef()))
                            .orElseThrow(AlertRecoveryServiceImpl::routeMismatch);
            var recovery = locked(c.tenantId(), id, "INBOX", source.inboxId());
            var prior = mapper.findAudit(c.tenantId(), audit).stream().findFirst();
            if (prior.isPresent()) {
                validateAudit(prior.get(), id, actor, digest, source.correlation());
                return new Acceptance(AlertRecoveryResponseMapper.view(recovery), true, null);
            }
            // 首次接纳才增加人工重放次数并建立租约；重复命令不会创建第二个工作任务。
            validateReplayable(recovery, source.status(), expected);
            Instant now = now();
            UUID attempt = UUID.randomUUID();
            if (acceptInbox(c.tenantId(), recovery, actor, reason, attempt, now) != 1)
                conflict();
            mapper.insertAudit(
                    c.tenantId(),
                    audit,
                    "ALERT_RECOVERY_REPLAY_REQUESTED",
                    id,
                    "SUCCEEDED",
                    actor,
                    digest,
                    null,
                    attempt,
                    source.correlation(),
                    now);
            var accepted = mapper.findRecovery(c.tenantId(), id, false).stream().findFirst().orElseThrow();
            return new Acceptance(
                    AlertRecoveryResponseMapper.view(accepted),
                    false,
                    new Work(
                            c.tenantId(),
                            id,
                            source.inboxId(),
                            actor,
                            reason,
                            attempt,
                            source.correlation(),
                            RecoveryReplayResumer.leaseUntil(now)));
        }
        if ("OUTBOX".equals(candidate.direction())) {
            var source =
                    mapper.findOutbox(c.tenantId(), candidate.source(), true).stream().findFirst()
                            .filter(AlertRecoveryServiceImpl::activeOutbox)
                            .orElseThrow(AlertRecoveryServiceImpl::routeMismatch);
            var recovery = locked(c.tenantId(), id, "OUTBOX", source.outboxId());
            var prior = mapper.findAudit(c.tenantId(), audit).stream().findFirst();
            if (prior.isPresent()) {
                validateAudit(prior.get(), id, actor, digest, source.correlation());
                return new Acceptance(AlertRecoveryResponseMapper.view(recovery), true, null);
            }
            validateReplayable(recovery, source.status(), expected);
            // 建单消息还绑定 IncidentTicketLink；关联已收敛时不能仅把旧 Outbox 再次开放。
            if (AlertEventContract.TICKET_CREATE_REQUESTED
                            .contractRef()
                            .equals(source.contractRef())
                    && !"RECOVERY_REQUIRED".equals(source.linkStatus()))
                throw ApiException.conflict("RECOVERY_NOT_REPLAYABLE", "IncidentTicketLink 已经收敛。");
            Instant now = now();
            UUID attempt = UUID.randomUUID();
            // 同一事务同时恢复 Outbox、必要的建单关联和人工审计；后续由正常发布器发送原消息。
            if (acceptOutbox(c.tenantId(), recovery, source, actor, reason, attempt, now)
                    != 1) conflict();
            mapper.insertAudit(
                    c.tenantId(),
                    audit,
                    "ALERT_RECOVERY_REPLAY_REQUESTED",
                    id,
                    "SUCCEEDED",
                    actor,
                    digest,
                    null,
                    attempt,
                    source.correlation(),
                    now);
            return new Acceptance(
                    AlertRecoveryResponseMapper.view(
                            mapper.findRecovery(c.tenantId(), id, false).stream().findFirst().orElseThrow()),
                    false,
                    null);
        }
        throw routeMismatch();
    }

    /** 原消息已加锁后，再锁定并确认恢复记录仍指向该消息；跨路由或来源不匹配统一视为不存在。 */
    private AlertRecoveryRow locked(
            UUID t, UUID id, String direction, UUID source) {
        return mapper.findRecovery(t, id, true).stream().findFirst()
                .filter(r -> direction.equals(r.direction()) && source.equals(r.source()))
                .orElseThrow(AlertRecoveryServiceImpl::routeMismatch);
    }

    /** 只允许当前 Alert 路由拥有的两个 v1 出站契约，防止用本入口重放其他服务或旧契约的消息。 */
    private static boolean activeOutbox(AlertRecoveryOutbox s) {
        return s.contractVersion() == 1
                && Set.of(
                                AlertEventContract.TICKET_CREATE_REQUESTED.contractRef(),
                                AlertEventContract.INCIDENT_RECOVERED.contractRef())
                        .contains(s.contractRef());
    }

    /** 恢复记录必须开放，且原消息仍需恢复；两侧事实一致后才比较操作员读取到的版本。 */
    private static void validateReplayable(
            AlertRecoveryRow r, String source, long expected) {
        if (!"OPEN".equals(r.state())) {
            if ("REPLAYING".equals(r.state())) conflict();
            throw ApiException.conflict("RECOVERY_NOT_REPLAYABLE", "恢复记录不再允许人工重放。");
        }
        if (!"RECOVERY_REQUIRED".equals(source))
            throw ApiException.conflict("RECOVERY_NOT_REPLAYABLE", "原消息事实已经终态收敛。");
        if (r.version() != expected) throw AlertRecoverySupport.versionConflict(r.version());
    }

    /** 审计 ID 命中只代表幂等键相同，还要核对操作者、目标、意图摘要和原消息因果关系。 */
    private static void validateAudit(
            AlertRecoveryAudit a, UUID recovery, UUID actor, String digest, UUID cause) {
        if (!"ALERT_RECOVERY_REPLAY_REQUESTED".equals(a.action())
                || !recovery.equals(a.target())
                || !"SUCCEEDED".equals(a.outcome())
                || !"USER".equals(a.actorType())
                || !actor.equals(a.actor())
                || !digest.equals(a.reason())
                || a.error() != null
                || !Objects.equals(cause, a.causation()))
            throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED", "同一 Idempotency-Key 已用于不同恢复意图。");
    }

    /**
     * 抛出恢复重放竞争冲突，要求调用者刷新当前状态后再决定是否提交新的人工尝试。
     */
    private static void conflict() {
        throw ApiException.conflict("RECOVERY_REPLAY_CONFLICT", "恢复记录的重放裁决已被其他请求取得。");
    }

    /**
     * 构造恢复记录不存在或不属于当前 Alert 路由的统一错误，避免跨路由读取或执行消息。
     */
    private static ApiException routeMismatch() {
        return ApiException.notFound("RECOVERY_ROUTE_MISMATCH", "恢复记录不存在或不属于 Alert 路由。");
    }





    /**
     * 读取当前时间并截断到微秒，使写入与数据库时间精度一致。
     */
    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }



    /** work 仅在首次接纳 Inbox 时生成；Outbox 和已接纳请求均不需要本地消费任务。 */
    private record Acceptance(AlertRecoveryView view, boolean replayed, Work work) {}

    /**
     * 在接纳事务中领取 Inbox 的人工重放，并将尝试标识、操作者与租约一并写入恢复记录。
     */

    private int acceptInbox(UUID tenant, AlertRecoveryRow r, UUID actor, String reason, UUID corr, Instant now) {
        return mark(tenant, r, actor, reason, corr, now);
    }
    /**
     * 在接纳事务中重置原发件箱及必要的建单关联，任一版本条件失败都中止整次人工重放。
     */
    private int acceptOutbox(
            UUID tenant,
            AlertRecoveryRow r,
            AlertRecoveryOutbox source,
            UUID actor,
            String reason,
            UUID corr,
            Instant now) {
        int n = mark(tenant, r, actor, reason, corr, now);
        if (n != 1) return n;
        int out =
                mapper.resetOutbox(now, tenant, source.outboxId(), source.version());
        if (out != 1) throw new IllegalStateException("Alert AlertRecoveryOutbox replay CAS lost");
        if (AlertEventContract.TICKET_CREATE_REQUESTED.contractRef().equals(source.contractRef())) {
            int link =
                    mapper.resetLink(now, tenant, source.incidentId(), source.linkVersion());
            if (link != 1) throw new IllegalStateException("IncidentTicketLink replay CAS lost");
        }
        return 1;
    }
    /**
     * 按恢复记录的方向、来源和版本领取人工重放；Inbox 同时保存持久化租约，Outbox 留待正常发布器发送。
     */
    private int mark(UUID tenant, AlertRecoveryRow r, UUID actor, String reason, UUID corr, Instant now) {
        return mapper.claimReplay("INBOX".equals(r.direction())
                        ? RecoveryReplayResumer.leaseUntil(now)
                        : null, now, actor, reason, corr, tenant, r.id(), r.direction(), r.source(), r.version());
    }
}
