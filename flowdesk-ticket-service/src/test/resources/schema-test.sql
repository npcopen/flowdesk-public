-- Minimal H2 MySQL-mode schema for Ticket service integration tests.
-- Production semantics live in the Flyway baseline + MySQL CHECK/triggers.

CREATE TABLE IF NOT EXISTS sla_policy (
    tenant_id BINARY(16) NOT NULL,
    sla_policy_id BINARY(16) NOT NULL,
    policy_code VARCHAR(64) NOT NULL,
    policy_name VARCHAR(128) NOT NULL,
    first_response_seconds INT NOT NULL,
    resolution_seconds INT NOT NULL,
    first_response_reminder_before_seconds INT NULL,
    resolution_reminder_before_seconds INT NULL,
    is_incident_default INT NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    lock_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by_type VARCHAR(16) NOT NULL,
    created_by_id BINARY(16) NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_by_type VARCHAR(16) NOT NULL,
    updated_by_id BINARY(16) NULL,
    PRIMARY KEY (tenant_id, sla_policy_id),
    UNIQUE (tenant_id, policy_code)
);

CREATE TABLE IF NOT EXISTS service_catalog_item (
    tenant_id BINARY(16) NOT NULL,
    catalog_item_id BINARY(16) NOT NULL,
    item_code VARCHAR(64) NOT NULL,
    item_name VARCHAR(128) NOT NULL,
    description VARCHAR(1000) NULL,
    risk_level VARCHAR(16) NOT NULL DEFAULT 'NORMAL',
    requires_approval BOOLEAN NOT NULL DEFAULT FALSE,
    workflow_definition_id BINARY(16) NULL,
    workflow_version_id BINARY(16) NULL,
    workflow_version_no INT NULL,
    sla_policy_id BINARY(16) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    lock_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by_type VARCHAR(16) NOT NULL,
    created_by_id BINARY(16) NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_by_type VARCHAR(16) NOT NULL,
    updated_by_id BINARY(16) NULL,
    PRIMARY KEY (tenant_id, catalog_item_id),
    UNIQUE (tenant_id, item_code)
);

CREATE TABLE IF NOT EXISTS ticket_number_counter (
    tenant_id BINARY(16) NOT NULL,
    business_date DATE NOT NULL,
    sequence_value BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (tenant_id, business_date),
    CHECK (sequence_value > 0)
);

CREATE TABLE IF NOT EXISTS ticket (
    tenant_id BINARY(16) NOT NULL,
    ticket_id BINARY(16) NOT NULL,
    ticket_no VARCHAR(32) NOT NULL,
    creation_intent_key BINARY(32) NOT NULL,
    origin_type VARCHAR(16) NOT NULL,
    catalog_item_id BINARY(16) NULL,
    requester_user_id BINARY(16) NULL,
    previous_attempt_ticket_id BINARY(16) NULL,
    subject VARCHAR(255) NOT NULL,
    description TEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    assignee_user_id BINARY(16) NULL,
    workflow_definition_id BINARY(16) NULL,
    workflow_version_id BINARY(16) NULL,
    workflow_version_no INT NULL,
    first_processable_at TIMESTAMP(6) NULL,
    resolved_at TIMESTAMP(6) NULL,
    closed_at TIMESTAMP(6) NULL,
    last_reopened_at TIMESTAMP(6) NULL,
    reopen_count INT NOT NULL DEFAULT 0,
    terminal_at TIMESTAMP(6) NULL,
    lock_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by_type VARCHAR(16) NOT NULL,
    created_by_id BINARY(16) NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_by_type VARCHAR(16) NOT NULL,
    updated_by_id BINARY(16) NULL,
    CONSTRAINT pk_tkt_07 PRIMARY KEY (tenant_id, ticket_id),
    CONSTRAINT uk_tkt_08 UNIQUE (tenant_id, ticket_no),
    CONSTRAINT uk_tkt_09 UNIQUE (tenant_id, creation_intent_key)
);

