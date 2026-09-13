CREATE TABLE monitoring_integration(
 tenant_id BINARY(16) NOT NULL,integration_id BINARY(16) NOT NULL,integration_code VARCHAR(64) NOT NULL,
 integration_name VARCHAR(128) NOT NULL,status VARCHAR(16) NOT NULL,api_key_selector_hash BINARY(32) NOT NULL,
 api_key_verifier VARBINARY(512) NOT NULL,api_key_verifier_scheme VARCHAR(64) NOT NULL,
 signing_scheme_version INT NOT NULL,valid_from TIMESTAMP(6) NOT NULL,valid_until TIMESTAMP(6) NULL,
 PRIMARY KEY(tenant_id,integration_id),UNIQUE(tenant_id,api_key_selector_hash));
CREATE INDEX idx_alt_test_selector ON monitoring_integration(api_key_selector_hash,status,integration_id);

CREATE TABLE webhook_nonce(
 tenant_id BINARY(16) NOT NULL,nonce_id BINARY(16) NOT NULL,integration_id BINARY(16) NOT NULL,
 nonce_hash BINARY(32) NOT NULL,request_timestamp TIMESTAMP(6) NOT NULL,accepted_at TIMESTAMP(6) NOT NULL,
 expires_at TIMESTAMP(6) NOT NULL,created_by_type VARCHAR(16) NOT NULL,created_by_id BINARY(16) NOT NULL,
 PRIMARY KEY(tenant_id,nonce_id),UNIQUE(tenant_id,integration_id,nonce_hash));

CREATE TABLE alert_event(
 tenant_id BINARY(16) NOT NULL,alert_event_id BINARY(16) NOT NULL,integration_id BINARY(16) NOT NULL,
 external_event_id VARCHAR(128) NOT NULL,event_type VARCHAR(16) NOT NULL,fingerprint VARCHAR(255) NOT NULL,
 alert_cycle_ref VARCHAR(128) NOT NULL,occurred_at TIMESTAMP(6) NOT NULL,received_at TIMESTAMP(6) NOT NULL,
 disposition VARCHAR(32) NOT NULL,incident_id BINARY(16) NULL,raw_body_sha256 BINARY(32) NOT NULL,
 raw_body_ref VARCHAR(512) NULL,raw_body_ciphertext BLOB NULL,summary VARCHAR(1000) NULL,
 correlation_id BINARY(16) NOT NULL,created_at TIMESTAMP(6) NOT NULL,created_by_type VARCHAR(16) NOT NULL,
 created_by_id BINARY(16) NOT NULL,PRIMARY KEY(tenant_id,alert_event_id),
 UNIQUE(tenant_id,integration_id,external_event_id));

CREATE TABLE incident(
 tenant_id BINARY(16) NOT NULL,incident_id BINARY(16) NOT NULL,fingerprint VARCHAR(255) NOT NULL,
 generation_no INT NOT NULL,status VARCHAR(16) NOT NULL,first_event_id BINARY(16) NOT NULL,
 last_event_id BINARY(16) NOT NULL,event_count INT NOT NULL,opened_at TIMESTAMP(6) NOT NULL,
 last_event_at TIMESTAMP(6) NOT NULL,recovered_at TIMESTAMP(6) NULL,recovery_event_id BINARY(16) NULL,
 recovery_review_state VARCHAR(16) NOT NULL,summary VARCHAR(1000) NULL,lock_version BIGINT NOT NULL,
 created_at TIMESTAMP(6) NOT NULL,created_by_type VARCHAR(16) NOT NULL,created_by_id BINARY(16) NOT NULL,
 updated_at TIMESTAMP(6) NOT NULL,updated_by_type VARCHAR(16) NOT NULL,updated_by_id BINARY(16) NULL,
 active_fingerprint_guard VARCHAR(255) GENERATED ALWAYS AS
   (CASE WHEN status='ACTIVE' THEN fingerprint ELSE NULL END),
 PRIMARY KEY(tenant_id,incident_id),UNIQUE(tenant_id,fingerprint,generation_no),
 UNIQUE(tenant_id,active_fingerprint_guard));

CREATE TABLE incident_ticket_link(
 tenant_id BINARY(16) NOT NULL,incident_id BINARY(16) NOT NULL,create_ticket_intent_key BINARY(32) NOT NULL,
 status VARCHAR(32) NOT NULL,ticket_id BINARY(16) NULL,requested_at TIMESTAMP(6) NOT NULL,
 established_at TIMESTAMP(6) NULL,recovery_required_at TIMESTAMP(6) NULL,recovery_reason_code VARCHAR(96) NULL,
 retry_count INT NOT NULL,last_attempt_at TIMESTAMP(6) NULL,lock_version BIGINT NOT NULL,
 created_at TIMESTAMP(6) NOT NULL,created_by_type VARCHAR(16) NOT NULL,created_by_id BINARY(16) NULL,
 updated_at TIMESTAMP(6) NOT NULL,updated_by_type VARCHAR(16) NOT NULL,updated_by_id BINARY(16) NULL,
 PRIMARY KEY(tenant_id,incident_id),UNIQUE(tenant_id,create_ticket_intent_key));

