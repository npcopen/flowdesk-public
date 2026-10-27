package com.flowdesk.alert.service.impl;
import com.flowdesk.alert.recovery.*;
import com.flowdesk.alert.service.AlertRecoveryExecutionService;
import com.flowdesk.alert.mapper.AlertRecoveryMapper;
import com.flowdesk.alert.model.*;

import com.flowdesk.alert.event.AlertEventContract;
import com.flowdesk.alert.event.TicketResultConsumer;
import com.flowdesk.alert.event.TicketResultTransportMessage;
import com.flowdesk.common.tenant.ActorRef;
import com.flowdesk.common.tenant.TenantContext;
import com.flowdesk.common.tenant.TenantContextScope;
import org.springframework.core.task.TaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 执行已被接纳的 Inbox 人工重放，不承担接纳裁决，也不另写一套建单关联逻辑。
 *
 * <p>{@link AlertRecoveryService} 或持久化租约扫描器提交 Work 后，本类从 Inbox 还原原始报文，
 * 交给 {@link TicketResultConsumer} 重新进行契约、业务和幂等校验。原消费者提交业务结果，本类再补齐人工尝试的完成审计。
 *
 * <p>执行期间不长期持有接纳事务；失败时用“人工尝试标识 + 租约”做条件更新，防止中断前的旧线程覆盖接管后的状态。
 */
