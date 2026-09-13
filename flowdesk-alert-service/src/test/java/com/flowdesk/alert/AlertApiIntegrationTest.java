package com.flowdesk.alert;

import com.flowdesk.alert.repository.AlertRepository;
import com.flowdesk.alert.service.AlertIdempotencyCodec;
import com.flowdesk.common.authz.PermissionCode;
import com.flowdesk.common.authz.RoleCode;
import com.flowdesk.common.security.JwtIssuer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties={
        "spring.cloud.service-registry.auto-registration.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:alert-api;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=",
        "spring.sql.init.mode=always","spring.sql.init.schema-locations=classpath:schema-test.sql","FLOWDESK_ALERT_DB_PASSWORD="
})
@AutoConfigureMockMvc
class AlertApiIntegrationTest {
    private static final UUID TENANT_A=UUID.fromString("41111111-1111-4111-8111-111111111111"),TENANT_B=UUID.fromString("42222222-2222-4222-8222-222222222222"),ADMIN=UUID.fromString("43333333-3333-4333-8333-333333333333");
    private static final KeyPair KEY=newKeyPair();private static final JwtIssuer ISSUER=new JwtIssuer(KEY.getPrivate(),"flowdesk-iam-service","flowdesk-api","flowdesk-internal",900,300);
    @Autowired MockMvc mvc;@Autowired JdbcTemplate jdbc;private UUID eventA,incidentA,eventB,incidentB;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("flowdesk.security.jwt.public-key-pem",AlertApiIntegrationTest::publicKeyPem);r.add("flowdesk.security.cursor-token.hmac-key",()->Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)));}
    @BeforeEach void reset(){for(String table:new String[]{"message_recovery","audit_record","inbox_message","outbox_event","incident_ticket_link","incident","alert_event","webhook_nonce","monitoring_integration"})jdbc.execute("DELETE FROM "+table);var a=seed(TENANT_A,"A","fp-a",1);eventA=a.event();incidentA=a.incident();seed(TENANT_A,"A2","fp-a2",1);var b=seed(TENANT_B,"B","fp-b",1);eventB=b.event();incidentB=b.incident();}
    @Test void exposesApi050Through054WithTenantIsolationAndOpaquePayloads()throws Exception{String auth="Bearer "+token();mvc.perform(get("/api/v1/alert-events").header("Authorization",auth)).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(2)).andExpect(jsonPath("$.data[0].rawBodySha256").isString()).andExpect(jsonPath("$.data[0].rawBodyRef").doesNotExist());mvc.perform(get("/api/v1/alert-events/"+eventA).header("Authorization",auth)).andExpect(status().isOk()).andExpect(jsonPath("$.data.alertEventId").value(eventA.toString()));mvc.perform(get("/api/v1/incidents").header("Authorization",auth).param("status","ACTIVE")).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(2));mvc.perform(get("/api/v1/incidents/"+incidentA).header("Authorization",auth)).andExpect(status().isOk()).andExpect(header().string("ETag","\"lv:0\""));mvc.perform(get("/api/v1/incidents/"+incidentA+"/ticket-link").header("Authorization",auth)).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("REQUESTED")).andExpect(jsonPath("$.data.createTicketIntentKey").doesNotExist());mvc.perform(get("/api/v1/alert-events/"+eventB).header("Authorization",auth)).andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));mvc.perform(get("/api/v1/incidents/"+incidentB).header("Authorization",auth)).andExpect(status().isNotFound());mvc.perform(get("/api/v1/alert-events").header("Authorization",auth).param("unknown","x")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("UNKNOWN_QUERY_PARAMETER"));}
    private Seed seed(UUID tenant,String code,String fingerprint,int generation){Instant now=Instant.parse("2026-08-26T01:00:00Z").plusSeconds(generation);UUID integration=UUID.randomUUID(),event=UUID.randomUUID(),incident=UUID.randomUUID();jdbc.update("INSERT INTO monitoring_integration(tenant_id,integration_id,integration_code,integration_name,status,api_key_selector_hash,api_key_verifier,api_key_verifier_scheme,signing_scheme_version,valid_from,valid_until) VALUES(?,?,?,?,'ACTIVE',?,?,'FD_API_KEY_SHA256_V1',1,?,NULL)",AlertRepository.bytes(tenant),AlertRepository.bytes(integration),code,code,AlertIdempotencyCodec.sha256((code+"selector").getBytes(StandardCharsets.UTF_8)),new byte[32],Timestamp.from(now));jdbc.update("INSERT INTO alert_event(tenant_id,alert_event_id,integration_id,external_event_id,event_type,fingerprint,alert_cycle_ref,occurred_at,received_at,disposition,incident_id,raw_body_sha256,raw_body_ref,raw_body_ciphertext,summary,correlation_id,created_at,created_by_type,created_by_id) VALUES(?,?,?,?,'FIRING',?,'cycle',?,?,'APPLIED',?,?,NULL,NULL,'seed',?,?,'INTEGRATION',?)",AlertRepository.bytes(tenant),AlertRepository.bytes(event),AlertRepository.bytes(integration),"evt-"+code,fingerprint,Timestamp.from(now),Timestamp.from(now),AlertRepository.bytes(incident),new byte[32],AlertRepository.bytes(UUID.randomUUID()),Timestamp.from(now),AlertRepository.bytes(integration));jdbc.update("INSERT INTO incident(tenant_id,incident_id,fingerprint,generation_no,status,first_event_id,last_event_id,event_count,opened_at,last_event_at,recovered_at,recovery_event_id,recovery_review_state,summary,lock_version,created_at,created_by_type,created_by_id,updated_at,updated_by_type,updated_by_id) VALUES(?,?,?,?,'ACTIVE',?,?,1,?,?,NULL,NULL,'NONE','seed',0,?,'INTEGRATION',?,?,'INTEGRATION',?)",AlertRepository.bytes(tenant),AlertRepository.bytes(incident),fingerprint,generation,AlertRepository.bytes(event),AlertRepository.bytes(event),Timestamp.from(now),Timestamp.from(now),Timestamp.from(now),AlertRepository.bytes(integration),Timestamp.from(now),AlertRepository.bytes(integration));byte[] intent=AlertIdempotencyCodec.derivedKey("FD-INCIDENT-TICKET-CREATE-REQUESTED-V1",tenant,incident);jdbc.update("INSERT INTO incident_ticket_link(tenant_id,incident_id,create_ticket_intent_key,status,ticket_id,requested_at,established_at,recovery_required_at,recovery_reason_code,retry_count,last_attempt_at,lock_version,created_at,created_by_type,created_by_id,updated_at,updated_by_type,updated_by_id) VALUES(?,?,?,'REQUESTED',NULL,?,NULL,NULL,NULL,0,NULL,0,?,'SERVICE',NULL,?,'SERVICE',NULL)",AlertRepository.bytes(tenant),AlertRepository.bytes(incident),intent,Timestamp.from(now),Timestamp.from(now),Timestamp.from(now));return new Seed(event,incident);}
    private static String token(){return ISSUER.issueHuman(TENANT_A,ADMIN,Set.of(RoleCode.SERVICE_DESK_ADMIN),Set.of(PermissionCode.ALERT_EVENT_READ_TENANT,PermissionCode.INCIDENT_READ_TENANT)).token();}private static KeyPair newKeyPair(){try{return KeyPairGenerator.getInstance("RSA").generateKeyPair();}catch(Exception e){throw new IllegalStateException(e);}}private static String publicKeyPem(){String nl=System.lineSeparator();return "-----BEGIN PUBLIC KEY-----"+nl+Base64.getEncoder().encodeToString(KEY.getPublic().getEncoded())+nl+"-----END PUBLIC KEY-----";}private record Seed(UUID event,UUID incident){}
}
