package com.flowdesk.alert.event;

import com.flowdesk.alert.repository.AlertRepository;
import com.flowdesk.alert.service.AlertIdempotencyCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties={
        "spring.cloud.service-registry.auto-registration.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:alert-ticket-result;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=",
        "spring.sql.init.mode=always","spring.sql.init.schema-locations=classpath:schema-test.sql",
        "flowdesk.alert.ticket-result.entry.enabled=true","flowdesk.alert.ticket-result.consumer.enabled=false",
        "FLOWDESK_ALERT_DB_PASSWORD="
})
class TicketResultConsumerIntegrationTest {
    private static final UUID TENANT=UUID.fromString("31111111-1111-4111-8111-111111111111");
    @Autowired TicketResultConsumer consumer;@Autowired TicketResultDlqAcceptor dlq;@Autowired JdbcTemplate jdbc;
    private UUID incident;private byte[] createIntent;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("flowdesk.security.jwt.public-key-pem",()->resource("test-jwt-public-key.pem"));r.add("flowdesk.security.cursor-token.hmac-key",()->Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)));}
    @BeforeEach void reset(){for(String table:new String[]{"message_recovery","audit_record","inbox_message","outbox_event","incident_ticket_link","incident","alert_event","webhook_nonce","monitoring_integration"})jdbc.execute("DELETE FROM "+table);incident=UUID.randomUUID();createIntent=AlertIdempotencyCodec.derivedKey(AlertEventContract.TICKET_CREATE_REQUESTED.intentNamespace(),TENANT,incident);seedIncident();}

    @Test void authoritativeResultEstablishesProjectionResolvesPublicationRecoveryAndIsIdempotent(){UUID outbox=seedCreateRecovery();UUID ticket=UUID.randomUUID();var message=established(UUID.randomUUID(),ticket,false);assertThat(consumer.consume(message)).isEqualTo(TicketResultConsumer.Result.ACK);assertThat(jdbc.queryForObject("SELECT status FROM incident_ticket_link",String.class)).isEqualTo("ESTABLISHED");assertThat(AlertRepository.uuid(jdbc.queryForObject("SELECT ticket_id FROM incident_ticket_link",byte[].class))).isEqualTo(ticket);assertThat(jdbc.queryForObject("SELECT state FROM message_recovery WHERE source_record_id=?",String.class,AlertRepository.bytes(outbox))).isEqualTo("RESOLVED");assertThat(count("inbox_message")).isEqualTo(1);assertThat(count("audit_record")).isEqualTo(1);
        assertThat(consumer.consume(message)).isEqualTo(TicketResultConsumer.Result.ACK);assertThat(count("inbox_message")).isEqualTo(1);UUID other=UUID.randomUUID();assertThat(consumer.consume(established(UUID.randomUUID(),other,false))).isEqualTo(TicketResultConsumer.Result.ACK);assertThat(AlertRepository.uuid(jdbc.queryForObject("SELECT ticket_id FROM incident_ticket_link",byte[].class))).isEqualTo(ticket);assertThat(count("inbox_message")).isEqualTo(1);}

    @Test void dlqAcceptanceCanConvergeThroughSameConsumerPath(){UUID ticket=UUID.randomUUID(),messageId=UUID.randomUUID();var dlqMessage=established(messageId,ticket,true);assertThat(dlq.accept(dlqMessage)).isEqualTo(TicketResultConsumer.Result.ACK);assertThat(jdbc.queryForObject("SELECT status FROM inbox_message",String.class)).isEqualTo("RECOVERY_REQUIRED");assertThat(jdbc.queryForObject("SELECT state FROM message_recovery",String.class)).isEqualTo("OPEN");assertThat(consumer.consume(established(messageId,ticket,false))).isEqualTo(TicketResultConsumer.Result.ACK);assertThat(jdbc.queryForObject("SELECT status FROM inbox_message",String.class)).isEqualTo("PROCESSED");assertThat(jdbc.queryForObject("SELECT state FROM message_recovery",String.class)).isEqualTo("RESOLVED");assertThat(jdbc.queryForObject("SELECT status FROM incident_ticket_link",String.class)).isEqualTo("ESTABLISHED");}

    @Test void wrongProducerIsRejectedBeforeTenantEffects(){var good=established(UUID.randomUUID(),UUID.randomUUID(),false);Map<String,String> p=new LinkedHashMap<>(good.properties());p.put("fd_producer","flowdesk-alert-service");var bad=new TicketResultTransportMessage(good.topic(),good.consumerGroup(),good.keys(),good.tags(),good.body(),p);assertThat(consumer.consume(bad)).isEqualTo(TicketResultConsumer.Result.ACK);assertThat(count("inbox_message")).isZero();assertThat(jdbc.queryForObject("SELECT status FROM incident_ticket_link",String.class)).isEqualTo("REQUESTED");}

    @Test void trustedButUncorrelatedResultIsPersistedAsAResultFreeDeterministicRejection(){jdbc.execute("DELETE FROM incident_ticket_link");assertThat(consumer.consume(established(UUID.randomUUID(),UUID.randomUUID(),false))).isEqualTo(TicketResultConsumer.Result.ACK);assertThat(jdbc.queryForObject("SELECT status FROM inbox_message",String.class)).isEqualTo("REJECTED");assertThat(jdbc.queryForObject("SELECT result_type FROM inbox_message",String.class)).isNull();assertThat(jdbc.queryForObject("SELECT result_id FROM inbox_message",byte[].class)).isNull();assertThat(jdbc.queryForObject("SELECT last_error_code FROM inbox_message",String.class)).isEqualTo("INCIDENT_LINK_NOT_FOUND");}

    @Test void v1ResultIgnoresAnAdditiveOptionalPayloadField(){UUID ticket=UUID.randomUUID();assertThat(consumer.consume(established(UUID.randomUUID(),ticket,false,",\"futureOptional\":42"))).isEqualTo(TicketResultConsumer.Result.ACK);assertThat(jdbc.queryForObject("SELECT status FROM incident_ticket_link",String.class)).isEqualTo("ESTABLISHED");assertThat(AlertRepository.uuid(jdbc.queryForObject("SELECT ticket_id FROM incident_ticket_link",byte[].class))).isEqualTo(ticket);}

    private void seedIncident(){
        Instant now=Instant.parse("2026-08-26T01:00:00Z");
        UUID event=UUID.randomUUID(),integration=UUID.randomUUID();
        jdbc.update("INSERT INTO alert_event(tenant_id,alert_event_id,integration_id,external_event_id,event_type,fingerprint,alert_cycle_ref,occurred_at,received_at,disposition,incident_id,raw_body_sha256,raw_body_ref,raw_body_ciphertext,summary,correlation_id,created_at,created_by_type,created_by_id) VALUES(?,?,?,'seed','FIRING','fp','cycle',?,?,'APPLIED',?,?,NULL,NULL,'seed',?,?,'INTEGRATION',?)",
                AlertRepository.bytes(TENANT),AlertRepository.bytes(event),AlertRepository.bytes(integration),
                Timestamp.from(now),Timestamp.from(now),AlertRepository.bytes(incident),new byte[32],
                AlertRepository.bytes(UUID.randomUUID()),Timestamp.from(now),AlertRepository.bytes(integration));
        jdbc.update("INSERT INTO incident(tenant_id,incident_id,fingerprint,generation_no,status,first_event_id,last_event_id,event_count,opened_at,last_event_at,recovered_at,recovery_event_id,recovery_review_state,summary,lock_version,created_at,created_by_type,created_by_id,updated_at,updated_by_type,updated_by_id) VALUES(?,?,'fp',1,'ACTIVE',?,?,1,?,?,NULL,NULL,'NONE','seed',0,?,'INTEGRATION',?,?,'SERVICE',NULL)",
                AlertRepository.bytes(TENANT),AlertRepository.bytes(incident),AlertRepository.bytes(event),
                AlertRepository.bytes(event),Timestamp.from(now),Timestamp.from(now),Timestamp.from(now),
                AlertRepository.bytes(integration),Timestamp.from(now));
        jdbc.update("INSERT INTO incident_ticket_link(tenant_id,incident_id,create_ticket_intent_key,status,ticket_id,requested_at,established_at,recovery_required_at,recovery_reason_code,retry_count,last_attempt_at,lock_version,created_at,created_by_type,created_by_id,updated_at,updated_by_type,updated_by_id) VALUES(?,?,?,'REQUESTED',NULL,?,NULL,NULL,NULL,0,NULL,0,?,'SERVICE',NULL,?,'SERVICE',NULL)",
                AlertRepository.bytes(TENANT),AlertRepository.bytes(incident),createIntent,
                Timestamp.from(now),Timestamp.from(now),Timestamp.from(now));
    }
    private UUID seedCreateRecovery(){Instant now=Instant.parse("2026-08-26T01:00:00Z");UUID outbox=UUID.randomUUID(),message=UUID.randomUUID(),recovery=UUID.randomUUID();byte[] body="{}".getBytes(StandardCharsets.UTF_8),intent=createIntent;jdbc.update("INSERT INTO outbox_event(tenant_id,outbox_id,message_id,publication_key,business_intent_key,aggregate_type,aggregate_id,contract_ref,contract_version,body_bytes,body_sha256,correlation_id,causation_id,status,attempt_count,next_attempt_at,locked_by,locked_at,last_error_code,broker_confirmed_at,lock_version,created_at,created_by_type,created_by_id,updated_at,updated_by_type,updated_by_id) VALUES(?,?,?,?,?,'INCIDENT',?,?,1,?,?,?,NULL,'RECOVERY_REQUIRED',8,?,NULL,NULL,'BROKER_RESULT_UNKNOWN',NULL,0,?,'SERVICE',NULL,?,'SERVICE',NULL)",AlertRepository.bytes(TENANT),AlertRepository.bytes(outbox),AlertRepository.bytes(message),AlertIdempotencyCodec.publicationKey(TENANT,message),intent,AlertRepository.bytes(incident),AlertEventContract.TICKET_CREATE_REQUESTED.contractRef(),body,AlertIdempotencyCodec.sha256(body),AlertRepository.bytes(UUID.randomUUID()),Timestamp.from(now),Timestamp.from(now),Timestamp.from(now));jdbc.update("INSERT INTO message_recovery(tenant_id,recovery_id,direction,source_record_id,business_intent_key,state,attempt_count,next_attempt_at,last_error_code,last_error_summary,dead_lettered_at,recovery_required_at,replay_count,last_replayed_at,last_replayed_by_type,last_replayed_by_id,last_replay_reason,last_replay_correlation_id,resolved_at,lock_version,created_at,updated_at,updated_by_type,updated_by_id) VALUES(?,?,'OUTBOX',?,?,'OPEN',8,NULL,'BROKER_RESULT_UNKNOWN',NULL,NULL,?,0,NULL,NULL,NULL,NULL,NULL,NULL,0,?,?,'SERVICE',NULL)",AlertRepository.bytes(TENANT),AlertRepository.bytes(recovery),AlertRepository.bytes(outbox),intent,Timestamp.from(now),Timestamp.from(now),Timestamp.from(now));return outbox;}
    private TicketResultTransportMessage established(UUID message,UUID ticket,boolean dlqTopic){return established(message,ticket,dlqTopic,"");}
    private TicketResultTransportMessage established(UUID message,UUID ticket,boolean dlqTopic,String additiveFields){byte[] intent=AlertIdempotencyCodec.derivedKey(AlertEventContract.TICKET_ESTABLISHED.intentNamespace(),TENANT,incident);String body="{\"schemaVersion\":\"1\",\"incidentId\":\""+incident+"\",\"createTicketIntentKey\":\""+HexFormat.of().formatHex(createIntent)+"\",\"ticketId\":\""+ticket+"\",\"establishedAt\":\"2026-08-26T01:30:00.000000Z\""+additiveFields+"}";byte[] bytes=body.getBytes(StandardCharsets.UTF_8),hash=AlertIdempotencyCodec.sha256(bytes);var b=AlertEventContract.TICKET_ESTABLISHED;Map<String,String>p=new LinkedHashMap<>();p.put("fd_envelope_version","1");p.put("fd_event_type",b.eventType());p.put("fd_contract_ref",b.contractRef());p.put("fd_contract_version","1");p.put("fd_producer",b.producer());p.put("fd_tenant_id",TENANT.toString());p.put("fd_message_id",message.toString());p.put("fd_business_intent_key",HexFormat.of().formatHex(intent));p.put("fd_aggregate_type","INCIDENT");p.put("fd_aggregate_id",incident.toString());p.put("fd_correlation_id",UUID.randomUUID().toString());p.put("fd_occurred_at","2026-08-26T01:30:00.000000Z");p.put("fd_body_sha256",HexFormat.of().formatHex(hash));return new TicketResultTransportMessage(dlqTopic?b.dlqTopic():b.topic(),dlqTopic?b.dlqAcceptorGroup():b.consumerGroup(),message.toString(),null,bytes,p);}
    private int count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);}private static String resource(String n){try(var in=TicketResultConsumerIntegrationTest.class.getClassLoader().getResourceAsStream(n)){return new String(in.readAllBytes(),StandardCharsets.UTF_8);}catch(Exception e){throw new IllegalStateException(e);}}
}
