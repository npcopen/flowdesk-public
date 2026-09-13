package com.flowdesk.alert.repository;

import com.flowdesk.common.tenant.TenantContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.ByteBuffer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Alert-owned tenant-first persistence for API-049～054 and M7 local facts. */
public final class AlertRepository {
    private static final String REPRESENTED_CYCLE = """
            (SELECT COUNT(*) FROM incident rn JOIN alert_event rf
               ON rf.tenant_id=rn.tenant_id AND rf.alert_event_id=rn.first_event_id
             WHERE rn.tenant_id=e.tenant_id AND rn.fingerprint=e.fingerprint
               AND rf.alert_cycle_ref=e.alert_cycle_ref)=1
            """;
    private final JdbcTemplate jdbc;
    public AlertRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    // These three pre-trust operations are scoped exclusively by the already
    // cryptographically verified integration candidate; they never read TenantContext.
    public void insertNoncePreTrust(UUID tenantId, UUID nonceId, UUID integrationId,
                                    byte[] nonceHash, Instant requestTimestamp,
                                    Instant acceptedAt, Instant expiresAt) {
        jdbc.update("""
                INSERT INTO webhook_nonce
                  (tenant_id,nonce_id,integration_id,nonce_hash,request_timestamp,
                   accepted_at,expires_at,created_by_type,created_by_id)
                VALUES (?,?,?,?,?,?,?,'INTEGRATION',?)
                """, bytes(tenantId), bytes(nonceId), bytes(integrationId), nonceHash,
                ts(requestTimestamp), ts(acceptedAt), ts(expiresAt), bytes(integrationId));
    }

    public Optional<EventRow> findEventPreTrust(UUID tenantId, UUID integrationId,
                                                 String externalEventId) {
        return jdbc.query("""
                SELECT e.alert_event_id,e.integration_id,i.integration_code,i.integration_name,
                       e.external_event_id,e.event_type,e.fingerprint,e.alert_cycle_ref,
                       e.occurred_at,e.received_at,e.disposition,e.incident_id,e.raw_body_sha256,
                       e.summary,e.correlation_id
                FROM alert_event e JOIN monitoring_integration i
                  ON i.tenant_id=e.tenant_id AND i.integration_id=e.integration_id
                WHERE e.tenant_id=? AND e.integration_id=? AND e.external_event_id=?
                """, EVENT_MAPPER, bytes(tenantId), bytes(integrationId), externalEventId)
                .stream().findFirst();
    }

    public Optional<IncidentCycleRow> findActiveForUpdatePreTrust(UUID tenantId,
                                                                  String fingerprint) {
        return jdbc.query("""
                SELECT n.incident_id,n.fingerprint,n.generation_no,n.status,n.event_count,
                       n.opened_at,n.last_event_at,n.recovered_at,n.summary,n.lock_version,
                       n.created_at,n.updated_at,
                       (SELECT e.alert_cycle_ref FROM alert_event e
                        WHERE e.tenant_id=n.tenant_id
                          AND e.alert_event_id=n.first_event_id) AS alert_cycle_ref
                FROM incident n
                WHERE n.tenant_id=? AND n.fingerprint=? AND n.status='ACTIVE'
                FOR UPDATE
                """, INCIDENT_CYCLE_MAPPER, bytes(tenantId), fingerprint).stream().findFirst();
    }

    public void insertRecoveredIncident(UUID tenantId, UUID incidentId, String fingerprint,
                                         int generation, UUID eventId, Instant now, String summary,
                                         UUID integrationId) {
        requireTenant(tenantId);
        jdbc.update("""
                INSERT INTO incident (tenant_id,incident_id,fingerprint,generation_no,status,
                  first_event_id,last_event_id,event_count,opened_at,last_event_at,recovered_at,
                  recovery_event_id,recovery_review_state,summary,lock_version,created_at,created_by_type,created_by_id,
                  updated_at,updated_by_type,updated_by_id)
                VALUES (?,?,?,?,'RECOVERED',?,?,1,?,?,?,?,'NONE',?,0,?,'INTEGRATION',?,?,'INTEGRATION',?)
                """, bytes(tenantId), bytes(incidentId), fingerprint, generation, bytes(eventId),
                bytes(eventId), Timestamp.from(now), Timestamp.from(now), Timestamp.from(now),
                bytes(eventId), summary, Timestamp.from(now), bytes(integrationId),
                Timestamp.from(now), bytes(integrationId));
    }

