package com.flowdesk.alert.event;

import com.flowdesk.alert.repository.AlertRepository;
import com.flowdesk.common.tenant.TenantContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

final class TicketResultRepository{
    private final JdbcTemplate jdbc;TicketResultRepository(JdbcTemplate j){jdbc=j;}
    Duplicate duplicate(AcceptedTicketResult m,boolean lock){requireTenant(m.tenantId());String suffix=lock?" FOR UPDATE":"";Optional<Inbox> byMessage=find("producer_service=? AND message_id=?",suffix,m,AlertEventContract.TICKET_ESTABLISHED.producer(),AlertRepository.bytes(m.messageId()));if(byMessage.isPresent())return classify(m,byMessage.get(),"MESSAGE_ID_CONTENT_CONFLICT");Optional<Inbox> byIntent=find("producer_service=? AND contract_ref=? AND business_intent_key=?",suffix,m,AlertEventContract.TICKET_ESTABLISHED.producer(),AlertEventContract.TICKET_ESTABLISHED.contractRef(),m.businessIntentKey());return byIntent.map(i->classify(m,i,"BUSINESS_INTENT_CONTENT_CONFLICT")).orElseGet(Duplicate::none);}
    private Optional<Inbox> find(String where,String suffix,AcceptedTicketResult m,Object...args){Object[] all=new Object[args.length+1];all[0]=AlertRepository.bytes(m.tenantId());System.arraycopy(args,0,all,1,args.length);return jdbc.query("SELECT inbox_id,business_intent_key,body_bytes,body_sha256,status,result_id,lock_version FROM inbox_message WHERE tenant_id=? AND "+where+suffix,(rs,n)->new Inbox(AlertRepository.uuid(rs.getBytes(1)),rs.getBytes(2),rs.getBytes(3),rs.getBytes(4),rs.getString(5),AlertRepository.uuid(rs.getBytes(6)),rs.getLong(7)),all).stream().findFirst();}
    private static Duplicate classify(AcceptedTicketResult m,Inbox i,String code){boolean same=Arrays.equals(m.businessIntentKey(),i.intent())&&Arrays.equals(m.body(),i.body())&&Arrays.equals(m.bodyHash(),i.hash());if(!same)return new Duplicate(Kind.CONFLICT,i,code);return new Duplicate("RECOVERY_REQUIRED".equals(i.status())?Kind.RECOVERABLE:Kind.TERMINAL,i,null);}
    void establish(AcceptedTicketResult m,AlertRepository.LinkRow link,Instant now){requireTenant(m.tenantId());if(!"ESTABLISHED".equals(link.status())){int n=jdbc.update("""
            UPDATE incident_ticket_link SET status='ESTABLISHED',ticket_id=?,established_at=?,
              recovery_required_at=NULL,recovery_reason_code=NULL,lock_version=lock_version+1,
              updated_at=?,updated_by_type='SERVICE',updated_by_id=NULL
            WHERE tenant_id=? AND incident_id=? AND status IN('REQUESTED','RECOVERY_REQUIRED') AND lock_version=?
            """,AlertRepository.bytes(m.ticketId()),Timestamp.from(m.establishedAt()),Timestamp.from(now),AlertRepository.bytes(m.tenantId()),AlertRepository.bytes(m.incidentId()),link.lockVersion());if(n!=1)throw new IllegalStateException("IncidentTicketLink CAS lost");}
        jdbc.update("""
            UPDATE message_recovery SET state='RESOLVED',resolved_at=?,next_attempt_at=NULL,
              lock_version=lock_version+1,updated_at=?,updated_by_type='SERVICE',updated_by_id=NULL
            WHERE tenant_id=? AND direction='OUTBOX' AND state IN('OPEN','REPLAYING')
              AND source_record_id IN(SELECT outbox_id FROM outbox_event WHERE tenant_id=?
                AND aggregate_type='INCIDENT' AND aggregate_id=? AND contract_ref=? AND contract_version=1)
            """,Timestamp.from(now),Timestamp.from(now),AlertRepository.bytes(m.tenantId()),AlertRepository.bytes(m.tenantId()),AlertRepository.bytes(m.incidentId()),AlertEventContract.TICKET_CREATE_REQUESTED.contractRef());}
    void insertInbox(AcceptedTicketResult m,String status,UUID result,int attempts,String error,Instant now){requireTenant(m.tenantId());jdbc.update("""
            INSERT INTO inbox_message(tenant_id,inbox_id,producer_service,message_id,business_intent_key,
              contract_ref,contract_version,body_sha256,body_bytes,correlation_id,causation_id,occurred_at,
              status,result_type,result_id,attempt_count,last_error_code,first_received_at,last_attempt_at,
              processed_at,lock_version,created_at,updated_at,updated_by_type,updated_by_id)
            VALUES(?,?,?,?,?,?,1,?,?,?,?,?,?,?, ?,?,?,?,?,?,0,?,?,'SERVICE',NULL)
            """,AlertRepository.bytes(m.tenantId()),AlertRepository.bytes(UUID.randomUUID()),AlertEventContract.TICKET_ESTABLISHED.producer(),AlertRepository.bytes(m.messageId()),m.businessIntentKey(),AlertEventContract.TICKET_ESTABLISHED.contractRef(),m.bodyHash(),m.body(),AlertRepository.bytes(m.correlationId()),AlertRepository.bytes(m.causationId()),Timestamp.from(m.envelopeOccurredAt()),status,"PROCESSED".equals(status)?"INCIDENT":null,AlertRepository.bytes(result),attempts,error,Timestamp.from(now),Timestamp.from(now),"RECOVERY_REQUIRED".equals(status)?null:Timestamp.from(now),Timestamp.from(now),Timestamp.from(now));}
    void finishRecovered(AcceptedTicketResult m,Inbox i,UUID result,Instant now){requireTenant(m.tenantId());int n=jdbc.update("""
            UPDATE inbox_message SET status='PROCESSED',result_type='INCIDENT',result_id=?,attempt_count=attempt_count+1,
              last_error_code=NULL,last_attempt_at=?,processed_at=?,lock_version=lock_version+1,
              updated_at=?,updated_by_type='SERVICE' WHERE tenant_id=? AND inbox_id=? AND status='RECOVERY_REQUIRED' AND lock_version=?
            """,AlertRepository.bytes(result),Timestamp.from(now),Timestamp.from(now),Timestamp.from(now),AlertRepository.bytes(m.tenantId()),AlertRepository.bytes(i.inboxId()),i.version());if(n!=1)throw new IllegalStateException("Ticket result Inbox CAS lost");resolveInbox(m.tenantId(),i.inboxId(),now);}
    void rejectRecovered(AcceptedTicketResult m,Inbox i,String error,Instant now){requireTenant(m.tenantId());int n=jdbc.update("""
            UPDATE inbox_message SET status='REJECTED',result_type=NULL,result_id=NULL,attempt_count=attempt_count+1,
              last_error_code=?,last_attempt_at=?,processed_at=?,lock_version=lock_version+1,updated_at=?,updated_by_type='SERVICE'
            WHERE tenant_id=? AND inbox_id=? AND status='RECOVERY_REQUIRED' AND lock_version=?
            """,error,Timestamp.from(now),Timestamp.from(now),Timestamp.from(now),AlertRepository.bytes(m.tenantId()),AlertRepository.bytes(i.inboxId()),i.version());if(n!=1)throw new IllegalStateException("Ticket result Inbox CAS lost");resolveInbox(m.tenantId(),i.inboxId(),now);}
    void insertRecovery(AcceptedTicketResult m,String code,Instant now){requireTenant(m.tenantId());UUID inbox=UUID.randomUUID();insertInboxWithId(m,inbox,now,code);jdbc.update("""
            INSERT INTO message_recovery(tenant_id,recovery_id,direction,source_record_id,business_intent_key,
              state,attempt_count,next_attempt_at,last_error_code,last_error_summary,dead_lettered_at,recovery_required_at,
              replay_count,last_replay_correlation_id,resolved_at,lock_version,created_at,updated_at,updated_by_type,updated_by_id)
            VALUES(?,?,'INBOX',?,?,'OPEN',6,NULL,?,'Ticket result delivery exhausted.',?,?,0,NULL,NULL,0,?,?,'SERVICE',NULL)
            """,AlertRepository.bytes(m.tenantId()),AlertRepository.bytes(UUID.randomUUID()),AlertRepository.bytes(inbox),m.businessIntentKey(),code,Timestamp.from(now),Timestamp.from(now),Timestamp.from(now),Timestamp.from(now));}
    private void insertInboxWithId(AcceptedTicketResult m,UUID inbox,Instant now,String error){jdbc.update("""
            INSERT INTO inbox_message(tenant_id,inbox_id,producer_service,message_id,business_intent_key,contract_ref,
              contract_version,body_sha256,body_bytes,correlation_id,causation_id,occurred_at,status,result_type,result_id,
              attempt_count,last_error_code,first_received_at,last_attempt_at,processed_at,lock_version,created_at,updated_at,updated_by_type,updated_by_id)
            VALUES(?,?,?,?,?,?,1,?,?,?,?,?,'RECOVERY_REQUIRED',NULL,NULL,6,?,?,?,NULL,0,?,?,'SERVICE',NULL)
            """,AlertRepository.bytes(m.tenantId()),AlertRepository.bytes(inbox),AlertEventContract.TICKET_ESTABLISHED.producer(),AlertRepository.bytes(m.messageId()),m.businessIntentKey(),AlertEventContract.TICKET_ESTABLISHED.contractRef(),m.bodyHash(),m.body(),AlertRepository.bytes(m.correlationId()),AlertRepository.bytes(m.causationId()),Timestamp.from(m.envelopeOccurredAt()),error,Timestamp.from(now),Timestamp.from(now),Timestamp.from(now),Timestamp.from(now));}
    void audit(AcceptedTicketResult m,String action,String outcome,String error,Instant now){jdbc.update("""
            INSERT INTO audit_record(tenant_id,audit_id,action_code,target_type,target_id,outcome,actor_type,actor_id,
              reason,error_code,correlation_id,causation_id,occurred_at,recorded_at)
            VALUES(?,?,?,'INCIDENT',?,?,'SERVICE',NULL,NULL,?,?,?,?,?)
            """,AlertRepository.bytes(m.tenantId()),AlertRepository.bytes(UUID.randomUUID()),action,AlertRepository.bytes(m.incidentId()),outcome,error,AlertRepository.bytes(m.correlationId()),AlertRepository.bytes(m.messageId()),Timestamp.from(now),Timestamp.from(now));}
    private void resolveInbox(UUID tenant,UUID inbox,Instant now){jdbc.update("UPDATE message_recovery SET state='RESOLVED',resolved_at=?,lock_version=lock_version+1,updated_at=? WHERE tenant_id=? AND direction='INBOX' AND source_record_id=? AND state IN('OPEN','REPLAYING')",Timestamp.from(now),Timestamp.from(now),AlertRepository.bytes(tenant),AlertRepository.bytes(inbox));}
    private static void requireTenant(UUID t){TenantContextHolder.current().requireTenant(t);}enum Kind{NONE,TERMINAL,RECOVERABLE,CONFLICT}record Inbox(UUID inboxId,byte[] intent,byte[] body,byte[] hash,String status,UUID result,long version){}record Duplicate(Kind kind,Inbox inbox,String code){static Duplicate none(){return new Duplicate(Kind.NONE,null,null);}}
}