CREATE TABLE IF NOT EXISTS workflow_start_link (
    tenant_id BINARY(16) NOT NULL,
    ticket_id BINARY(16) NOT NULL,
    start_intent_key BINARY(32) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'REQUESTED',
    workflow_instance_id BINARY(16) NULL,
    requested_at TIMESTAMP(6) NOT NULL,
    established_at TIMESTAMP(6) NULL,
    recovery_required_at TIMESTAMP(6) NULL,
    recovery_reason_code VARCHAR(96) NULL,
    retry_count INT NOT NULL DEFAULT 0,
    last_attempt_at TIMESTAMP(6) NULL,
    lock_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by_type VARCHAR(16) NOT NULL,
    created_by_id BINARY(16) NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_by_type VARCHAR(16) NOT NULL,
    updated_by_id BINARY(16) NULL,
    PRIMARY KEY (tenant_id, ticket_id),
    UNIQUE (tenant_id, start_intent_key)
);

CREATE TABLE IF NOT EXISTS ticket_comment (
    tenant_id BINARY(16) NOT NULL,
    comment_id BINARY(16) NOT NULL,
    ticket_id BINARY(16) NOT NULL,
    body TEXT NOT NULL,
    correlation_id BINARY(16) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by_type VARCHAR(16) NOT NULL,
    created_by_id BINARY(16) NOT NULL,
    CONSTRAINT pk_tkt_20 PRIMARY KEY (tenant_id, comment_id)
);

CREATE TABLE IF NOT EXISTS ticket_timeline_entry (
    tenant_id BINARY(16) NOT NULL,
    timeline_id BINARY(16) NOT NULL,
    ticket_id BINARY(16) NOT NULL,
    effect_key BINARY(32) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    summary VARCHAR(1000) NOT NULL,
    related_ref_type VARCHAR(48) NULL,
    related_ref_id BINARY(16) NULL,
    actor_type VARCHAR(16) NOT NULL,
    actor_id BINARY(16) NULL,
    occurred_at TIMESTAMP(6) NOT NULL,
    correlation_id BINARY(16) NOT NULL,
    causation_id BINARY(16) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_tkt_22 PRIMARY KEY (tenant_id, timeline_id),
    CONSTRAINT uk_tkt_23 UNIQUE (tenant_id, ticket_id, effect_key)
);

CREATE TABLE IF NOT EXISTS sla_clock (
    tenant_id BINARY(16) NOT NULL,
    sla_clock_id BINARY(16) NOT NULL,
    ticket_id BINARY(16) NOT NULL,
    sla_policy_id BINARY(16) NOT NULL,
    clock_type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'RUNNING',
    started_at TIMESTAMP(6) NOT NULL,
    deadline_at TIMESTAMP(6) NOT NULL,
    reminder_due_at TIMESTAMP(6) NULL,
    policy_duration_seconds INT NOT NULL,
    policy_reminder_before_seconds INT NULL,
    milestone_at TIMESTAMP(6) NULL,
    breached_at TIMESTAMP(6) NULL,
    satisfied_at TIMESTAMP(6) NULL,
    lock_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by_type VARCHAR(16) NOT NULL,
    created_by_id BINARY(16) NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_by_type VARCHAR(16) NOT NULL,
    updated_by_id BINARY(16) NULL,
    PRIMARY KEY (tenant_id, sla_clock_id),
    cycle_no INT NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, ticket_id, clock_type, cycle_no)
);

CREATE TABLE IF NOT EXISTS sla_trigger (
    tenant_id BINARY(16) NOT NULL,
    sla_trigger_id BINARY(16) NOT NULL,
    sla_clock_id BINARY(16) NOT NULL,
    stage VARCHAR(32) NOT NULL,
    effect_key BINARY(32) NOT NULL,
    due_at TIMESTAMP(6) NOT NULL,
    triggered_at TIMESTAMP(6) NOT NULL,
    publication_key BINARY(32) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by_type VARCHAR(16) NOT NULL DEFAULT 'SCHEDULER',
    created_by_id BINARY(16) NULL,
    PRIMARY KEY (tenant_id, sla_trigger_id),
    UNIQUE (tenant_id, sla_clock_id, stage)
);

