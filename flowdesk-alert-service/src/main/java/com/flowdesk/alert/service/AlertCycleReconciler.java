package com.flowdesk.alert.service;

import com.flowdesk.alert.repository.AlertRepository;
import com.flowdesk.common.tenant.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.UUID;

/** Repairs durable waiting facts after an upgrade or an interrupted follow-up. */
@Component
@EnableScheduling
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="flowdesk.alert.webhook.enabled", havingValue="true")
public class AlertCycleReconciler {
    private final JdbcTemplate jdbc;
    private final WebhookAcceptanceService service;
    public AlertCycleReconciler(JdbcTemplate jdbc, WebhookAcceptanceService service) { this.jdbc=jdbc;this.service=service; }
    @Scheduled(fixedDelayString="${flowdesk.alert.reconciliation.delay-ms:30000}", initialDelayString="${flowdesk.alert.reconciliation.initial-delay-ms:30000}")
    public void scan() {
        var candidates=jdbc.query("""
                SELECT n.tenant_id,n.fingerprint,n.recovered_at
                FROM (SELECT i.tenant_id,i.fingerprint,MAX(e.occurred_at) AS recovered_at
                      FROM incident i JOIN alert_event e
                        ON e.tenant_id=i.tenant_id AND e.alert_event_id=i.recovery_event_id
                      WHERE i.status='RECOVERED' GROUP BY i.tenant_id,i.fingerprint) n
                WHERE 1=1
                  AND NOT EXISTS (SELECT 1 FROM incident active WHERE active.tenant_id=n.tenant_id AND active.fingerprint=n.fingerprint AND active.status='ACTIVE')
                  AND EXISTS (SELECT 1 FROM alert_event pending WHERE pending.tenant_id=n.tenant_id
                    AND pending.fingerprint=n.fingerprint AND pending.incident_id IS NULL AND pending.event_type='FIRING'
                    AND pending.occurred_at>n.recovered_at
                    AND NOT EXISTS (SELECT 1 FROM incident known JOIN alert_event first_event
                      ON first_event.tenant_id=known.tenant_id AND first_event.alert_event_id=known.first_event_id
                      WHERE known.tenant_id=pending.tenant_id AND known.fingerprint=pending.fingerprint
                        AND first_event.alert_cycle_ref=pending.alert_cycle_ref))
                ORDER BY n.recovered_at,n.tenant_id,n.fingerprint LIMIT 100
                """,(rs,index)->new Candidate(AlertRepository.uuid(rs.getBytes("tenant_id")),rs.getString("fingerprint"),rs.getTimestamp("recovered_at").toInstant()));
        for (var candidate:candidates) {
            try(var scope=TenantContextScope.open(TenantContext.ofPersistedTask(candidate.tenant(),ActorRef.service("flowdesk-alert-service"),UUID.randomUUID()))) {
                service.reconcilePersistedCycles(candidate.tenant(),candidate.fingerprint(),candidate.recoveredAt());
            } catch(RuntimeException failure) {
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("Waiting alert cycle reconciliation will retry",failure);
            }
        }
    }
    private record Candidate(UUID tenant,String fingerprint,Instant recoveredAt) { }
}
