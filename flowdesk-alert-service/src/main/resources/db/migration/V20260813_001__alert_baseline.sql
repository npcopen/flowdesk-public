-- FlowDesk Alert schema baseline for MySQL 8.4.x.
-- The connection default schema is flowdesk_alert. No cross-schema object is referenced.

CREATE TABLE monitoring_integration (
    tenant_id BINARY(16) NOT NULL,
    integration_id BINARY(16) NOT NULL,
    integration_code VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    integration_name VARCHAR(128) NOT NULL,
    status VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'ACTIVE',
    api_key_selector_hash BINARY(32) NOT NULL,
    api_key_verifier VARBINARY(512) NOT NULL,
    api_key_verifier_scheme VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    signing_scheme_version INT UNSIGNED NOT NULL,
    hmac_secret_ref VARCHAR(512) NULL DEFAULT NULL,
    hmac_secret_ciphertext VARBINARY(4096) NULL DEFAULT NULL,
    credential_storage_mode VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL DEFAULT NULL,
    secret_material_version INT UNSIGNED NOT NULL DEFAULT 1,
    valid_from TIMESTAMP(6) NOT NULL,
    valid_until TIMESTAMP(6) NULL DEFAULT NULL,
    lock_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by_type VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    created_by_id BINARY(16) NULL DEFAULT NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_by_type VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    updated_by_id BINARY(16) NULL DEFAULT NULL,

    -- IDX-ALT-01
    CONSTRAINT pk_alt_monitoring_integration PRIMARY KEY (tenant_id, integration_id),
    -- IDX-ALT-02
    CONSTRAINT uk_alt_integration_code UNIQUE KEY idx_alt_02 (tenant_id, integration_code),
    -- IDX-ALT-03
    CONSTRAINT uk_alt_integration_selector UNIQUE KEY idx_alt_03
        (tenant_id, api_key_selector_hash),
    -- IDX-ALT-04
    KEY idx_alt_04 (api_key_selector_hash, status, integration_id),
    -- IDX-ALT-05
    KEY idx_alt_05 (tenant_id, status, integration_name, integration_id),

    CONSTRAINT ck_alt_integration_status_validity CHECK (
        status IN ('ACTIVE', 'INACTIVE')
        AND OCTET_LENGTH(api_key_verifier) > 0
        AND CHAR_LENGTH(api_key_verifier_scheme) > 0
        AND signing_scheme_version >= 1
        AND secret_material_version >= 1
        AND (valid_until IS NULL OR valid_until > valid_from)
    ),
    -- OPEN-005 remains deliberately open: neither storage representation may contain material.
    CONSTRAINT ck_alt_integration_secret_placeholders CHECK (
        hmac_secret_ref IS NULL
        AND hmac_secret_ciphertext IS NULL
        AND credential_storage_mode IS NULL
    ),
    CONSTRAINT ck_alt_integration_actor CHECK (
        created_by_type IN ('USER', 'SERVICE', 'SCHEDULER')
        AND updated_by_type IN ('USER', 'SERVICE', 'SCHEDULER')
    )
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE webhook_nonce (
    tenant_id BINARY(16) NOT NULL,
    nonce_id BINARY(16) NOT NULL,
    integration_id BINARY(16) NOT NULL,
    nonce_hash BINARY(32) NOT NULL,
    request_timestamp TIMESTAMP(6) NOT NULL,
    accepted_at TIMESTAMP(6) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    created_by_type VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'INTEGRATION',
    created_by_id BINARY(16) NOT NULL,

    -- IDX-ALT-06
    CONSTRAINT pk_alt_webhook_nonce PRIMARY KEY (tenant_id, nonce_id),
    -- IDX-ALT-07 also supports fk_alt_nonce_integration.
    CONSTRAINT uk_alt_nonce_replay UNIQUE KEY idx_alt_07
        (tenant_id, integration_id, nonce_hash),
    -- IDX-ALT-08
    KEY idx_alt_08 (tenant_id, expires_at, nonce_id),

    CONSTRAINT fk_alt_nonce_integration FOREIGN KEY (tenant_id, integration_id)
        REFERENCES monitoring_integration (tenant_id, integration_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT ck_alt_nonce_times_actor CHECK (
        expires_at > accepted_at
        AND created_by_type = 'INTEGRATION'
        AND created_by_id = integration_id
    )
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE alert_event (
    tenant_id BINARY(16) NOT NULL,
    alert_event_id BINARY(16) NOT NULL,
    integration_id BINARY(16) NOT NULL,
    external_event_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    event_type VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    fingerprint VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    alert_cycle_ref VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL DEFAULT NULL,
    occurred_at TIMESTAMP(6) NOT NULL,
    received_at TIMESTAMP(6) NOT NULL,
    disposition VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    incident_id BINARY(16) NULL DEFAULT NULL,
    raw_body_sha256 BINARY(32) NOT NULL,
    raw_body_ref VARCHAR(512) NULL DEFAULT NULL,
    raw_body_ciphertext LONGBLOB NULL DEFAULT NULL,
    summary VARCHAR(1000) NULL DEFAULT NULL,
    correlation_id BINARY(16) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by_type VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'INTEGRATION',
    created_by_id BINARY(16) NOT NULL,

    -- IDX-ALT-09
    CONSTRAINT pk_alt_alert_event PRIMARY KEY (tenant_id, alert_event_id),
    -- IDX-ALT-10 also supports fk_alt_event_integration.
    CONSTRAINT uk_alt_event_external_id UNIQUE KEY idx_alt_10
        (tenant_id, integration_id, external_event_id),
    -- IDX-ALT-11
    KEY idx_alt_11 (tenant_id, fingerprint, received_at, alert_event_id),
    -- IDX-ALT-12
    KEY idx_alt_12 (tenant_id, disposition, received_at, alert_event_id),
    -- IDX-ALT-13; incident_id is deliberately a logical reference, not an FK.
    KEY idx_alt_13 (tenant_id, incident_id, occurred_at, alert_event_id),

    CONSTRAINT fk_alt_event_integration FOREIGN KEY (tenant_id, integration_id)
        REFERENCES monitoring_integration (tenant_id, integration_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT ck_alt_event_type_disposition CHECK (
        event_type IN ('FIRING', 'RESOLVED')
        AND disposition IN ('APPLIED', 'LATE_OLD_GENERATION', 'REVIEW_REQUIRED')
        AND CHAR_LENGTH(external_event_id) > 0
        AND CHAR_LENGTH(fingerprint) > 0
        AND created_by_type = 'INTEGRATION'
        AND created_by_id = integration_id
        AND (
            (disposition = 'REVIEW_REQUIRED' AND incident_id IS NULL)
            OR (disposition IN ('APPLIED', 'LATE_OLD_GENERATION') AND incident_id IS NOT NULL)
        )
    ),
    -- DB-OPEN-001 remains deliberately open: only the digest is persisted.
    CONSTRAINT ck_alt_event_raw_placeholders CHECK (
        raw_body_ref IS NULL AND raw_body_ciphertext IS NULL
    )
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE incident (
    tenant_id BINARY(16) NOT NULL,
    incident_id BINARY(16) NOT NULL,
    fingerprint VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    generation_no INT UNSIGNED NOT NULL,
    status VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'ACTIVE',
    active_fingerprint_guard VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin
        GENERATED ALWAYS AS (
            CASE WHEN status = 'ACTIVE' THEN fingerprint ELSE NULL END
        ) STORED,
    first_event_id BINARY(16) NOT NULL,
    last_event_id BINARY(16) NOT NULL,
    event_count INT UNSIGNED NOT NULL DEFAULT 1,
    opened_at TIMESTAMP(6) NOT NULL,
    last_event_at TIMESTAMP(6) NOT NULL,
    recovered_at TIMESTAMP(6) NULL DEFAULT NULL,
    recovery_event_id BINARY(16) NULL DEFAULT NULL,
    recovery_review_state VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'NONE',
    summary VARCHAR(1000) NULL DEFAULT NULL,
    lock_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by_type VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'INTEGRATION',
    created_by_id BINARY(16) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_by_type VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    updated_by_id BINARY(16) NULL DEFAULT NULL,

    -- IDX-ALT-14
    CONSTRAINT pk_alt_incident PRIMARY KEY (tenant_id, incident_id),
    -- IDX-ALT-15
    CONSTRAINT uk_alt_incident_generation UNIQUE KEY idx_alt_15
        (tenant_id, fingerprint, generation_no),
    -- IDX-ALT-16
    CONSTRAINT uk_alt_incident_active UNIQUE KEY idx_alt_16
        (tenant_id, active_fingerprint_guard),
    -- IDX-ALT-17
    KEY idx_alt_17 (tenant_id, status, updated_at, incident_id),
    -- IDX-ALT-18
    KEY idx_alt_18 (tenant_id, fingerprint, created_at, incident_id),

    CONSTRAINT ck_alt_incident_status_recovery CHECK (
        status IN ('ACTIVE', 'RECOVERED')
        AND recovery_review_state IN ('NONE', 'REVIEW_REQUIRED')
        AND (
            (status = 'ACTIVE' AND recovered_at IS NULL AND recovery_event_id IS NULL)
            OR (status = 'RECOVERED'
                AND recovered_at IS NOT NULL
                AND recovered_at >= opened_at
                AND recovery_event_id IS NOT NULL)
        )
    ),
    CONSTRAINT ck_alt_incident_generation_count CHECK (
        generation_no >= 1
        AND event_count >= 1
        AND CHAR_LENGTH(fingerprint) > 0
        AND last_event_at >= opened_at
    ),
    CONSTRAINT ck_alt_incident_actor CHECK (
        created_by_type = 'INTEGRATION'
        AND updated_by_type IN ('INTEGRATION', 'SERVICE')
    )
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE incident_ticket_link (
    tenant_id BINARY(16) NOT NULL,
    incident_id BINARY(16) NOT NULL,
    create_ticket_intent_key BINARY(32) NOT NULL,
    status VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'REQUESTED',
    ticket_id BINARY(16) NULL DEFAULT NULL,
    requested_at TIMESTAMP(6) NOT NULL,
    established_at TIMESTAMP(6) NULL DEFAULT NULL,
    recovery_required_at TIMESTAMP(6) NULL DEFAULT NULL,
    recovery_reason_code VARCHAR(96) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL DEFAULT NULL,
    retry_count INT UNSIGNED NOT NULL DEFAULT 0,
    last_attempt_at TIMESTAMP(6) NULL DEFAULT NULL,
    lock_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by_type VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'SERVICE',
    created_by_id BINARY(16) NULL DEFAULT NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_by_type VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'SERVICE',
    updated_by_id BINARY(16) NULL DEFAULT NULL,

    -- IDX-ALT-19 also supports fk_alt_ticket_link_incident.
    CONSTRAINT pk_alt_incident_ticket_link PRIMARY KEY (tenant_id, incident_id),
    -- IDX-ALT-20
    CONSTRAINT uk_alt_ticket_link_intent UNIQUE KEY idx_alt_20
        (tenant_id, create_ticket_intent_key),
    -- IDX-ALT-21
    KEY idx_alt_21 (tenant_id, ticket_id, established_at, incident_id),
    -- IDX-ALT-22
    KEY idx_alt_22 (tenant_id, status, updated_at, incident_id),

    CONSTRAINT fk_alt_ticket_link_incident FOREIGN KEY (tenant_id, incident_id)
        REFERENCES incident (tenant_id, incident_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT ck_alt_ticket_link_state_times CHECK (
        status IN ('REQUESTED', 'ESTABLISHED', 'RECOVERY_REQUIRED')
        AND (
            (status = 'REQUESTED'
                AND ticket_id IS NULL
                AND established_at IS NULL
                AND (
                    (recovery_required_at IS NULL AND recovery_reason_code IS NULL)
                    OR (recovery_required_at IS NOT NULL
                        AND recovery_required_at >= requested_at
                        AND recovery_reason_code IS NOT NULL)
                ))
            OR (status = 'ESTABLISHED'
                AND ticket_id IS NOT NULL
                AND established_at IS NOT NULL
                AND established_at >= requested_at
                AND (
                    (recovery_required_at IS NULL AND recovery_reason_code IS NULL)
                    OR (recovery_required_at IS NOT NULL
                        AND recovery_required_at >= requested_at
                        AND recovery_reason_code IS NOT NULL)
                ))
            OR (status = 'RECOVERY_REQUIRED'
                AND ticket_id IS NULL
                AND established_at IS NULL
                AND recovery_required_at IS NOT NULL
                AND recovery_required_at >= requested_at
                AND recovery_reason_code IS NOT NULL)
        )
        AND created_by_type = 'SERVICE'
        AND updated_by_type IN ('SERVICE', 'USER', 'SCHEDULER')
    ),
    CONSTRAINT ck_alt_ticket_link_attempts CHECK (
        (
            (retry_count = 0 AND last_attempt_at IS NULL)
            OR (retry_count > 0 AND last_attempt_at IS NOT NULL AND last_attempt_at >= requested_at)
        )
        AND (status <> 'RECOVERY_REQUIRED' OR retry_count > 0)
    )
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE outbox_event (
    tenant_id BINARY(16) NOT NULL,
    outbox_id BINARY(16) NOT NULL,
    message_id BINARY(16) NOT NULL,
    publication_key BINARY(32) NOT NULL,
    business_intent_key BINARY(32) NOT NULL,
    aggregate_type VARCHAR(48) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    aggregate_id BINARY(16) NOT NULL,
    contract_ref VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    contract_version INT UNSIGNED NOT NULL,
    body_bytes LONGBLOB NOT NULL,
    body_sha256 BINARY(32) NOT NULL,
    correlation_id BINARY(16) NOT NULL,
    causation_id BINARY(16) NULL DEFAULT NULL,
    status VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'PENDING',
    attempt_count INT UNSIGNED NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    locked_by VARCHAR(96) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL DEFAULT NULL,
    locked_at TIMESTAMP(6) NULL DEFAULT NULL,
    last_error_code VARCHAR(96) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL DEFAULT NULL,
    broker_confirmed_at TIMESTAMP(6) NULL DEFAULT NULL,
    lock_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by_type VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'INTEGRATION',
    created_by_id BINARY(16) NULL DEFAULT NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_by_type VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'SERVICE',
    updated_by_id BINARY(16) NULL DEFAULT NULL,

    -- IDX-ALT-23
    CONSTRAINT pk_alt_outbox_event PRIMARY KEY (tenant_id, outbox_id),
    -- IDX-ALT-24
    CONSTRAINT uk_alt_outbox_message UNIQUE KEY idx_alt_24 (tenant_id, message_id),
    -- IDX-ALT-25
    CONSTRAINT uk_alt_outbox_publication UNIQUE KEY idx_alt_25 (tenant_id, publication_key),
    -- IDX-ALT-26
    KEY idx_alt_26 (tenant_id, status, next_attempt_at, outbox_id),
    -- IDX-ALT-27
    KEY idx_alt_27 (tenant_id, aggregate_type, aggregate_id, created_at, outbox_id),

    CONSTRAINT ck_alt_outbox_state_times CHECK (
        status IN ('PENDING', 'PUBLISHING', 'PUBLISHED', 'RECOVERY_REQUIRED')
        AND contract_version >= 1
        AND OCTET_LENGTH(body_bytes) > 0
        AND created_by_type IN ('INTEGRATION', 'SERVICE')
        AND updated_by_type IN ('USER', 'SERVICE', 'SCHEDULER')
        AND (
            (status = 'PUBLISHED' AND broker_confirmed_at IS NOT NULL)
            OR (status <> 'PUBLISHED' AND broker_confirmed_at IS NULL)
        )
        AND (broker_confirmed_at IS NULL OR broker_confirmed_at >= created_at)
    ),
    CONSTRAINT ck_alt_outbox_attempt_lease CHECK (
        (
            (status = 'PUBLISHING'
                AND attempt_count >= 1
                AND locked_by IS NOT NULL
                AND CHAR_LENGTH(locked_by) > 0
                AND locked_at IS NOT NULL)
            OR (status <> 'PUBLISHING' AND locked_by IS NULL AND locked_at IS NULL)
        )
        AND (locked_at IS NULL OR locked_at >= created_at)
        AND next_attempt_at >= created_at
    )
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE inbox_message (
    tenant_id BINARY(16) NOT NULL,
    inbox_id BINARY(16) NOT NULL,
    producer_service VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    message_id BINARY(16) NOT NULL,
    business_intent_key BINARY(32) NOT NULL,
    contract_ref VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    contract_version INT UNSIGNED NOT NULL,
    body_sha256 BINARY(32) NOT NULL,
    status VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    result_type VARCHAR(48) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL DEFAULT NULL,
    result_id BINARY(16) NULL DEFAULT NULL,
    attempt_count INT UNSIGNED NOT NULL DEFAULT 1,
    last_error_code VARCHAR(96) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL DEFAULT NULL,
    first_received_at TIMESTAMP(6) NOT NULL,
    last_attempt_at TIMESTAMP(6) NOT NULL,
    processed_at TIMESTAMP(6) NULL DEFAULT NULL,
    lock_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_by_type VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'SERVICE',
    updated_by_id BINARY(16) NULL DEFAULT NULL,

    -- IDX-ALT-28
    CONSTRAINT pk_alt_inbox_message PRIMARY KEY (tenant_id, inbox_id),
    -- IDX-ALT-29
    CONSTRAINT uk_alt_inbox_message_id UNIQUE KEY idx_alt_29
        (tenant_id, producer_service, message_id),
    -- IDX-ALT-30
    CONSTRAINT uk_alt_inbox_business_intent UNIQUE KEY idx_alt_30
        (tenant_id, producer_service, contract_ref, business_intent_key),
    -- IDX-ALT-31
    KEY idx_alt_31 (tenant_id, status, updated_at, inbox_id),
    -- IDX-ALT-32
    KEY idx_alt_32 (tenant_id, result_type, result_id, processed_at, inbox_id),

    CONSTRAINT ck_alt_inbox_state_result CHECK (
        status IN ('PROCESSED', 'REJECTED', 'RECOVERY_REQUIRED')
        AND (
            (status = 'PROCESSED'
                AND processed_at IS NOT NULL
                AND result_type IS NOT NULL
                AND result_id IS NOT NULL)
            OR (status = 'REJECTED'
                AND processed_at IS NOT NULL
                AND result_type IS NULL
                AND result_id IS NULL)
            OR (status = 'RECOVERY_REQUIRED'
                AND processed_at IS NULL
                AND result_type IS NULL
                AND result_id IS NULL)
        )
    ),
    CONSTRAINT ck_alt_inbox_attempt_times CHECK (
        producer_service = 'flowdesk-ticket-service'
        AND contract_version >= 1
        AND attempt_count >= 1
        AND last_attempt_at >= first_received_at
        AND (processed_at IS NULL OR processed_at >= first_received_at)
        AND updated_by_type IN ('USER', 'SERVICE', 'SCHEDULER')
        AND (updated_by_type <> 'USER' OR updated_by_id IS NOT NULL)
    )
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE audit_record (
    tenant_id BINARY(16) NOT NULL,
    audit_id BINARY(16) NOT NULL,
    action_code VARCHAR(96) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    target_type VARCHAR(48) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    target_id BINARY(16) NULL DEFAULT NULL,
    outcome VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    actor_type VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    actor_id BINARY(16) NULL DEFAULT NULL,
    reason VARCHAR(1000) NULL DEFAULT NULL,
    error_code VARCHAR(96) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL DEFAULT NULL,
    correlation_id BINARY(16) NOT NULL,
    causation_id BINARY(16) NULL DEFAULT NULL,
    occurred_at TIMESTAMP(6) NOT NULL,
    recorded_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    -- IDX-ALT-33
    CONSTRAINT pk_alt_audit_record PRIMARY KEY (tenant_id, audit_id),
    -- IDX-ALT-34
    KEY idx_alt_34 (tenant_id, target_type, target_id, occurred_at, audit_id),
    -- IDX-ALT-35
    KEY idx_alt_35 (tenant_id, actor_type, actor_id, occurred_at, audit_id),
    -- IDX-ALT-36
    KEY idx_alt_36 (tenant_id, correlation_id, occurred_at, audit_id),

    CONSTRAINT ck_alt_audit_outcome_actor CHECK (
        outcome IN ('SUCCEEDED', 'REJECTED', 'FAILED')
        AND actor_type IN ('INTEGRATION', 'SERVICE', 'USER', 'SCHEDULER')
        AND CHAR_LENGTH(action_code) > 0
        AND CHAR_LENGTH(target_type) > 0
    )
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE message_recovery (
    tenant_id BINARY(16) NOT NULL,
    recovery_id BINARY(16) NOT NULL,
    direction VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    source_record_id BINARY(16) NOT NULL,
    business_intent_key BINARY(32) NOT NULL,
    state VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'OPEN',
    attempt_count INT UNSIGNED NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP(6) NULL DEFAULT NULL,
    last_error_code VARCHAR(96) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    last_error_summary VARCHAR(1000) NULL DEFAULT NULL,
    dead_lettered_at TIMESTAMP(6) NULL DEFAULT NULL,
    recovery_required_at TIMESTAMP(6) NOT NULL,
    replay_count INT UNSIGNED NOT NULL DEFAULT 0,
    last_replayed_at TIMESTAMP(6) NULL DEFAULT NULL,
    last_replayed_by_type VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL DEFAULT NULL,
    last_replayed_by_id BINARY(16) NULL DEFAULT NULL,
    last_replay_reason VARCHAR(1000) NULL DEFAULT NULL,
    resolved_at TIMESTAMP(6) NULL DEFAULT NULL,
    lock_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_by_type VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'SERVICE',
    updated_by_id BINARY(16) NULL DEFAULT NULL,

    -- IDX-ALT-37
    CONSTRAINT pk_alt_message_recovery PRIMARY KEY (tenant_id, recovery_id),
    -- IDX-ALT-38
    CONSTRAINT uk_alt_recovery_source UNIQUE KEY idx_alt_38
        (tenant_id, direction, source_record_id),
    -- IDX-ALT-39
    KEY idx_alt_39 (tenant_id, state, next_attempt_at, recovery_id),
    -- IDX-ALT-40
    KEY idx_alt_40 (tenant_id, dead_lettered_at, recovery_id),

    CONSTRAINT ck_alt_recovery_state_times CHECK (
        direction IN ('OUTBOX', 'INBOX')
        AND state IN ('OPEN', 'REPLAYING', 'RESOLVED')
        AND CHAR_LENGTH(last_error_code) > 0
        AND (dead_lettered_at IS NULL OR dead_lettered_at <= recovery_required_at)
        AND (next_attempt_at IS NULL OR next_attempt_at >= recovery_required_at)
        AND (state = 'OPEN' OR next_attempt_at IS NULL)
        AND (
            (replay_count = 0
                AND last_replayed_at IS NULL
                AND last_replayed_by_type IS NULL
                AND last_replayed_by_id IS NULL
                AND last_replay_reason IS NULL)
            OR (replay_count > 0
                AND last_replayed_at IS NOT NULL
                AND last_replayed_at >= recovery_required_at
                AND last_replayed_by_type IN ('USER', 'SERVICE', 'SCHEDULER')
                AND (last_replayed_by_type <> 'USER' OR last_replayed_by_id IS NOT NULL)
                AND last_replay_reason IS NOT NULL
                AND CHAR_LENGTH(TRIM(last_replay_reason)) > 0)
        )
        AND (
            (state IN ('OPEN', 'REPLAYING') AND resolved_at IS NULL)
            OR (state = 'RESOLVED'
                AND resolved_at IS NOT NULL
                AND next_attempt_at IS NULL
                AND resolved_at >= recovery_required_at)
        )
        AND (state <> 'REPLAYING' OR replay_count > 0)
        AND updated_by_type IN ('USER', 'SERVICE', 'SCHEDULER')
        AND (updated_by_type <> 'USER' OR updated_by_id IS NOT NULL)
    )
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

DELIMITER $$

CREATE TRIGGER bu_alt_incident_terminal_guard
BEFORE UPDATE ON incident
FOR EACH ROW
BEGIN
    IF OLD.tenant_id <> NEW.tenant_id
       OR OLD.incident_id <> NEW.incident_id
       OR OLD.fingerprint <> NEW.fingerprint
       OR OLD.generation_no <> NEW.generation_no
       OR OLD.first_event_id <> NEW.first_event_id
       OR OLD.opened_at <> NEW.opened_at
       OR OLD.created_at <> NEW.created_at
       OR OLD.created_by_type <> NEW.created_by_type
       OR NOT (OLD.created_by_id <=> NEW.created_by_id) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'incident identity and creation facts are immutable';
    END IF;

    IF OLD.status = 'RECOVERED' THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'recovered incident is terminal and immutable';
    END IF;
END$$

CREATE TRIGGER bu_alt_incident_ticket_link_guard
BEFORE UPDATE ON incident_ticket_link
FOR EACH ROW
BEGIN
    IF OLD.tenant_id <> NEW.tenant_id
       OR OLD.incident_id <> NEW.incident_id
       OR OLD.create_ticket_intent_key <> NEW.create_ticket_intent_key
       OR OLD.requested_at <> NEW.requested_at
       OR OLD.created_at <> NEW.created_at
       OR OLD.created_by_type <> NEW.created_by_type
       OR NOT (OLD.created_by_id <=> NEW.created_by_id) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'incident ticket intent and creation facts are immutable';
    END IF;

    IF OLD.status = 'ESTABLISHED'
       AND (NEW.status <> 'ESTABLISHED'
            OR NOT (NEW.ticket_id <=> OLD.ticket_id)
            OR NOT (NEW.established_at <=> OLD.established_at)) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'established incident ticket link cannot be downgraded or replaced';
    END IF;

    IF OLD.status = 'RECOVERY_REQUIRED' AND NEW.status <> OLD.status THEN
        IF NEW.status = 'REQUESTED'
           AND (NEW.updated_by_type <> 'USER'
                OR NEW.updated_by_id IS NULL
                OR NEW.updated_at <= OLD.updated_at
                OR NOT (NEW.recovery_required_at <=> OLD.recovery_required_at)
                OR NOT (NEW.recovery_reason_code <=> OLD.recovery_reason_code)) THEN
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = 'incident ticket replay requires an authorized user and original intent';
        END IF;

        IF NEW.status NOT IN ('REQUESTED', 'ESTABLISHED') THEN
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = 'incident ticket recovery transition is invalid';
        END IF;
    END IF;
END$$

DELIMITER ;