CREATE TABLE IF NOT EXISTS outbox_event (
    tenant_id BINARY(16) NOT NULL,
    outbox_id BINARY(16) NOT NULL,
    message_id BINARY(16) NOT NULL,
    publication_key BINARY(32) NOT NULL,
    business_intent_key BINARY(32) NOT NULL,
    aggregate_type VARCHAR(48) NOT NULL,
    aggregate_id BINARY(16) NOT NULL,
    contract_ref VARCHAR(128) NOT NULL,
    contract_version INT NOT NULL,
    body_bytes BLOB NOT NULL,
    body_sha256 BINARY(32) NOT NULL,
    correlation_id BINARY(16) NOT NULL,
    causation_id BINARY(16) NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    locked_by VARCHAR(96) NULL,
    locked_at TIMESTAMP(6) NULL,
    last_error_code VARCHAR(96) NULL,
    broker_confirmed_at TIMESTAMP(6) NULL,
    lock_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by_type VARCHAR(16) NOT NULL,
    created_by_id BINARY(16) NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_by_type VARCHAR(16) NOT NULL DEFAULT 'SERVICE',
    updated_by_id BINARY(16) NULL,
    PRIMARY KEY (tenant_id, outbox_id),
    UNIQUE (tenant_id, message_id),
    UNIQUE (tenant_id, publication_key)
);

CREATE TABLE IF NOT EXISTS incident_ticket_mapping (
    tenant_id BINARY(16) NOT NULL,
    incident_id BINARY(16) NOT NULL,
    ticket_id BINARY(16) NOT NULL,
    creation_intent_key BINARY(32) NOT NULL,
    established_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by_type VARCHAR(16) NOT NULL DEFAULT 'SERVICE',
    created_by_id BINARY(16) NULL,
    PRIMARY KEY (tenant_id, incident_id),
    UNIQUE (tenant_id, ticket_id),
    UNIQUE (tenant_id, creation_intent_key)
);

CREATE TABLE IF NOT EXISTS inbox_message (
    tenant_id BINARY(16) NOT NULL,
    inbox_id BINARY(16) NOT NULL,
    producer_service VARCHAR(64) NOT NULL,
    message_id BINARY(16) NOT NULL,
    business_intent_key BINARY(32) NOT NULL,
    contract_ref VARCHAR(128) NOT NULL,
    contract_version INT NOT NULL,
    body_bytes BLOB NOT NULL,
    body_sha256 BINARY(32) NOT NULL,
    correlation_id BINARY(16) NOT NULL,
    causation_id BINARY(16) NULL,
    occurred_at TIMESTAMP(6) NOT NULL,
    status VARCHAR(32) NOT NULL,
    result_type VARCHAR(48) NULL,
    result_id BINARY(16) NULL,
    attempt_count INT NOT NULL DEFAULT 1,
    last_error_code VARCHAR(96) NULL,
    first_received_at TIMESTAMP(6) NOT NULL,
    last_attempt_at TIMESTAMP(6) NOT NULL,
    processed_at TIMESTAMP(6) NULL,
    lock_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_by_type VARCHAR(16) NOT NULL DEFAULT 'SERVICE',
    updated_by_id BINARY(16) NULL,
    PRIMARY KEY (tenant_id, inbox_id),
    UNIQUE (tenant_id, producer_service, message_id),
    UNIQUE (tenant_id, producer_service, contract_ref, business_intent_key)
);

CREATE TABLE IF NOT EXISTS message_recovery (
    tenant_id BINARY(16) NOT NULL,
    recovery_id BINARY(16) NOT NULL,
    direction VARCHAR(16) NOT NULL,
    source_record_id BINARY(16) NOT NULL,
    business_intent_key BINARY(32) NOT NULL,
    state VARCHAR(32) NOT NULL DEFAULT 'OPEN',
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP(6) NULL,
    last_error_code VARCHAR(96) NOT NULL,
    last_error_summary VARCHAR(1000) NULL,
    dead_lettered_at TIMESTAMP(6) NULL,
    recovery_required_at TIMESTAMP(6) NOT NULL,
    replay_count INT NOT NULL DEFAULT 0,
    last_replayed_at TIMESTAMP(6) NULL,
    last_replayed_by_type VARCHAR(16) NULL,
    last_replayed_by_id BINARY(16) NULL,
    last_replay_reason VARCHAR(1000) NULL,
    last_replay_correlation_id BINARY(16) NULL,
    resolved_at TIMESTAMP(6) NULL,
    lock_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_by_type VARCHAR(16) NOT NULL DEFAULT 'SERVICE',
    updated_by_id BINARY(16) NULL,
    PRIMARY KEY (tenant_id, recovery_id),
    UNIQUE (tenant_id, direction, source_record_id)
);