public final class AlertRecoveryExecutionServiceImpl implements AlertRecoveryExecutionService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter MICROS =
            new DateTimeFormatterBuilder().appendInstant(6).toFormatter();
    private final TaskExecutor executor;
    private final AlertRecoveryMapper mapper;
    private final TicketResultConsumer consumer;
    private final TransactionTemplate transactions;

    /**
     * 注入任务执行器、恢复 Mapper、原工单结果消费者和事务管理器，分离异步执行与人工尝试结算事务。
     */
    public AlertRecoveryExecutionServiceImpl(
            TaskExecutor executor,
            AlertRecoveryMapper mapper,
            TicketResultConsumer consumer,
            PlatformTransactionManager transactionManager) {
        this.executor = executor;
        this.mapper = mapper;
        this.consumer = consumer;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** 队列拒绝任务也要结算为失败；若连结算都未能提交，持久化租约过期后仍可被扫描器接管。 */
    public void submit(AlertRecoveryService.Work work) {
        try {
            executor.execute(() -> execute(work));
        } catch (RuntimeException unavailable) {
            settleFailure(work, "RECOVERY_REPLAY_WORKER_UNAVAILABLE");
        }
    }

    /**
     * 恢复持久化的人工操作上下文并检查执行凭据，再走原消费者路径。
     * 消费者返回 ACK 不等于业务成功：明确拒绝也会 ACK，因此最终状态还必须回查 Inbox。
     */
    public void execute(AlertRecoveryService.Work work) {
        var context =
                TenantContext.ofPersistedTask(
                        work.tenant(), ActorRef.human(work.actor()), work.attempt());
        try (TenantContextScope ignored = TenantContextScope.open(context)) {
            var recovery = mapper.findRecovery(work.tenant(), work.recovery(), false).stream().findFirst().orElse(null);
            if (!ownsLease(recovery, work)) return;
            var source = mapper.findInbox(work.tenant(), work.source(), false).stream().findFirst().orElse(null);
            // 接管时可能发现上一执行者已经提交消费结果，只缺人工审计；此时直接尝试补齐结算。
            if (source == null || !"RECOVERY_REQUIRED".equals(source.status())) {
                settleCompletion(work);
                return;
            }
            var result = consumer.consume(restore(work.tenant(), source));
            if (result == TicketResultConsumer.Result.RETRY)
                settleFailure(work, "RECOVERY_REPLAY_NOT_TERMINAL");
            else settleCompletion(work);
        } catch (RuntimeException failed) {
            settleFailure(work, "RECOVERY_REPLAY_EXECUTION_FAILED");
        }
    }

    /**
     * 检查数据库是否仍保存本任务的执行凭据，而非仅比较当前时间是否超过截止时间。
     * 接管者会保留人工尝试标识并更新租约，旧线程的截止时间随之失配，不能再结算新租约。
     */
    private static boolean ownsLease(
            AlertRecoveryRow recovery, AlertRecoveryService.Work work) {
        return recovery != null
                && "REPLAYING".equals(recovery.state())
                && work.source().equals(recovery.source())
                && work.attempt().equals(recovery.replayCorrelation())
                && work.leaseUntil().equals(recovery.next());
    }

    /**
     * 用 Inbox 保存的原正文、消息 ID、业务意图、摘要和时间还原投递信封。
     * 重放复用原消息身份，才能沿用消费者的业务去重规则；不能生成新 ID 把人工重放伪装成新业务事件。
     */
    private TicketResultTransportMessage restore(
            UUID tenant, AlertRecoveryInbox source) {
        if (!AlertEventContract.TICKET_ESTABLISHED.contractRef().equals(source.contractRef()))
            throw new IllegalStateException("Inactive Alert Inbox replay contract");
        try {
            JsonNode payload = JSON.readTree(source.body());
            UUID aggregate = UUID.fromString(payload.get("incidentId").stringValue());
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("fd_envelope_version", "1");
            headers.put("fd_event_type", AlertEventContract.TICKET_ESTABLISHED.eventType());
            headers.put("fd_contract_ref", source.contractRef());
            headers.put("fd_contract_version", "1");
            headers.put("fd_producer", AlertEventContract.TICKET_ESTABLISHED.producer());
            headers.put("fd_tenant_id", tenant.toString());
            headers.put("fd_message_id", source.messageId().toString());
            headers.put("fd_business_intent_key", HexFormat.of().formatHex(source.intent()));
            headers.put("fd_aggregate_type", "INCIDENT");
            headers.put("fd_aggregate_id", aggregate.toString());
            headers.put("fd_correlation_id", source.correlation().toString());
            if (source.causation() != null)
                headers.put("fd_causation_id", source.causation().toString());
            headers.put("fd_occurred_at", MICROS.format(source.occurred()));
            headers.put("fd_body_sha256", HexFormat.of().formatHex(source.hash()));
            return new TicketResultTransportMessage(
                    AlertEventContract.TICKET_ESTABLISHED.topic(),
                    AlertEventContract.TICKET_ESTABLISHED.consumerGroup(),
                    source.messageId().toString(),
                    null,
                    source.body(),
                    headers);
        } catch (Exception invalid) {
            throw new IllegalStateException("Persisted Alert replay source is invalid", invalid);
        }
    }

    /**
     * 本事务依次锁定恢复记录和 Inbox，核实原消费者已提交 PROCESSED 或 REJECTED 后，再补齐人工完成审计。
     * 正常消费者会在其自身事务中结算恢复记录，因此这里也允许同一人工尝试已是 RESOLVED，仅缺审计的情况。
     */
    private void settleCompletion(AlertRecoveryService.Work work) {
        transactions.executeWithoutResult(
                tx -> {
                    var recovery =
                            mapper.findRecovery(work.tenant(), work.recovery(), true).stream().findFirst().orElse(null);
                    var source = mapper.findInbox(work.tenant(), work.source(), true).stream().findFirst().orElse(null);
                    if (recovery == null || !work.attempt().equals(recovery.replayCorrelation()))
                        return;
                    if (source == null
                            || !("PROCESSED".equals(source.status())
                                    || "REJECTED".equals(source.status()))) {
                        reopen(work, "RECOVERY_REPLAY_NOT_TERMINAL");
                        return;
                    }
                    // 正常消费会原子结算 Inbox 与 Recovery；接管任务也可能读到上一轮已完成投递留下的终态 Inbox。
                    if (ownsLease(recovery, work))
                        mapper.resolveTerminal(now(), work.tenant(), work.recovery(), work.attempt(), work.leaseUntil());
                    else if (!"RESOLVED".equals(recovery.state())) return;
                    // 明确拒绝也是消费终态，但审计结果保留 REJECTED，不能把 ACK 统一记成成功。
                    String outcome = "PROCESSED".equals(source.status()) ? "SUCCEEDED" : "REJECTED";
                    UUID audit =
                            AlertRecoveryIdempotency.outcomeAuditId(
                                    work.tenant(), work.attempt(), outcome);
                    if (mapper.findAudit(work.tenant(), audit).stream().findFirst().isEmpty())
                        mapper.insertAudit(
                                work.tenant(),
                                audit,
                                "ALERT_RECOVERY_REPLAY_COMPLETED",
                                work.recovery(),
                                outcome,
                                work.actor(),
                                work.reason(),
                                "REJECTED".equals(outcome) ? "REJECTED" : null,
                                work.attempt(),
                                work.sourceCorrelation(),
                                now());
                });
    }

    /** 失败结算使用独立于原消费者执行的事务，确保业务回滚后仍能记录人工尝试失败。 */
    private void settleFailure(AlertRecoveryService.Work work, String code) {
        transactions.executeWithoutResult(tx -> reopen(work, code));
    }

    /**
     * 只有仍持有该租约的任务才能把恢复记录重新开放并写失败审计。
     * OPEN 表示等待操作员再次裁决，不由本工作线程自动创建新的人工尝试；更新未命中时也不写过期任务的失败审计。
     */
    private void reopen(AlertRecoveryService.Work work, String code) {
        Instant at = now();
        if (mapper.reopen(code, at, work.tenant(), work.recovery(), work.attempt(), work.leaseUntil())
                == 1) {
            UUID audit =
                    AlertRecoveryIdempotency.outcomeAuditId(
                            work.tenant(), work.attempt(), "FAILED");
            if (mapper.findAudit(work.tenant(), audit).stream().findFirst().isEmpty())
                mapper.insertAudit(
                        work.tenant(),
                        audit,
                        "ALERT_RECOVERY_REPLAY_COMPLETED",
                        work.recovery(),
                        "FAILED",
                        work.actor(),
                        work.reason(),
                        code,
                        work.attempt(),
                        work.sourceCorrelation(),
                        at);
        }
    }

    /**
     * 读取当前时间并截断到微秒，使写入与数据库时间精度一致。
     */
    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