    public boolean isRecoveryOnlyIncident(UUID tenantId, UUID incidentId) {
        requireTenant(tenantId);
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM incident i JOIN alert_event e
                  ON e.tenant_id=i.tenant_id AND e.alert_event_id=i.first_event_id
                WHERE i.tenant_id=? AND i.incident_id=? AND i.status='RECOVERED'
                  AND e.event_type='RESOLVED'
                """, Long.class, bytes(tenantId), bytes(incidentId)) > 0;
    }

    public long countActiveIncidents(UUID tenantId) {
        requireTenant(tenantId);
        return jdbc.queryForObject("SELECT COUNT(*) FROM incident WHERE tenant_id = ? AND status = 'ACTIVE'",
                Long.class, bytes(tenantId));
    }

    public List<IncidentCycleRow> findByCyclePreTrust(UUID tenantId, String fingerprint,
                                                       String cycleRef) {
        return jdbc.query("""
                SELECT n.incident_id,n.fingerprint,n.generation_no,n.status,n.event_count,
                       n.opened_at,n.last_event_at,n.recovered_at,n.summary,n.lock_version,
                       n.created_at,n.updated_at,e.alert_cycle_ref
                FROM incident n JOIN alert_event e
                  ON e.tenant_id=n.tenant_id AND e.alert_event_id=n.first_event_id
                WHERE n.tenant_id=? AND n.fingerprint=? AND e.alert_cycle_ref=?
                ORDER BY n.generation_no,n.incident_id
                """, INCIDENT_CYCLE_MAPPER, bytes(tenantId), fingerprint, cycleRef);
    }

    public int nextGenerationPreTrust(UUID tenantId, String fingerprint) {
        Integer value = jdbc.queryForObject("""
                SELECT COALESCE(MAX(generation_no),0)+1 FROM incident
                WHERE tenant_id=? AND fingerprint=?
                """, Integer.class, bytes(tenantId), fingerprint);
        return value == null ? 1 : value;
    }

    public Optional<EventRow> findUnlinkedRecoveryPreTrust(UUID tenantId, String fingerprint, String cycleRef) {
        return jdbc.query("""
                SELECT e.alert_event_id,e.integration_id,i.integration_code,i.integration_name,
                       e.external_event_id,e.event_type,e.fingerprint,e.alert_cycle_ref,
                       e.occurred_at,e.received_at,e.disposition,e.incident_id,e.raw_body_sha256,
                       e.summary,e.correlation_id
                FROM alert_event e JOIN monitoring_integration i
                  ON i.tenant_id=e.tenant_id AND i.integration_id=e.integration_id
                WHERE e.tenant_id=? AND e.fingerprint=? AND e.alert_cycle_ref=?
                  AND e.event_type='RESOLVED' AND e.incident_id IS NULL
                ORDER BY e.received_at,e.alert_event_id LIMIT 1
                """, EVENT_MAPPER, bytes(tenantId), fingerprint, cycleRef).stream().findFirst();
    }

    /** Durable, previously accepted facts waiting for an earlier active cycle to recover. */
    public List<EventRow> findWaitingCycles(UUID tenantId, String fingerprint, Instant recoveredAt) {
        requireTenant(tenantId);
        return jdbc.query("""
                SELECT e.alert_event_id,e.integration_id,i.integration_code,i.integration_name,
                       e.external_event_id,e.event_type,e.fingerprint,e.alert_cycle_ref,
                       e.occurred_at,e.received_at,e.disposition,e.incident_id,e.raw_body_sha256,
                       e.summary,e.correlation_id
                FROM alert_event e JOIN monitoring_integration i
                  ON i.tenant_id=e.tenant_id AND i.integration_id=e.integration_id
                WHERE e.tenant_id=? AND e.fingerprint=? AND e.incident_id IS NULL
                  AND e.event_type='FIRING' AND e.occurred_at>?
                  AND NOT EXISTS (SELECT 1 FROM incident n JOIN alert_event f
                    ON f.tenant_id=n.tenant_id AND f.alert_event_id=n.first_event_id
                    WHERE n.tenant_id=e.tenant_id AND n.fingerprint=e.fingerprint
                      AND f.alert_cycle_ref=e.alert_cycle_ref)
                ORDER BY e.occurred_at,e.alert_event_id
                """,EVENT_MAPPER,bytes(tenantId),fingerprint,ts(recoveredAt));
    }

    public void insertEventPreTrust(UUID tenantId, UUID eventId, UUID integrationId,
                                    String externalId, String eventType, String fingerprint,
                                    String cycleRef, Instant occurredAt, Instant receivedAt,
                                    String disposition, UUID incidentId, byte[] rawHash,
                                    String summary, UUID correlationId) {
        jdbc.update("""
                INSERT INTO alert_event
                  (tenant_id,alert_event_id,integration_id,external_event_id,event_type,
                   fingerprint,alert_cycle_ref,occurred_at,received_at,disposition,incident_id,
                   raw_body_sha256,raw_body_ref,raw_body_ciphertext,summary,correlation_id,
                   created_at,created_by_type,created_by_id)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,NULL,NULL,?,?,?,'INTEGRATION',?)
                """, bytes(tenantId), bytes(eventId), bytes(integrationId), externalId,
                eventType, fingerprint, cycleRef, ts(occurredAt), ts(receivedAt), disposition,
                bytes(incidentId), rawHash, summary, bytes(correlationId), ts(receivedAt),
                bytes(integrationId));
    }

    // All remaining mutations require the TenantContext installed only after the
    // unique nonce and final immutable AlertEvent have both been inserted.
    public void insertIncident(UUID tenantId, UUID incidentId, String fingerprint,
                               int generation, UUID eventId, Instant now, String summary,
                               UUID integrationId) {
        requireTenant(tenantId);
        jdbc.update("""
                INSERT INTO incident
                  (tenant_id,incident_id,fingerprint,generation_no,status,first_event_id,
                   last_event_id,event_count,opened_at,last_event_at,recovered_at,
                   recovery_event_id,recovery_review_state,summary,lock_version,created_at,
                   created_by_type,created_by_id,updated_at,updated_by_type,updated_by_id)
                VALUES (?,?,?,?,'ACTIVE',?,?,1,?,?,NULL,NULL,'NONE',?,0,?,
                        'INTEGRATION',?,?,'INTEGRATION',?)
                """, bytes(tenantId), bytes(incidentId), fingerprint, generation,
                bytes(eventId), bytes(eventId), ts(now), ts(now), summary, ts(now),
                bytes(integrationId), ts(now), bytes(integrationId));
    }

    public int aggregateFiring(UUID tenantId, IncidentCycleRow active, UUID eventId,
                               Instant now, String summary, UUID integrationId) {
        requireTenant(tenantId);
        return jdbc.update("""
                UPDATE incident SET last_event_id=?,event_count=event_count+1,
                    last_event_at=GREATEST(last_event_at,?),
                    summary=COALESCE(?,summary),lock_version=lock_version+1,
                    updated_at=?,updated_by_type='INTEGRATION',updated_by_id=?
                WHERE tenant_id=? AND incident_id=? AND status='ACTIVE' AND lock_version=?
                """, bytes(eventId), ts(now), summary, ts(now), bytes(integrationId),
                bytes(tenantId), bytes(active.incidentId()), active.lockVersion());
    }

    public int recoverIncident(UUID tenantId, IncidentCycleRow active, UUID eventId,
                               Instant now, String summary, UUID integrationId) {
        requireTenant(tenantId);
        return jdbc.update("""
                UPDATE incident SET status='RECOVERED',last_event_id=?,event_count=event_count+1,
                    last_event_at=GREATEST(last_event_at,?),
                    recovered_at=GREATEST(last_event_at,?),recovery_event_id=?,
                    summary=COALESCE(?,summary),lock_version=lock_version+1,
                    updated_at=?,updated_by_type='INTEGRATION',updated_by_id=?
                WHERE tenant_id=? AND incident_id=? AND status='ACTIVE' AND lock_version=?
                """, bytes(eventId), ts(now), ts(now), bytes(eventId), summary, ts(now),
                bytes(integrationId), bytes(tenantId), bytes(active.incidentId()),
                active.lockVersion());
    }

    public void insertTicketLink(UUID tenantId, UUID incidentId, byte[] intent, Instant now) {
        requireTenant(tenantId);
        jdbc.update("""
                INSERT INTO incident_ticket_link
                  (tenant_id,incident_id,create_ticket_intent_key,status,ticket_id,
                   requested_at,established_at,recovery_required_at,recovery_reason_code,
                   retry_count,last_attempt_at,lock_version,created_at,created_by_type,
                   created_by_id,updated_at,updated_by_type,updated_by_id)
                VALUES (?,?,?,'REQUESTED',NULL,?,NULL,NULL,NULL,0,NULL,0,?,
                        'SERVICE',NULL,?,'SERVICE',NULL)
                """, bytes(tenantId), bytes(incidentId), intent, ts(now), ts(now), ts(now));
    }

    public void insertOutbox(UUID tenantId, UUID outboxId, UUID messageId,
                             byte[] publicationKey, byte[] businessIntent,
                             UUID incidentId, String contractRef, byte[] body, byte[] bodyHash,
                             UUID correlationId, UUID causationId, Instant now,
                             String creatorType) {
        requireTenant(tenantId);
        jdbc.update("""
                INSERT INTO outbox_event
                  (tenant_id,outbox_id,message_id,publication_key,business_intent_key,
                   aggregate_type,aggregate_id,contract_ref,contract_version,body_bytes,
                   body_sha256,correlation_id,causation_id,status,attempt_count,next_attempt_at,
                   locked_by,locked_at,last_error_code,broker_confirmed_at,lock_version,
                   created_at,created_by_type,created_by_id,updated_at,updated_by_type,updated_by_id)
                VALUES (?,?,?,?,?,'INCIDENT',?,?,1,?,?,?,?, 'PENDING',0,?,NULL,NULL,NULL,NULL,0,
                        ?,?,NULL,?,'SERVICE',NULL)
                """, bytes(tenantId), bytes(outboxId), bytes(messageId), publicationKey,
                businessIntent, bytes(incidentId), contractRef, body, bodyHash,
                bytes(correlationId), bytes(causationId), ts(now), ts(now), creatorType,
                ts(now));
    }

    public void insertAudit(UUID tenantId, UUID auditId, String action, String targetType,
                            UUID targetId, String outcome, String reason, String error,
                            String actorType, UUID actorId, UUID correlationId,
                            UUID causationId, Instant now) {
        requireTenant(tenantId);
        jdbc.update("""
                INSERT INTO audit_record
                  (tenant_id,audit_id,action_code,target_type,target_id,outcome,actor_type,
                   actor_id,reason,error_code,correlation_id,causation_id,occurred_at,recorded_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, bytes(tenantId), bytes(auditId), action, targetType, bytes(targetId),
                outcome, actorType, bytes(actorId), reason, error, bytes(correlationId),
                bytes(causationId), ts(now), ts(now));
    }

    public Optional<LinkRow> findLink(UUID tenantId, UUID incidentId, boolean lock) {
        requireTenant(tenantId);
        return jdbc.query("""
                SELECT incident_id,create_ticket_intent_key,status,ticket_id,requested_at,
                       established_at,recovery_required_at,recovery_reason_code,retry_count,
                       last_attempt_at,lock_version,created_at,updated_at
                FROM incident_ticket_link WHERE tenant_id=? AND incident_id=?
                """ + (lock ? " FOR UPDATE" : ""), LINK_MAPPER,
                bytes(tenantId), bytes(incidentId)).stream().findFirst();
    }

    public Optional<IncidentCycleRow> findIncident(UUID tenantId, UUID incidentId, boolean lock) {
        requireTenant(tenantId);
        return jdbc.query("""
                SELECT n.incident_id,n.fingerprint,n.generation_no,n.status,n.event_count,
                       n.opened_at,n.last_event_at,n.recovered_at,n.summary,n.lock_version,
                       n.created_at,n.updated_at,
                       (SELECT e.alert_cycle_ref FROM alert_event e
                        WHERE e.tenant_id=n.tenant_id
                          AND e.alert_event_id=n.first_event_id) AS alert_cycle_ref
                FROM incident n
                WHERE n.tenant_id=? AND n.incident_id=?
                """ + (lock ? " FOR UPDATE" : ""), INCIDENT_CYCLE_MAPPER,
                bytes(tenantId), bytes(incidentId)).stream().findFirst();
    }

    public Optional<EventRow> findEvent(UUID tenantId, UUID eventId) {
        requireTenant(tenantId);
        return jdbc.query("""
                SELECT e.alert_event_id,e.integration_id,i.integration_code,i.integration_name,
                       e.external_event_id,e.event_type,e.fingerprint,e.alert_cycle_ref,
                       e.occurred_at,e.received_at,e.disposition,e.incident_id,e.raw_body_sha256,
                       e.summary,e.correlation_id
                FROM alert_event e JOIN monitoring_integration i
                  ON i.tenant_id=e.tenant_id AND i.integration_id=e.integration_id
                WHERE e.tenant_id=? AND e.alert_event_id=?
                """, EVENT_MAPPER, bytes(tenantId), bytes(eventId)).stream().findFirst();
    }

    public List<EventRow> listEvents(UUID tenantId, EventFilter filter, String sort,
                                     Instant cursorTime, UUID cursorId, int limit) {
        requireTenant(tenantId);
        String column = "-occurredAt".equals(sort) ? "e.occurred_at" : "e.received_at";
        boolean ascending = "receivedAt".equals(sort);
        String cmp = ascending ? ">" : "<";
        StringBuilder sql = new StringBuilder("""
                SELECT e.alert_event_id,e.integration_id,i.integration_code,i.integration_name,
                       e.external_event_id,e.event_type,e.fingerprint,e.alert_cycle_ref,
                       e.occurred_at,e.received_at,e.disposition,e.incident_id,e.raw_body_sha256,
                       e.summary,e.correlation_id
                FROM alert_event e JOIN monitoring_integration i
                  ON i.tenant_id=e.tenant_id AND i.integration_id=e.integration_id
                WHERE e.tenant_id=?
                """);
        List<Object> args = new ArrayList<>(); args.add(bytes(tenantId));
        add(sql,args,"e.event_type",filter.eventType());
        if ("REVIEW_REQUIRED".equals(filter.disposition())) {
            sql.append(" AND e.disposition='REVIEW_REQUIRED' AND NOT (").append(REPRESENTED_CYCLE).append(")");
        } else if ("APPLIED".equals(filter.disposition())) {
            sql.append(" AND (e.disposition='APPLIED' OR (e.incident_id IS NULL AND (").append(REPRESENTED_CYCLE).append(")))");
        } else add(sql,args,"e.disposition",filter.disposition());
        if (filter.incidentId()!=null) {
            sql.append("""
                     AND (e.incident_id=? OR (e.incident_id IS NULL AND EXISTS (
                       SELECT 1 FROM incident linked JOIN alert_event first_event
                         ON first_event.tenant_id=linked.tenant_id AND first_event.alert_event_id=linked.first_event_id
                       WHERE linked.tenant_id=? AND linked.incident_id=? AND linked.fingerprint=e.fingerprint
                         AND first_event.alert_cycle_ref=e.alert_cycle_ref)))
                    """);
            args.add(bytes(filter.incidentId())); args.add(bytes(tenantId)); args.add(bytes(filter.incidentId()));
        }
        add(sql,args,"e.fingerprint",filter.fingerprint());
        add(sql,args,"e.alert_cycle_ref",filter.cycleRef());
        add(sql,args,"e.external_event_id",filter.externalEventId());
        if (filter.receivedFrom()!=null) { sql.append(" AND e.received_at>=?"); args.add(ts(filter.receivedFrom())); }
        if (filter.receivedTo()!=null) { sql.append(" AND e.received_at<=?"); args.add(ts(filter.receivedTo())); }
        if (cursorTime!=null) {
            sql.append(" AND (").append(column).append(' ').append(cmp)
                    .append(" ? OR (").append(column).append("=? AND e.alert_event_id ")
                    .append(cmp).append(" ?))");
            args.add(ts(cursorTime)); args.add(ts(cursorTime)); args.add(bytes(cursorId));
        }
        sql.append(" ORDER BY ").append(column).append(ascending?" ASC":" DESC")
                .append(",e.alert_event_id").append(ascending?" ASC":" DESC")
                .append(" LIMIT ").append(limit);
        return jdbc.query(sql.toString(), EVENT_MAPPER, args.toArray());
    }

    public List<IncidentCycleRow> listIncidents(UUID tenantId, IncidentFilter filter,
                                                String sort, Instant cursorTime,
                                                UUID cursorId, int limit) {
        requireTenant(tenantId);
        String column = "-openedAt".equals(sort) ? "n.opened_at" : "n.last_event_at";
        boolean ascending = "lastEventAt".equals(sort); String cmp=ascending?">":"<";
        StringBuilder sql = new StringBuilder("""
                SELECT n.incident_id,n.fingerprint,n.generation_no,n.status,n.event_count,
                       n.opened_at,n.last_event_at,n.recovered_at,n.summary,n.lock_version,
                       n.created_at,n.updated_at,e.alert_cycle_ref
                FROM incident n JOIN alert_event e
                  ON e.tenant_id=n.tenant_id AND e.alert_event_id=n.first_event_id
                LEFT JOIN incident_ticket_link l
                  ON l.tenant_id=n.tenant_id AND l.incident_id=n.incident_id
                WHERE n.tenant_id=?
                """);
        List<Object> args=new ArrayList<>(); args.add(bytes(tenantId));
        add(sql,args,"n.status",filter.status()); add(sql,args,"n.fingerprint",filter.fingerprint());
        add(sql,args,"l.status",filter.ticketLinkStatus());
        if(filter.openedFrom()!=null){sql.append(" AND n.opened_at>=?");args.add(ts(filter.openedFrom()));}
        if(filter.openedTo()!=null){sql.append(" AND n.opened_at<=?");args.add(ts(filter.openedTo()));}
        if(cursorTime!=null){sql.append(" AND (").append(column).append(' ').append(cmp)
                .append(" ? OR (").append(column).append("=? AND n.incident_id ")
                .append(cmp).append(" ?))");args.add(ts(cursorTime));args.add(ts(cursorTime));args.add(bytes(cursorId));}
        sql.append(" ORDER BY ").append(column).append(ascending?" ASC":" DESC")
                .append(",n.incident_id").append(ascending?" ASC":" DESC")
                .append(" LIMIT ").append(limit);
        return jdbc.query(sql.toString(),INCIDENT_CYCLE_MAPPER,args.toArray());
    }

    private static void add(StringBuilder sql,List<Object> args,String column,Object value){
        if(value!=null){sql.append(" AND ").append(column).append("=?");args.add(value);}
    }
    private static void requireTenant(UUID tenantId){TenantContextHolder.current().requireTenant(tenantId);}
    public static byte[] bytes(UUID value){if(value==null)return null;return ByteBuffer.allocate(16)
            .putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits()).array();}
    public static UUID uuid(byte[] value){if(value==null)return null;ByteBuffer b=ByteBuffer.wrap(value);return new UUID(b.getLong(),b.getLong());}
    private static Timestamp ts(Instant value){return value==null?null:Timestamp.from(value);}
    private static Instant instant(ResultSet rs,String name)throws SQLException{Timestamp v=rs.getTimestamp(name);return v==null?null:v.toInstant();}

    private static final org.springframework.jdbc.core.RowMapper<EventRow> EVENT_MAPPER=(rs,n)->new EventRow(
            uuid(rs.getBytes("alert_event_id")),uuid(rs.getBytes("integration_id")),
            rs.getString("integration_code"),rs.getString("integration_name"),
            rs.getString("external_event_id"),rs.getString("event_type"),rs.getString("fingerprint"),
            rs.getString("alert_cycle_ref"),instant(rs,"occurred_at"),instant(rs,"received_at"),
            rs.getString("disposition"),uuid(rs.getBytes("incident_id")),rs.getBytes("raw_body_sha256"),
            rs.getString("summary"),uuid(rs.getBytes("correlation_id")));
    private static final org.springframework.jdbc.core.RowMapper<IncidentCycleRow> INCIDENT_CYCLE_MAPPER=(rs,n)->new IncidentCycleRow(
            uuid(rs.getBytes("incident_id")),rs.getString("fingerprint"),rs.getInt("generation_no"),
            rs.getString("status"),rs.getInt("event_count"),instant(rs,"opened_at"),
            instant(rs,"last_event_at"),instant(rs,"recovered_at"),rs.getString("summary"),
            rs.getLong("lock_version"),instant(rs,"created_at"),instant(rs,"updated_at"),
            rs.getString("alert_cycle_ref"));
    private static final org.springframework.jdbc.core.RowMapper<LinkRow> LINK_MAPPER=(rs,n)->new LinkRow(
            uuid(rs.getBytes("incident_id")),rs.getBytes("create_ticket_intent_key"),rs.getString("status"),
            uuid(rs.getBytes("ticket_id")),instant(rs,"requested_at"),instant(rs,"established_at"),
            instant(rs,"recovery_required_at"),rs.getString("recovery_reason_code"),
            rs.getInt("retry_count"),instant(rs,"last_attempt_at"),rs.getLong("lock_version"),
            instant(rs,"created_at"),instant(rs,"updated_at"));

    public record EventRow(UUID alertEventId,UUID integrationId,String integrationCode,
                           String integrationName,String externalEventId,String eventType,
                           String fingerprint,String cycleRef,Instant occurredAt,Instant receivedAt,
                           String disposition,UUID incidentId,byte[] rawBodySha256,String summary,
                           UUID correlationId) { }
    public record IncidentCycleRow(UUID incidentId,String fingerprint,int generationNo,String status,
                                   int eventCount,Instant openedAt,Instant lastEventAt,
                                   Instant recoveredAt,String summary,long lockVersion,
                                   Instant createdAt,Instant updatedAt,String cycleRef) { }
    public record LinkRow(UUID incidentId,byte[] createIntent,String status,UUID ticketId,
                          Instant requestedAt,Instant establishedAt,Instant recoveryRequiredAt,
                          String recoveryReasonCode,int retryCount,Instant lastAttemptAt,
                          long lockVersion,Instant createdAt,Instant updatedAt) { }
    public record EventFilter(String eventType,String disposition,UUID incidentId,String fingerprint,
                              String cycleRef,String externalEventId,Instant receivedFrom,Instant receivedTo) { }
    public record IncidentFilter(String status,String fingerprint,String ticketLinkStatus,
                                 Instant openedFrom,Instant openedTo) { }
}
