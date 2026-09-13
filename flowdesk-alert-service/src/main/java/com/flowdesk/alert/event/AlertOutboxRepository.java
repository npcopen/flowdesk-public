package com.flowdesk.alert.event;

import com.flowdesk.alert.repository.AlertRepository;
import com.flowdesk.common.tenant.TenantContextHolder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Narrow Alert Outbox publisher persistence for the two Alert-owned M7 contracts. */
final class AlertOutboxRepository {
    private final JdbcTemplate jdbc;
    AlertOutboxRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    List<Candidate> candidates(Instant now,Instant cutoff){return jdbc.query("""
            SELECT tenant_id,outbox_id,status,attempt_count,locked_by,correlation_id,lock_version
            FROM outbox_event
            WHERE contract_ref IN (?,?) AND contract_version=1 AND aggregate_type='INCIDENT'
              AND attempt_count<=? AND ((status='PENDING' AND next_attempt_at<=?)
                OR (status='PUBLISHING' AND locked_at<=?))
            ORDER BY CASE WHEN status='PENDING' THEN next_attempt_at ELSE locked_at END,
                     tenant_id,outbox_id LIMIT 50
            """,CANDIDATE,AlertEventContract.TICKET_CREATE_REQUESTED.contractRef(),
            AlertEventContract.INCIDENT_RECOVERED.contractRef(),AlertEventContract.MAX_ATTEMPTS,
            Timestamp.from(now),Timestamp.from(cutoff));}
    Optional<Claimed> claim(Candidate c,String token,Instant now,Instant cutoff){requireTx();requireTenant(c.tenantId());int changed;
        if("PENDING".equals(c.status())) changed=jdbc.update("""
                UPDATE outbox_event SET status='PUBLISHING',attempt_count=attempt_count+1,
                  locked_by=?,locked_at=?,lock_version=lock_version+1,updated_at=?,updated_by_type='SERVICE'
                WHERE tenant_id=? AND outbox_id=? AND status='PENDING' AND attempt_count=?
                  AND attempt_count<? AND lock_version=? AND next_attempt_at<=?
                """,token,Timestamp.from(now),Timestamp.from(now),AlertRepository.bytes(c.tenantId()),
                AlertRepository.bytes(c.outboxId()),c.attemptCount(),AlertEventContract.MAX_ATTEMPTS,
                c.lockVersion(),Timestamp.from(now));
        else changed=jdbc.update("""
                UPDATE outbox_event SET attempt_count=attempt_count+1,locked_by=?,locked_at=?,
                  lock_version=lock_version+1,updated_at=?,updated_by_type='SERVICE'
                WHERE tenant_id=? AND outbox_id=? AND status='PUBLISHING' AND attempt_count=?
                  AND attempt_count<? AND locked_by=? AND lock_version=? AND locked_at<=?
                """,token,Timestamp.from(now),Timestamp.from(now),AlertRepository.bytes(c.tenantId()),
                AlertRepository.bytes(c.outboxId()),c.attemptCount(),AlertEventContract.MAX_ATTEMPTS,
                c.lockedBy(),c.lockVersion(),Timestamp.from(cutoff));
        return changed==1?claimed(c.tenantId(),c.outboxId(),token,c.attemptCount()+1,c.lockVersion()+1):Optional.empty();}
    Optional<Claimed> claimRecovery(Candidate c,String token,Instant now,Instant cutoff){requireTx();requireTenant(c.tenantId());if(c.attemptCount()!=8)return Optional.empty();int changed;
        if("PENDING".equals(c.status()))changed=jdbc.update("""
                UPDATE outbox_event SET status='PUBLISHING',locked_by=?,locked_at=?,lock_version=lock_version+1,
                  updated_at=?,updated_by_type='SERVICE' WHERE tenant_id=? AND outbox_id=? AND status='PENDING'
                  AND attempt_count=8 AND lock_version=? AND next_attempt_at<=?
                """,token,Timestamp.from(now),Timestamp.from(now),AlertRepository.bytes(c.tenantId()),
                AlertRepository.bytes(c.outboxId()),c.lockVersion(),Timestamp.from(now));
        else changed=jdbc.update("""
                UPDATE outbox_event SET locked_by=?,locked_at=?,lock_version=lock_version+1,
                  updated_at=?,updated_by_type='SERVICE' WHERE tenant_id=? AND outbox_id=? AND status='PUBLISHING'
                  AND attempt_count=8 AND locked_by=? AND lock_version=? AND locked_at<=?
                """,token,Timestamp.from(now),Timestamp.from(now),AlertRepository.bytes(c.tenantId()),
                AlertRepository.bytes(c.outboxId()),c.lockedBy(),c.lockVersion(),Timestamp.from(cutoff));
        return changed==1?claimed(c.tenantId(),c.outboxId(),token,8,c.lockVersion()+1):Optional.empty();}
    private Optional<Claimed> claimed(UUID tenant,UUID outbox,String token,int attempt,long version){return jdbc.query("""
            SELECT tenant_id,outbox_id,message_id,publication_key,business_intent_key,aggregate_id,
              contract_ref,body_bytes,body_sha256,correlation_id,causation_id,attempt_count,
              locked_by,locked_at,lock_version,created_at FROM outbox_event
            WHERE tenant_id=? AND outbox_id=? AND status='PUBLISHING' AND attempt_count=?
              AND locked_by=? AND lock_version=?
            """,CLAIMED,AlertRepository.bytes(tenant),AlertRepository.bytes(outbox),attempt,token,version)
            .stream().findFirst();}
    boolean published(Claimed r,Instant now,Instant cutoff){requireTx();requireTenant(r.tenantId());int changed=jdbc.update("""
            UPDATE outbox_event SET status='PUBLISHED',locked_by=NULL,locked_at=NULL,last_error_code=NULL,
              broker_confirmed_at=?,lock_version=lock_version+1,updated_at=?,updated_by_type='SERVICE'
            WHERE tenant_id=? AND outbox_id=? AND status='PUBLISHING' AND attempt_count=?
              AND locked_by=? AND lock_version=? AND locked_at>?
            """,Timestamp.from(now),Timestamp.from(now),AlertRepository.bytes(r.tenantId()),
            AlertRepository.bytes(r.outboxId()),r.attemptCount(),r.token(),r.lockVersion(),Timestamp.from(cutoff));
        if(changed==1){jdbc.update("""
                UPDATE message_recovery SET state='RESOLVED',resolved_at=?,next_attempt_at=NULL,
                  lock_version=lock_version+1,updated_at=?,updated_by_type='SERVICE'
                WHERE tenant_id=? AND direction='OUTBOX' AND source_record_id=? AND state='REPLAYING'
                """,Timestamp.from(now),Timestamp.from(now),AlertRepository.bytes(r.tenantId()),AlertRepository.bytes(r.outboxId()));}
        return changed==1;}
    boolean pending(Claimed r,Instant now,Instant cutoff,Instant next,String code){requireTx();requireTenant(r.tenantId());int changed=jdbc.update("""
            UPDATE outbox_event SET status='PENDING',next_attempt_at=?,locked_by=NULL,locked_at=NULL,
              last_error_code=?,broker_confirmed_at=NULL,lock_version=lock_version+1,updated_at=?,updated_by_type='SERVICE'
            WHERE tenant_id=? AND outbox_id=? AND status='PUBLISHING' AND attempt_count=?
              AND locked_by=? AND lock_version=? AND locked_at>?
            """,Timestamp.from(next),code,Timestamp.from(now),AlertRepository.bytes(r.tenantId()),
            AlertRepository.bytes(r.outboxId()),r.attemptCount(),r.token(),r.lockVersion(),Timestamp.from(cutoff));
        if(changed==1&&isCreate(r))jdbc.update("""
                UPDATE incident_ticket_link SET retry_count=?,last_attempt_at=?,lock_version=lock_version+1,
                  updated_at=?,updated_by_type='SERVICE' WHERE tenant_id=? AND incident_id=? AND status='REQUESTED'
                """,r.attemptCount(),Timestamp.from(now),Timestamp.from(now),AlertRepository.bytes(r.tenantId()),AlertRepository.bytes(r.incidentId()));
        return changed==1;}
    boolean recover(Claimed r,Instant now,Instant cutoff,String code){requireTx();requireTenant(r.tenantId());int changed=jdbc.update("""
            UPDATE outbox_event SET status='RECOVERY_REQUIRED',next_attempt_at=?,locked_by=NULL,locked_at=NULL,
              last_error_code=?,broker_confirmed_at=NULL,lock_version=lock_version+1,updated_at=?,updated_by_type='SERVICE'
            WHERE tenant_id=? AND outbox_id=? AND status='PUBLISHING' AND attempt_count=8
              AND locked_by=? AND lock_version=? AND locked_at>?
            """,Timestamp.from(now),code,Timestamp.from(now),AlertRepository.bytes(r.tenantId()),
            AlertRepository.bytes(r.outboxId()),r.token(),r.lockVersion(),Timestamp.from(cutoff));
        if(changed!=1)return false;
        if(isCreate(r))jdbc.update("""
                UPDATE incident_ticket_link SET status='RECOVERY_REQUIRED',recovery_required_at=?,
                  recovery_reason_code=?,retry_count=8,last_attempt_at=?,lock_version=lock_version+1,
                  updated_at=?,updated_by_type='SERVICE' WHERE tenant_id=? AND incident_id=? AND status='REQUESTED'
                """,Timestamp.from(now),code,Timestamp.from(now),Timestamp.from(now),
                AlertRepository.bytes(r.tenantId()),AlertRepository.bytes(r.incidentId()));
        int reopened=jdbc.update("""
                UPDATE message_recovery SET state='OPEN',attempt_count=8,next_attempt_at=NULL,
                  last_error_code=?,last_error_summary='Alert M7 publication exhausted again.',
                  resolved_at=NULL,lock_version=lock_version+1,updated_at=?,updated_by_type='SERVICE',updated_by_id=NULL
                WHERE tenant_id=? AND direction='OUTBOX' AND source_record_id=?
                  AND business_intent_key=? AND state='REPLAYING'
                """,code,Timestamp.from(now),AlertRepository.bytes(r.tenantId()),
                AlertRepository.bytes(r.outboxId()),r.intent());
        if(reopened==0)try{jdbc.update("""
                INSERT INTO message_recovery(tenant_id,recovery_id,direction,source_record_id,
                  business_intent_key,state,attempt_count,next_attempt_at,last_error_code,last_error_summary,
                  dead_lettered_at,recovery_required_at,replay_count,last_replay_correlation_id,resolved_at,
                  lock_version,created_at,updated_at,updated_by_type,updated_by_id)
                VALUES(?,?,'OUTBOX',?,?,'OPEN',8,NULL,?,'Alert M7 publication exhausted.',NULL,?,0,NULL,NULL,0,?,?,'SERVICE',NULL)
                """,AlertRepository.bytes(r.tenantId()),AlertRepository.bytes(UUID.randomUUID()),
                AlertRepository.bytes(r.outboxId()),r.intent(),code,Timestamp.from(now),Timestamp.from(now),Timestamp.from(now));}
        catch(DuplicateKeyException duplicate){var existing=jdbc.query("""
                SELECT business_intent_key,state FROM message_recovery
                WHERE tenant_id=? AND direction='OUTBOX' AND source_record_id=?
                """,(rs,n)->new Recovery(rs.getBytes(1),rs.getString(2)),AlertRepository.bytes(r.tenantId()),AlertRepository.bytes(r.outboxId()))
                .stream().findFirst().orElseThrow();if(!Arrays.equals(existing.intent(),r.intent())||!"OPEN".equals(existing.state()))throw duplicate;}
        jdbc.update("""
                INSERT INTO audit_record(tenant_id,audit_id,action_code,target_type,target_id,outcome,
                  actor_type,actor_id,reason,error_code,correlation_id,causation_id,occurred_at,recorded_at)
                VALUES(?,?,'ALERT_OUTBOX_RECOVERY_REQUIRED','MESSAGE_RECOVERY',?,'FAILED','SERVICE',NULL,NULL,?,?,NULL,?,?)
                """,AlertRepository.bytes(r.tenantId()),AlertRepository.bytes(UUID.randomUUID()),AlertRepository.bytes(r.outboxId()),
                code,AlertRepository.bytes(r.correlationId()),Timestamp.from(now),Timestamp.from(now));return true;}
    private static boolean isCreate(Claimed r){return AlertEventContract.TICKET_CREATE_REQUESTED.contractRef().equals(r.contractRef());}
    private static void requireTx(){if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Alert Outbox mutation requires transaction");}
    private static void requireTenant(UUID tenant){TenantContextHolder.current().requireTenant(tenant);}
    private static Instant instant(ResultSet rs,String f)throws SQLException{Timestamp t=rs.getTimestamp(f);return t==null?null:t.toInstant();}
    private static final org.springframework.jdbc.core.RowMapper<Candidate>CANDIDATE=(rs,n)->new Candidate(AlertRepository.uuid(rs.getBytes("tenant_id")),AlertRepository.uuid(rs.getBytes("outbox_id")),rs.getString("status"),rs.getInt("attempt_count"),rs.getString("locked_by"),AlertRepository.uuid(rs.getBytes("correlation_id")),rs.getLong("lock_version"));
    private static final org.springframework.jdbc.core.RowMapper<Claimed>CLAIMED=(rs,n)->new Claimed(AlertRepository.uuid(rs.getBytes("tenant_id")),AlertRepository.uuid(rs.getBytes("outbox_id")),AlertRepository.uuid(rs.getBytes("message_id")),rs.getBytes("publication_key"),rs.getBytes("business_intent_key"),AlertRepository.uuid(rs.getBytes("aggregate_id")),rs.getString("contract_ref"),rs.getBytes("body_bytes"),rs.getBytes("body_sha256"),AlertRepository.uuid(rs.getBytes("correlation_id")),AlertRepository.uuid(rs.getBytes("causation_id")),rs.getInt("attempt_count"),rs.getString("locked_by"),instant(rs,"locked_at"),rs.getLong("lock_version"),instant(rs,"created_at"));
    record Candidate(UUID tenantId,UUID outboxId,String status,int attemptCount,String lockedBy,UUID correlationId,long lockVersion){}
    record Claimed(UUID tenantId,UUID outboxId,UUID messageId,byte[] publicationKey,byte[] intent,UUID incidentId,String contractRef,byte[] body,byte[] hash,UUID correlationId,UUID causationId,int attemptCount,String token,Instant lockedAt,long lockVersion,Instant createdAt){}
    private record Recovery(byte[] intent,String state){}
}
