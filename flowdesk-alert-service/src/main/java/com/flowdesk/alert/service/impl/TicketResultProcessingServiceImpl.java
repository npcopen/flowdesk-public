package com.flowdesk.alert.service.impl;
import com.flowdesk.alert.service.TicketResultProcessingService;
import com.flowdesk.alert.event.AcceptedTicketResult;
import com.flowdesk.alert.event.AlertEventContract;
import com.flowdesk.alert.model.LinkRow;
import com.flowdesk.alert.model.*;

import com.flowdesk.common.tenant.TenantContextHolder;
import com.flowdesk.alert.mapper.TicketResultMapper;
import com.flowdesk.alert.mapper.IncidentMapper;
import com.flowdesk.alert.mapper.AlertMessageMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.temporal.ChronoUnit;

import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

/**
 * 保存 Ticket 关联结果的 TicketResultInbox 消费事实，并维护这些事实对应的故障关联、恢复记录与审计。
 *
 * <p>本类通过能力入口持有本地事务，不控制 Broker ACK，
 * 使“关联已建立”和“消息已处理”能够一起提交或一起回滚。去重同时使用生产者消息 ID 与契约业务意图，
 * 防止 Broker 重投或生产者重新发布同一业务结果造成重复副作用。
 */
public final class TicketResultProcessingServiceImpl implements TicketResultProcessingService {
    private final TicketResultMapper mapper;

    private final IncidentMapper incidents;
    private final AlertMessageMapper messages;
    private final TransactionTemplate transactions;
    private final Clock clock;

    /**
     * 组装当前组件依赖并委托完整构造方法；使用 UTC 系统时钟等默认运行配置，显式依赖版本可用于确定性测试。
     */
    public TicketResultProcessingServiceImpl(TicketResultMapper mapper, IncidentMapper incidents,
            AlertMessageMapper messages, PlatformTransactionManager tx) {
        this(mapper, incidents, messages, tx, Clock.systemUTC());
    }

    /**
     * 注入工单结果、故障与关联 Mapper，以及事务管理器和可替换时钟，使消费、恢复及审计共用一致事实。
     */
    public TicketResultProcessingServiceImpl(TicketResultMapper mapper, IncidentMapper incidents,
            AlertMessageMapper messages, PlatformTransactionManager tx, Clock clock) {
        this.mapper = mapper;
        this.incidents = incidents;
        this.messages = messages;
        this.transactions = new TransactionTemplate(tx);
        this.clock = clock;
    }

    /** 关联、Inbox、Recovery 和审计在一个本地事务内裁决，返回时提交已完成。 */
    @Override
    public void process(AcceptedTicketResult message) {
        transactions.executeWithoutResult(tx -> processInside(message));
    }

    /** 死信首次接纳原子保存原消息、人工恢复记录和审计；既有裁决不会重新开放。 */
    @Override
    public void acceptDeadLetter(AcceptedTicketResult message) {
        transactions.executeWithoutResult(tx -> {
            if (duplicate(message, true).kind() != TicketResultDuplicateKind.NONE) return;
            Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            insertRecovery(message, "BROKER_RETRIES_EXHAUSTED", now);
            audit(message, "INCIDENT_TICKET_RESULT_DLQ_ACCEPTED", "FAILED",
                    "BROKER_RETRIES_EXHAUSTED", now);
        });
    }