CREATE TABLE outbox_event(
 tenant_id BINARY(16) NOT NULL,outbox_id BINARY(16) NOT NULL,message_id BINARY(16) NOT NULL,
 publication_key BINARY(32) NOT NULL,business_intent_key BINARY(32) NOT NULL,aggregate_type VARCHAR(48) NOT NULL,
 aggregate_id BINARY(16) NOT NULL,contract_ref VARCHAR(128) NOT NULL,contract_version INT NOT NULL,
 body_bytes BLOB NOT NULL,body_sha256 BINARY(32) NOT NULL,correlation_id BINARY(16) NOT NULL,
 causation_id BINARY(16) NULL,status VARCHAR(32) NOT NULL,attempt_count INT NOT NULL,
 next_attempt_at TIMESTAMP(6) NOT NULL,locked_by VARCHAR(96) NULL,locked_at TIMESTAMP(6) NULL,
 last_error_code VARCHAR(96) NULL,broker_confirmed_at TIMESTAMP(6) NULL,lock_version BIGINT NOT NULL,
 created_at TIMESTAMP(6) NOT NULL,created_by_type VARCHAR(16) NOT NULL,created_by_id BINARY(16) NULL,
 updated_at TIMESTAMP(6) NOT NULL,updated_by_type VARCHAR(16) NOT NULL,updated_by_id BINARY(16) NULL,
 PRIMARY KEY(tenant_id,outbox_id),UNIQUE(tenant_id,message_id),UNIQUE(tenant_id,publication_key));

CREATE TABLE inbox_message(
 tenant_id BINARY(16) NOT NULL,inbox_id BINARY(16) NOT NULL,producer_service VARCHAR(64) NOT NULL,
 message_id BINARY(16) NOT NULL,business_intent_key BINARY(32) NOT NULL,contract_ref VARCHAR(128) NOT NULL,
 contract_version INT NOT NULL,body_sha256 BINARY(32) NOT NULL,body_bytes BLOB NOT NULL,
 correlation_id BINARY(16) NOT NULL,causation_id BINARY(16) NULL,occurred_at TIMESTAMP(6) NOT NULL,
 status VARCHAR(32) NOT NULL,result_type VARCHAR(48) NULL,result_id BINARY(16) NULL,attempt_count INT NOT NULL,
 last_error_code VARCHAR(96) NULL,first_received_at TIMESTAMP(6) NOT NULL,last_attempt_at TIMESTAMP(6) NOT NULL,
 processed_at TIMESTAMP(6) NULL,lock_version BIGINT NOT NULL,created_at TIMESTAMP(6) NOT NULL,
 updated_at TIMESTAMP(6) NOT NULL,updated_by_type VARCHAR(16) NOT NULL,updated_by_id BINARY(16) NULL,
 PRIMARY KEY(tenant_id,inbox_id),UNIQUE(tenant_id,producer_service,message_id),
 UNIQUE(tenant_id,producer_service,contract_ref,business_intent_key));

CREATE TABLE audit_record(
 tenant_id BINARY(16) NOT NULL,audit_id BINARY(16) NOT NULL,action_code VARCHAR(96) NOT NULL,
 target_type VARCHAR(48) NOT NULL,target_id BINARY(16) NULL,outcome VARCHAR(16) NOT NULL,
 actor_type VARCHAR(16) NOT NULL,actor_id BINARY(16) NULL,reason VARCHAR(1000) NULL,
 error_code VARCHAR(96) NULL,correlation_id BINARY(16) NOT NULL,causation_id BINARY(16) NULL,
 occurred_at TIMESTAMP(6) NOT NULL,recorded_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(tenant_id,audit_id));

CREATE TABLE message_recovery(
 tenant_id BINARY(16) NOT NULL,recovery_id BINARY(16) NOT NULL,direction VARCHAR(16) NOT NULL,
 source_record_id BINARY(16) NOT NULL,business_intent_key BINARY(32) NOT NULL,state VARCHAR(32) NOT NULL,
 attempt_count INT NOT NULL,next_attempt_at TIMESTAMP(6) NULL,last_error_code VARCHAR(96) NOT NULL,
 last_error_summary VARCHAR(1000) NULL,dead_lettered_at TIMESTAMP(6) NULL,recovery_required_at TIMESTAMP(6) NOT NULL,
 replay_count INT NOT NULL,last_replayed_at TIMESTAMP(6) NULL,last_replayed_by_type VARCHAR(16) NULL,
 last_replayed_by_id BINARY(16) NULL,last_replay_reason VARCHAR(1000) NULL,last_replay_correlation_id BINARY(16) NULL,
 resolved_at TIMESTAMP(6) NULL,lock_version BIGINT NOT NULL,created_at TIMESTAMP(6) NOT NULL,
 updated_at TIMESTAMP(6) NOT NULL,updated_by_type VARCHAR(16) NOT NULL,updated_by_id BINARY(16) NULL,
 PRIMARY KEY(tenant_id,recovery_id),UNIQUE(tenant_id,direction,source_record_id));
