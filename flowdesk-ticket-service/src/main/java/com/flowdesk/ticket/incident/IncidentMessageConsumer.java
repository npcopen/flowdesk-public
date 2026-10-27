package com.flowdesk.ticket.incident;

import com.flowdesk.common.tenant.TenantContext;
import com.flowdesk.common.tenant.TenantContextScope;
import com.flowdesk.ticket.service.IdempotencyCodec;
import com.flowdesk.ticket.sla.SlaIndexProjection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Alert 到 Ticket 的 M7 消费入口，将工单、Incident 映射、SLA、审计和结果消息纳入同一本地事务。 */
public final class IncidentMessageConsumer {
    private static final Logger LOG=LoggerFactory.getLogger(IncidentMessageConsumer.class);
    private static final DateTimeFormatter MICROS=new DateTimeFormatterBuilder().appendInstant(6).toFormatter(Locale.ROOT);
    private static final DateTimeFormatter TICKET_DATE=DateTimeFormatter.ofPattern("yyyyMMdd",Locale.ROOT);
    private final IncidentEnvelopeGuard guard;private final IncidentInboxService repository;
    private final TransactionTemplate transactions;private final ObjectMapper json;private final Clock clock;
    private final SlaIndexProjection slaIndexProjection;
    /**
     * 构造告警建单组件并保存调用依赖。
     */
    IncidentMessageConsumer(IncidentEnvelopeGuard guard,IncidentInboxService repository,
                            PlatformTransactionManager tx,ObjectMapper json){this(guard,repository,tx,json,Clock.systemUTC(),null);}
    /**
     * 构造告警建单组件并保存调用依赖；支持注入时钟以控制时间边界。
     */
    IncidentMessageConsumer(IncidentEnvelopeGuard guard,IncidentInboxService repository,
                            PlatformTransactionManager tx,ObjectMapper json,Clock clock){this(guard,repository,tx,json,clock,null);}
    /**
     * 构造告警建单组件并保存调用依赖；支持注入时钟以控制时间边界。
     */
    IncidentMessageConsumer(IncidentEnvelopeGuard guard,IncidentInboxService repository,
                            PlatformTransactionManager tx,ObjectMapper json,Clock clock,
                            SlaIndexProjection slaIndexProjection){this.guard=guard;this.repository=repository;
        this.transactions=new TransactionTemplate(tx);this.json=json;this.clock=clock;this.slaIndexProjection=slaIndexProjection;}
    /** 信封校验成功后才建立租户上下文；非法传输直接 ACK，依赖暂时不可用则交回 Broker 重试。 */
    public IncidentConsumeResult consume(IncidentTransportMessage delivery){final AcceptedIncidentMessage message;
        try{message=guard.validateMain(delivery);}catch(IncidentContractViolation poison){
            LOG.warn("Incident delivery ACKed by transport guard; code={}",poison.code());return IncidentConsumeResult.ack(poison.code());}
        TenantContext context=TenantContext.ofValidatedBrokerMessage(message.tenantId(),IncidentMessageContract.ALERT_PRODUCER,message.correlationId());
        try(TenantContextScope ignored=TenantContextScope.open(context)){
            try{return Objects.requireNonNull(transactions.execute(tx->process(message)));}
            catch(DataAccessException|TransactionException|DependencyUnavailable|IllegalStateException transientFailure){
                LOG.warn("Incident delivery returned to Broker; dependency=TICKET_DATABASE");return IncidentConsumeResult.retry("TICKET_INCIDENT_DEPENDENCY_UNAVAILABLE");}}
    }
    /**
     * 在租户事务中处理验证消息，按 Inbox 判重后写入业务、审计与发布事实。
     */
    private IncidentConsumeResult process(AcceptedIncidentMessage m){var duplicate=repository.duplicate(m,true);
        if(duplicate.kind()==com.flowdesk.ticket.model.incident.DuplicateKind.TERMINAL)return IncidentConsumeResult.ack("INCIDENT_MESSAGE_DUPLICATE");
        Instant now=clock.instant().truncatedTo(ChronoUnit.MICROS);
        if(duplicate.kind()==com.flowdesk.ticket.model.incident.DuplicateKind.CONFLICT){repository.insertAudit(m,m.incidentId(),
                "INCIDENT_MESSAGE_CONFLICT","REJECTED","Message identity was reused.",duplicate.conflictCode(),now);
            return IncidentConsumeResult.ack(duplicate.conflictCode());}
        if(m.binding().kind()==IncidentMessageContract.Kind.CREATE_REQUESTED)return create(m,duplicate,now);
        return recover(m,duplicate,now);}
    /** 先复用已建立的 Incident 映射，否则原子创建工单、SLA 时钟及建单成功 Outbox。 */
    private IncidentConsumeResult create(AcceptedIncidentMessage m,com.flowdesk.ticket.model.incident.Duplicate duplicate,Instant now){
        com.flowdesk.ticket.model.incident.Mapping existing=repository.mapping(m.tenantId(),m.incidentId());
        if(existing!=null){if(!"INCIDENT".equals(existing.originType())||!MessageDigest.isEqual(existing.creationIntent(),m.createTicketIntentKey())
                    ||!MessageDigest.isEqual(existing.ticketIntent(),m.createTicketIntentKey()))return reject(m,duplicate,"INCIDENT_MAPPING_CONFLICT",now);
            scheduleSlaIndexProjection(m.tenantId(),IncidentMessageIdentity.slaClockId(
                    m.tenantId(),existing.ticketId(),"FIRST_RESPONSE"));
            scheduleSlaIndexProjection(m.tenantId(),IncidentMessageIdentity.slaClockId(
                    m.tenantId(),existing.ticketId(),"RESOLUTION"));
            completeInbox(m,duplicate,existing.ticketId(),now);return IncidentConsumeResult.ack("INCIDENT_TICKET_ALREADY_ESTABLISHED");}
        com.flowdesk.ticket.model.incident.Policy policy=repository.incidentPolicy(m.tenantId());
        if(policy==null||policy.firstResponseSeconds()<=0||policy.resolutionSeconds()<=0
                ||invalidReminder(policy.firstReminderSeconds(),policy.firstResponseSeconds())
                ||invalidReminder(policy.resolutionReminderSeconds(),policy.resolutionSeconds()))throw new DependencyUnavailable();
        UUID ticket=stableUuid("FD-INCIDENT-TICKET-V1",m.tenantId(),m.incidentId(),m.createTicketIntentKey());
        LocalDate date=LocalDate.ofInstant(now,ZoneOffset.UTC);
        String ticketNo=String.format(Locale.ROOT,"FD-%s-%04d",TICKET_DATE.format(date),repository.nextSequence(m.tenantId(),date,now));
        repository.insertTicket(m,ticket,ticketNo,now);repository.insertMapping(m,ticket,now);
        repository.insertTimeline(m,ticket,m.createTicketIntentKey(),"INCIDENT_CREATED","监控 Incident 已创建工单。",now);
        UUID firstResponseClock=repository.insertClock(m,ticket,policy,"FIRST_RESPONSE",policy.firstResponseSeconds(),policy.firstReminderSeconds(),now);
        UUID resolutionClock=repository.insertClock(m,ticket,policy,"RESOLUTION",policy.resolutionSeconds(),policy.resolutionReminderSeconds(),now);
        scheduleSlaIndexProjection(m.tenantId(),firstResponseClock);
        scheduleSlaIndexProjection(m.tenantId(),resolutionClock);
        repository.insertAudit(m,ticket,"INCIDENT_TICKET_CREATED","SUCCEEDED",null,null,now);
        insertEstablishedOutbox(m,ticket,now);completeInbox(m,duplicate,ticket,now);
        return IncidentConsumeResult.ack("INCIDENT_TICKET_CREATED");}
    /** 监控恢复只追加时间线，不自动修改工单主状态；映射尚未建立时返回重试以容忍乱序。 */
    private IncidentConsumeResult recover(AcceptedIncidentMessage m,com.flowdesk.ticket.model.incident.Duplicate duplicate,Instant now){
        com.flowdesk.ticket.model.incident.Mapping mapping=repository.mapping(m.tenantId(),m.incidentId());
        if(mapping==null)return IncidentConsumeResult.retry("INCIDENT_MAPPING_NOT_ESTABLISHED");
        if(!"INCIDENT".equals(mapping.originType())||!MessageDigest.isEqual(mapping.creationIntent(),m.createTicketIntentKey())
                ||!MessageDigest.isEqual(mapping.ticketIntent(),m.createTicketIntentKey()))return reject(m,duplicate,"INCIDENT_MAPPING_CONFLICT",now);
        repository.insertTimeline(m,mapping.ticketId(),m.businessIntentKey(),"INCIDENT_RECOVERED",
                "监控 Incident 已恢复；工单主状态保持不变。",m.recoveredAt());
        repository.insertAudit(m,mapping.ticketId(),"INCIDENT_RECOVERY_TIMELINE_APPENDED","SUCCEEDED",null,null,now);
        completeInbox(m,duplicate,mapping.ticketId(),now);return IncidentConsumeResult.ack("INCIDENT_RECOVERY_APPENDED");}
    /**
     * 把不可处理消息收敛为拒绝结果，保存契约允许的拒绝原因。
     */
    private IncidentConsumeResult reject(AcceptedIncidentMessage m,com.flowdesk.ticket.model.incident.Duplicate duplicate,String code,Instant now){
        if(duplicate.kind()==com.flowdesk.ticket.model.incident.DuplicateKind.RECOVERABLE)repository.rejectRecoveredInbox(m,duplicate.inbox(),code,now);
        else repository.insertRejectedInbox(m,inboxId(m),code,now);
        repository.insertAudit(m,m.incidentId(),"INCIDENT_MESSAGE_REJECTED","REJECTED","Incident references do not match Ticket facts.",code,now);
        return IncidentConsumeResult.ack(code);}
    /**
     * 完成 Inbox 并关联最终结果，使正常投递与恢复沿同一事实收敛。
     */
    private void completeInbox(AcceptedIncidentMessage m,com.flowdesk.ticket.model.incident.Duplicate duplicate,UUID ticket,Instant now){
        if(duplicate.kind()==com.flowdesk.ticket.model.incident.DuplicateKind.RECOVERABLE)repository.completeRecoveredInbox(m,duplicate.inbox(),ticket,now);
        else repository.insertProcessedInbox(m,inboxId(m),ticket,now);}
    /**
     * 登记提交后的 SLA 索引投影，避免回滚事务泄露未提交候选。
     */
    private void scheduleSlaIndexProjection(UUID tenantId,UUID clockId){
        if(slaIndexProjection!=null)slaIndexProjection.schedule(tenantId,clockId);
    }
    /**
     * 为告警建单结果写入发布意图，与业务事实在同一事务提交。
     */
    private void insertEstablishedOutbox(AcceptedIncidentMessage m,UUID ticket,Instant now){byte[] intent=IdempotencyCodec.derivedKey(
            IncidentMessageContract.ESTABLISHED_INTENT_NAMESPACE,m.tenantId(),m.incidentId());LinkedHashMap<String,Object> body=new LinkedHashMap<>();
        body.put("schemaVersion","1");body.put("incidentId",m.incidentId().toString());body.put("createTicketIntentKey",HexFormat.of().formatHex(m.createTicketIntentKey()));
        body.put("ticketId",ticket.toString());body.put("establishedAt",MICROS.format(now));byte[] bytes;
        try{bytes=json.writeValueAsBytes(body);}catch(Exception impossible){throw new IllegalStateException("Unable to encode established result",impossible);}
        UUID message=stableUuid("FD-INCIDENT-ESTABLISHED-MESSAGE-V1",m.tenantId(),m.incidentId(),intent);
        UUID outbox=stableUuid("FD-INCIDENT-ESTABLISHED-OUTBOX-V1",m.tenantId(),m.incidentId(),intent);
        byte[] publication=IdempotencyCodec.publicationKey("FD-INCIDENT-ESTABLISHED-PUBLICATION-V1",m.tenantId(),message);
        repository.insertOutboxEstablished(m,ticket,outbox,message,publication,intent,bytes,sha(bytes),now);}
    /**
     * 从消息稳定身份派生 Inbox 标识，使重复投递命中同一接收记录。
     */
    private static UUID inboxId(AcceptedIncidentMessage m){return stableUuid("FD-INCIDENT-INBOX-V1",m.tenantId(),m.messageId());}
    /**
     * 从规范业务键派生稳定 UUID，使重试与恢复复用同一业务身份。
     */
    private static UUID stableUuid(String namespace,Object... fields){return IdempotencyCodec.uuidFromDigest(IdempotencyCodec.derivedKey(namespace,fields));}
    /**
     * 判断提醒提前量与时长关系是否无法形成合法提醒时间。
     */
    private static boolean invalidReminder(Long r,long d){return r!=null&&(r<0||r>d);}
    /**
     * 计算输入内容 SHA-256 摘要，供原文完整性和幂等内容核验。
     */
    private static byte[] sha(byte[] b){
        try{return MessageDigest.getInstance("SHA-256").digest(b);}catch(Exception impossible){throw new IllegalStateException(impossible);}}
    /**
     * 消费依赖暂不可用的内部信号，供上层沿可重试路径处理。
     */
    private static final class DependencyUnavailable extends RuntimeException{}
}
