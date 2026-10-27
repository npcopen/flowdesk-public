package com.flowdesk.ticket.notification;

import com.flowdesk.common.notification.NotificationEventContract;
import com.flowdesk.common.tenant.ActorRef;
import com.flowdesk.common.tenant.TenantContext;
import com.flowdesk.common.tenant.TenantContextHolder;
import com.flowdesk.common.tenant.TenantContextScope;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** 以有界批次、至少一次语义发布工单通知；领取与结算使用短事务，Broker 调用位于事务外。 */
public final class TicketNotificationOutboxPublisher {
    static final String RESULT_UNKNOWN="BROKER_RESULT_UNKNOWN"; static final String INVALID_CONTRACT="OUTBOX_CONTRACT_INVALID";
    private final TicketNotificationDispatchService repository; private final TicketNotificationOutboxTransport transport;
    private final TransactionTemplate transactions; private final Clock clock;
    /**
     * 构造工单通知组件并保存调用依赖；支持注入时钟以控制时间边界。
     */
    public TicketNotificationOutboxPublisher(TicketNotificationDispatchService r,TicketNotificationOutboxTransport t,PlatformTransactionManager m,Clock c){repository=Objects.requireNonNull(r);transport=Objects.requireNonNull(t);transactions=new TransactionTemplate(Objects.requireNonNull(m));clock=Objects.requireNonNull(c);}
    /**
     * 构造工单通知组件并保存调用依赖。
     */
    public TicketNotificationOutboxPublisher(TicketNotificationDispatchService r,TicketNotificationOutboxTransport t,PlatformTransactionManager m){this(r,t,m,Clock.systemUTC());}
    /**
     * 扫描有界待发批次，依次领取、发送并按确认结果结算重试或恢复。
     */
    public PublishResult publishOnce(){
        if(TransactionSynchronizationManager.isActualTransactionActive()||TenantContextHolder.currentOrNull()!=null)throw new IllegalStateException("Ticket Notification publisher must start outside context/transaction");
        Instant now=instant(); List<com.flowdesk.ticket.model.notification.Candidate> candidates=repository.findCandidates(now,now.minus(NotificationEventContract.OUTBOX_CLAIM_LEASE),NotificationEventContract.OUTBOX_MAX_CLAIMS); Mutable result=new Mutable(candidates.size());
        for(var candidate:candidates){TenantContext context=TenantContext.ofPersistedTask(candidate.tenantId(),ActorRef.service(NotificationEventContract.TICKET.producerService()),candidate.correlationId());try(TenantContextScope ignored=TenantContextScope.open(context)){if(candidate.attemptCount()>=NotificationEventContract.OUTBOX_MAX_ATTEMPTS)recover(candidate,result);else send(candidate,result);}}
        return result.freeze();
    }
    /** 数据库事务中领取消息，事务外发送原始字节，再依据 Broker 结果有条件结算或安排退避。 */
    private void send(com.flowdesk.ticket.model.notification.Candidate candidate,Mutable result){
        Instant claimedAt=instant();String token=UUID.randomUUID().toString();Optional<com.flowdesk.ticket.model.notification.Claimed> claim=transactions.execute(tx->repository.claimForSend(candidate,token,claimedAt,claimedAt.minus(NotificationEventContract.OUTBOX_CLAIM_LEASE)));if(claim==null||claim.isEmpty()){result.lostClaim++;return;}result.claimedForSend++;var row=claim.get();TicketNotificationOutboxSendResult outcome;
        try{outcome=transport.send(TicketNotificationOutboxEnvelopeFactory.create(row));if(outcome==null)outcome=TicketNotificationOutboxSendResult.notConfirmed(RESULT_UNKNOWN,null);}
        catch(TicketNotificationOutboxEnvelopeFactory.InvalidContract invalid){Boolean done=transactions.execute(tx->repository.rejectContract(row,instant(),instant().minus(NotificationEventContract.OUTBOX_CLAIM_LEASE),INVALID_CONTRACT,"Persisted Notification contract failed validation",TicketNotificationIdentity.recoveryId(row)));if(Boolean.TRUE.equals(done))result.recovered++;else result.lostCompletion++;return;}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();outcome=TicketNotificationOutboxSendResult.notConfirmed(RESULT_UNKNOWN,null);}
        catch(Exception unknown){outcome=TicketNotificationOutboxSendResult.notConfirmed(RESULT_UNKNOWN,null);}
        Instant completed=instant();Instant cutoff=completed.minus(NotificationEventContract.OUTBOX_CLAIM_LEASE);
        if(outcome.status()==TicketNotificationOutboxSendResult.Status.SEND_OK){Boolean done=transactions.execute(tx->repository.markPublished(row,completed,cutoff));if(Boolean.TRUE.equals(done))result.published++;else result.lostCompletion++;}
        else if(row.attemptCount()<NotificationEventContract.OUTBOX_MAX_ATTEMPTS){Duration backoff=NotificationEventContract.FAILURE_BACKOFFS.get(row.attemptCount()-1);TicketNotificationOutboxSendResult failed=outcome;Boolean done=transactions.execute(tx->repository.markPending(row,completed,cutoff,completed.plus(backoff),failed.errorCode()));if(Boolean.TRUE.equals(done))result.rescheduled++;else result.lostCompletion++;}
        else{TicketNotificationOutboxSendResult failed=outcome;Boolean done=transactions.execute(tx->repository.moveToRecovery(row,completed,cutoff,failed.errorCode(),clean(failed.safeErrorSummary()),TicketNotificationIdentity.recoveryId(row)));if(Boolean.TRUE.equals(done))result.recovered++;else result.lostCompletion++;}
    }
    /**
     * 将耗尽发送机会的记录转入恢复，保留恢复及审计事实。
     */
    private void recover(com.flowdesk.ticket.model.notification.Candidate candidate,Mutable result){Instant now=instant();String token=UUID.randomUUID().toString();Optional<com.flowdesk.ticket.model.notification.Claimed> claim=transactions.execute(tx->repository.claimForRecovery(candidate,token,now,now.minus(NotificationEventContract.OUTBOX_CLAIM_LEASE)));if(claim==null||claim.isEmpty()){result.lostClaim++;return;}result.claimedForRecovery++;Boolean done=transactions.execute(tx->repository.moveToRecovery(claim.get(),instant(),instant().minus(NotificationEventContract.OUTBOX_CLAIM_LEASE),RESULT_UNKNOWN,null,TicketNotificationIdentity.recoveryId(claim.get())));if(Boolean.TRUE.equals(done))result.recovered++;else result.lostCompletion++;}
    /**
     * 取得当前规范精度时间。
     */
    private Instant instant(){return clock.instant().truncatedTo(ChronoUnit.MICROS);}
    /**
     * 清理控制字符与多余空白，并限制错误摘要长度。
     */
    private static String clean(String s){if(s==null)return null;String v=s.replaceAll("[\\p{Cntrl}\\s]+"," ").trim();return v.isEmpty()?null:v.substring(0,Math.min(1000,v.length()));}
    /**
     * 一次通知发布统计，包含扫描、领取、发布、退避与恢复数量。
     */
    public record PublishResult(int scanned,int claimedForSend,int claimedForRecovery,int published,int rescheduled,int recovered,int lostClaim,int lostCompletion){}
    /**
     * 本轮处理的内部可变计数器，结束后转换为不可变统计结果。
     */
    private static final class Mutable{final int scanned;int claimedForSend,claimedForRecovery,published,rescheduled,recovered,lostClaim,lostCompletion;
        /**
         * 初始化单轮统计累加器并保存扫描数量，其余计数从零开始。
         */
        Mutable(int s){scanned=s;}
        /**
         * 把本轮可变计数转为不可变统计，供调用方观察处理进度。
         */
        PublishResult freeze(){return new PublishResult(scanned,claimedForSend,claimedForRecovery,published,rescheduled,recovered,lostClaim,lostCompletion);}}
}