CREATE TABLE IF NOT EXISTS audit_record (
    tenant_id BINARY(16) NOT NULL,
    audit_id BINARY(16) NOT NULL,
    action_code VARCHAR(96) NOT NULL,
    target_type VARCHAR(48) NOT NULL,
    target_id BINARY(16) NULL,
    outcome VARCHAR(16) NOT NULL,
    actor_type VARCHAR(16) NOT NULL,
    actor_id BINARY(16) NULL,
    reason VARCHAR(1000) NULL,
    error_code VARCHAR(96) NULL,
    correlation_id BINARY(16) NOT NULL,
    causation_id BINARY(16) NULL,
    occurred_at TIMESTAMP(6) NOT NULL,
    recorded_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_tkt_44 PRIMARY KEY (tenant_id, audit_id),
    CONSTRAINT ck_tkt_audit_outcome CHECK (
        outcome IN ('SUCCEEDED', 'REJECTED', 'FAILED')
        AND (outcome <> 'SUCCEEDED' OR error_code IS NULL)
        AND (outcome <> 'FAILED' OR error_code IS NOT NULL)
    )
);

-- M8 SLA target, qualification and rebuild facts.  These are deliberately
-- lightweight H2 equivalents of the MySQL migration so scheduler tests can
-- exercise the same repository paths.
CREATE TABLE IF NOT EXISTS sla_escalation_target (
    tenant_id BINARY(16) NOT NULL,
    target_id BINARY(16) NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    recipient_user_id BINARY(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    effective_at TIMESTAMP(6) NOT NULL,
    qualification_version BIGINT,
    qualification_checked_at TIMESTAMP(6),
    qualification_valid_until TIMESTAMP(6),
    qualification_state VARCHAR(16) NOT NULL,
    qualification_reason_code VARCHAR(96),
    lock_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by_type VARCHAR(16) NOT NULL DEFAULT 'SERVICE',
    created_by_id BINARY(16),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_by_type VARCHAR(16) NOT NULL DEFAULT 'SERVICE',
    updated_by_id BINARY(16),
    PRIMARY KEY (tenant_id, target_id)
);

CREATE TABLE IF NOT EXISTS sla_qualification_snapshot (
    tenant_id BINARY(16) NOT NULL,
    target_id BINARY(16) NOT NULL,
    recipient_user_id BINARY(16) NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    qualification_version BIGINT NOT NULL,
    checked_at TIMESTAMP(6) NOT NULL,
    valid_until TIMESTAMP(6) NOT NULL,
    eligible BOOLEAN NOT NULL,
    reason_code VARCHAR(96) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by_type VARCHAR(16) NOT NULL DEFAULT 'SERVICE',
    created_by_id BINARY(16),
    PRIMARY KEY (tenant_id, target_id)
);

CREATE TABLE IF NOT EXISTS sla_index_rebuild_lease (
    tenant_id BINARY(16) NOT NULL,
    fencing_epoch BIGINT NOT NULL DEFAULT 0,
    run_id BINARY(16),
    high_water_updated_at TIMESTAMP(6),
    high_water_sla_clock_id BINARY(16),
    expires_at TIMESTAMP(6),
    state VARCHAR(16) NOT NULL DEFAULT 'IDLE',
    last_error_code VARCHAR(96),
    lock_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_by_type VARCHAR(16) NOT NULL DEFAULT 'SCHEDULER',
    updated_by_id BINARY(16),
    PRIMARY KEY (tenant_id)
);

CREATE TABLE IF NOT EXISTS sla_trigger_recipient (
    tenant_id BINARY(16) NOT NULL,
    sla_trigger_id BINARY(16) NOT NULL,
    recipient_user_id BINARY(16) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by_type VARCHAR(16) NOT NULL DEFAULT 'SCHEDULER',
    created_by_id BINARY(16),
    PRIMARY KEY (tenant_id, sla_trigger_id, recipient_user_id)
);