    /**
     * 先核对消息与业务意图去重结果，再校验故障、建单意图及权威工单编号，原子保存成功或确定性拒绝。
     */
    private void processInside(AcceptedTicketResult m) {
        var duplicate = duplicate(m, true);
        if (duplicate.kind() == TicketResultDuplicateKind.TERMINAL) return;
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (duplicate.kind() == TicketResultDuplicateKind.CONFLICT) {
            audit(m, "INCIDENT_TICKET_RESULT_CONFLICT", "REJECTED", duplicate.code(), now);
            return;
        }
        var incident = incidents.findIncident(m.tenantId(), m.incidentId(), true).orElse(null);
        var link = messages.findLink(m.tenantId(), m.incidentId(), true).orElse(null);
        String error = null;
        if (incident == null || link == null) error = "INCIDENT_LINK_NOT_FOUND";
        else if (!MessageDigest.isEqual(link.createIntent(), m.createTicketIntentKey()))
            error = "INCIDENT_CREATE_INTENT_CONFLICT";
        else if ("ESTABLISHED".equals(link.status()) && !m.ticketId().equals(link.ticketId()))
            error = "INCIDENT_TICKET_AUTHORITY_CONFLICT";
        if (error != null) {
            // 业务事实不匹配属于确定性拒绝：持久化拒绝结果后确认消息，避免无限重试同一冲突。
            if (duplicate.kind() == TicketResultDuplicateKind.RECOVERABLE)
                rejectRecovered(m, duplicate.inbox(), error, now);
            else insertInbox(m, "REJECTED", null, 1, error, now);
            audit(m, "INCIDENT_TICKET_RESULT_REJECTED", "REJECTED", error, now);
            return;
        }
        establish(m, link, now);
        // 从死信恢复的消息更新原 Inbox；首次投递创建 Inbox，均与关联和审计一起提交。
        if (duplicate.kind() == TicketResultDuplicateKind.RECOVERABLE)
            finishRecovered(m, duplicate.inbox(), m.incidentId(), now);
        else insertInbox(m, "PROCESSED", m.incidentId(), 1, null, now);
        audit(m, "INCIDENT_TICKET_LINK_ESTABLISHED", "SUCCEEDED", null, now);
        return;
    }

    /**
     * 先按消息 ID 查询，再按业务意图查询；查询始终限定当前租户和固定生产者。
     * 消费与恢复传入 lock=true，使已有 TicketResultInbox 的分类及后续状态更新处于同一行锁保护下。
     * 身份命中还必须比较业务意图、原始正文和摘要，不能把“同一个键”直接当作“同一份结果”。
     */
    private TicketResultDuplicate duplicate(AcceptedTicketResult m, boolean lock) {
        requireTenant(m.tenantId());
        Optional<TicketResultInbox> byMessage = mapper.findByMessage(m.tenantId(), AlertEventContract.TICKET_ESTABLISHED.producer(), m.messageId(), lock).stream().findFirst();
        if (byMessage.isPresent())
            return classify(m, byMessage.get(), "MESSAGE_ID_CONTENT_CONFLICT");
        Optional<TicketResultInbox> byIntent = mapper.findByIntent(m.tenantId(), AlertEventContract.TICKET_ESTABLISHED.producer(), AlertEventContract.TICKET_ESTABLISHED.contractRef(), m.businessIntentKey(), lock).stream().findFirst();
        return byIntent.map(i -> classify(m, i, "BUSINESS_INTENT_CONTENT_CONFLICT"))
                .orElseGet(TicketResultDuplicate::none);
    }



    /** 内容一致时，待恢复记录可继续处理，其他已存在状态复用终态；内容不一致始终优先判为冲突。 */
    private static TicketResultDuplicate classify(AcceptedTicketResult m, TicketResultInbox i, String code) {
        boolean same =
                Arrays.equals(m.businessIntentKey(), i.intent())
                        && Arrays.equals(m.body(), i.body())
                        && Arrays.equals(m.bodyHash(), i.hash());
        if (!same) return new TicketResultDuplicate(TicketResultDuplicateKind.CONFLICT, i, code);
        return new TicketResultDuplicate(
                "RECOVERY_REQUIRED".equals(i.status()) ? TicketResultDuplicateKind.RECOVERABLE : TicketResultDuplicateKind.TERMINAL, i, null);
    }

    /**
     * 在消费者已核对建单意图与 ticketId 后，以版本条件建立故障到工单的关联。
     * Ticket 的权威结果证明建单请求已被处理，即使此前发送确认丢失，也可一并结束对应的 Outbox 恢复记录。
     * 已建立且一致的关联不重复改写；版本竞争失败则抛错，让调用方回滚本次全部消费写入。
     */
    private void establish(AcceptedTicketResult m, LinkRow link, Instant now) {
        requireTenant(m.tenantId());
        if (!"ESTABLISHED".equals(link.status())) {
            int n =
                    mapper.establishLink(m.ticketId(), m.establishedAt(), now, m.tenantId(), m.incidentId(), link.lockVersion());
            if (n != 1) throw new IllegalStateException("IncidentTicketLink CAS lost");
        }
        mapper.resolveOutboxRecoveries(now, m.tenantId(), m.incidentId(), AlertEventContract.TICKET_CREATE_REQUESTED.contractRef());
    }

    /** 记录首次接纳结果；只有待恢复状态不写 processed_at，终态处理时间与业务事务一同落库。 */
    private void insertInbox(
            AcceptedTicketResult m,
            String status,
            UUID result,
            int attempts,
            String error,
            Instant now) {
        requireTenant(m.tenantId());
        mapper.insertInbox(m.tenantId(), UUID.randomUUID(), AlertEventContract.TICKET_ESTABLISHED.producer(), m.messageId(), m.businessIntentKey(), AlertEventContract.TICKET_ESTABLISHED.contractRef(), m.bodyHash(), m.body(), m.correlationId(), m.causationId(), m.envelopeOccurredAt(), status, "PROCESSED".equals(status) ? "INCIDENT" : null, result, attempts, error, now, "RECOVERY_REQUIRED".equals(status) ? null : now);
    }

    /** 将原待恢复 TicketResultInbox 改为成功并结束其恢复记录；版本条件更新失败时由调用方回滚，不另建消费记录。 */
    private void finishRecovered(AcceptedTicketResult m, TicketResultInbox i, UUID result, Instant now) {
        requireTenant(m.tenantId());
        int n =
                mapper.completeRecoveredInbox(result, now, m.tenantId(), i.inboxId(), i.version());
        if (n != 1) throw new IllegalStateException("Ticket result TicketResultInbox CAS lost");
        resolveInbox(m.tenantId(), i.inboxId(), now);
    }

    /** 重放发现确定性业务冲突时，把原 TicketResultInbox 结算为拒绝，同时结束恢复记录，保留失败原因供排查。 */
    private void rejectRecovered(AcceptedTicketResult m, TicketResultInbox i, String error, Instant now) {
        requireTenant(m.tenantId());
        int n =
                mapper.rejectRecoveredInbox(error, now, m.tenantId(), i.inboxId(), i.version());
        if (n != 1) throw new IllegalStateException("Ticket result TicketResultInbox CAS lost");
        resolveInbox(m.tenantId(), i.inboxId(), now);
    }

    /** 死信首次落库时同时建立待恢复 TicketResultInbox 和 OPEN 恢复记录，保存原始消息以便后续重新校验和重放。 */
    private void insertRecovery(AcceptedTicketResult m, String code, Instant now) {
        requireTenant(m.tenantId());
        UUID inbox = UUID.randomUUID();
        insertInboxWithId(m, inbox, now, code);
        mapper.insertRecovery(m.tenantId(), UUID.randomUUID(), inbox, m.businessIntentKey(), code, now);
    }

    /**
     * 使用调用方预先生成的 Inbox 标识保存死信原文、摘要和追踪字段，供恢复记录引用同一消费事实。
     */
    private void insertInboxWithId(AcceptedTicketResult m, UUID inbox, Instant now, String error) {
        mapper.insertRecoveryInbox(m.tenantId(), inbox, AlertEventContract.TICKET_ESTABLISHED.producer(), m.messageId(), m.businessIntentKey(), AlertEventContract.TICKET_ESTABLISHED.contractRef(), m.bodyHash(), m.body(), m.correlationId(), m.causationId(), m.envelopeOccurredAt(), error, now);
    }

    /** 与业务写入共用事务，以原消息 ID 串联消费因果，记录成功、拒绝、冲突或死信接纳原因。 */
    private void audit(AcceptedTicketResult m, String action, String outcome, String error, Instant now) {
        mapper.insertAudit(m.tenantId(), UUID.randomUUID(), action, m.incidentId(), outcome, error, m.correlationId(), m.messageId(), now);
    }

    /**
     * 将指定租户下源 Inbox 对应的恢复记录结算为已解决，随原消费事务提交。
     */
    private void resolveInbox(UUID tenant, UUID inbox, Instant now) {
        mapper.resolveInboxRecovery(now, tenant, inbox);
    }

    /**
     * 核对目标租户与线程可信上下文一致，阻止内部调用跨越租户边界。
     */
    private static void requireTenant(UUID t) {
        TenantContextHolder.current().requireTenant(t);
    }









}
