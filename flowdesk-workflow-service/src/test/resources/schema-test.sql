-- H2 MySQL-mode schema for Workflow database contract and repository tests.
-- Production state-transition triggers and grants remain owned by forward MySQL
-- migrations; this schema mirrors the columns, relational constraints and indexes
-- needed by the Workflow M5 consumer/recovery slice.

CREATE TABLE IF NOT EXISTS workflow_definition (
    tenant_id BINARY(16) NOT NULL,
    definition_id BINARY(16) NOT NULL,
    definition_code VARCHAR(64) NOT NULL,
    definition_name VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    lock_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by_type VARCHAR(16) NOT NULL,
    created_by_id BINARY(16) NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_by_type VARCHAR(16) NOT NULL,
    updated_by_id BINARY(16) NULL,
    CONSTRAINT pk_wf_01_definition PRIMARY KEY (tenant_id, definition_id),
    CONSTRAINT uk_wf_02_definition_code UNIQUE (tenant_id, definition_code),
    CONSTRAINT ck_wf_definition_status_actor CHECK (
        status = 'ACTIVE'
        AND created_by_type IN ('USER', 'SERVICE')
        AND updated_by_type IN ('USER', 'SERVICE')
        AND (created_by_type <> 'USER' OR created_by_id IS NOT NULL)
        AND (updated_by_type <> 'USER' OR updated_by_id IS NOT NULL)
    )
);
CREATE INDEX IF NOT EXISTS idx_wf_03_definition_list
    ON workflow_definition (tenant_id, status, definition_name, definition_id);

CREATE TABLE IF NOT EXISTS workflow_draft (
    tenant_id BINARY(16) NOT NULL,
    draft_id BINARY(16) NOT NULL,
    definition_id BINARY(16) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'EDITABLE',
    condition_field_code VARCHAR(64) NOT NULL,
    condition_operator VARCHAR(16) NOT NULL,
    condition_value_type VARCHAR(16) NOT NULL,
    condition_value_text VARCHAR(255) NOT NULL,
    approval_level_count SMALLINT NOT NULL DEFAULT 2,
    level1_approver_user_id BINARY(16) NOT NULL,
    level2_approver_user_id BINARY(16) NOT NULL,
    validation_state VARCHAR(16) NOT NULL DEFAULT 'UNVALIDATED',
    validation_digest BINARY(32) NULL,
    validated_at TIMESTAMP(6) NULL,
    consumed_at TIMESTAMP(6) NULL,
    editable_definition_guard BINARY(16)
        GENERATED ALWAYS AS (
            CASE WHEN status = 'EDITABLE' THEN definition_id ELSE NULL END
        ),
    lock_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by_type VARCHAR(16) NOT NULL,
    created_by_id BINARY(16) NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_by_type VARCHAR(16) NOT NULL,
    updated_by_id BINARY(16) NULL,
    CONSTRAINT pk_wf_04_draft PRIMARY KEY (tenant_id, draft_id),
    CONSTRAINT uk_wf_05_editable_definition UNIQUE (tenant_id, editable_definition_guard),
    CONSTRAINT fk_wf_draft_definition FOREIGN KEY (tenant_id, definition_id)
        REFERENCES workflow_definition (tenant_id, definition_id),
    CONSTRAINT ck_wf_draft_status_times CHECK (
        (status = 'EDITABLE' AND consumed_at IS NULL)
        OR (status = 'CONSUMED' AND consumed_at IS NOT NULL AND consumed_at >= created_at)
    ),
    CONSTRAINT ck_wf_draft_condition CHECK (
        condition_field_code = 'SERVICE_RISK_LEVEL'
        AND condition_operator = 'EQUALS'
        AND condition_value_type = 'STRING'
        AND condition_value_text IN ('NORMAL', 'HIGH')
    ),
    CONSTRAINT ck_wf_draft_two_levels CHECK (approval_level_count = 2),
    CONSTRAINT ck_wf_draft_validation CHECK (
        created_by_type IN ('USER', 'SERVICE')
        AND updated_by_type IN ('USER', 'SERVICE')
        AND (created_by_type <> 'USER' OR created_by_id IS NOT NULL)
        AND (updated_by_type <> 'USER' OR updated_by_id IS NOT NULL)
        AND (
            (validation_state = 'UNVALIDATED'
                AND validation_digest IS NULL AND validated_at IS NULL)
            OR (validation_state = 'VALID'
                AND validation_digest IS NOT NULL AND validated_at IS NOT NULL
                AND validated_at >= created_at)
        )
        AND (status <> 'CONSUMED' OR validation_state = 'VALID')
        AND (consumed_at IS NULL OR consumed_at >= validated_at)
    )
);
CREATE INDEX IF NOT EXISTS idx_wf_06_definition_drafts
    ON workflow_draft (tenant_id, definition_id, status, updated_at, draft_id);

CREATE TABLE IF NOT EXISTS workflow_version (
    tenant_id BINARY(16) NOT NULL,
    workflow_version_id BINARY(16) NOT NULL,
    definition_id BINARY(16) NOT NULL,
    version_no INT NOT NULL,
    source_draft_id BINARY(16) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PUBLISHED',
    condition_field_code VARCHAR(64) NOT NULL,
    condition_operator VARCHAR(16) NOT NULL,
    condition_value_type VARCHAR(16) NOT NULL,
    condition_value_text VARCHAR(255) NOT NULL,
    approval_level_count SMALLINT NOT NULL DEFAULT 2,
    level1_approver_user_id BINARY(16) NOT NULL,
    level2_approver_user_id BINARY(16) NOT NULL,
    snapshot_sha256 BINARY(32) NOT NULL,
    published_at TIMESTAMP(6) NOT NULL,
    published_by_type VARCHAR(16) NOT NULL,
    published_by_id BINARY(16) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_wf_07_version PRIMARY KEY (tenant_id, workflow_version_id),
    CONSTRAINT uk_wf_08_definition_version UNIQUE (tenant_id, definition_id, version_no),
    CONSTRAINT uk_wf_09_source_draft UNIQUE (tenant_id, source_draft_id),
    CONSTRAINT uk_wf_39_version_identity
        UNIQUE (tenant_id, workflow_version_id, definition_id, version_no),
    CONSTRAINT fk_wf_version_definition FOREIGN KEY (tenant_id, definition_id)
        REFERENCES workflow_definition (tenant_id, definition_id),
    CONSTRAINT fk_wf_version_draft FOREIGN KEY (tenant_id, source_draft_id)
        REFERENCES workflow_draft (tenant_id, draft_id),
    CONSTRAINT ck_wf_version_published_levels CHECK (
        status = 'PUBLISHED' AND version_no > 0 AND approval_level_count = 2
    ),
    CONSTRAINT ck_wf_version_condition_actor CHECK (
        condition_field_code = 'SERVICE_RISK_LEVEL'
        AND condition_operator = 'EQUALS'
        AND condition_value_type = 'STRING'
        AND condition_value_text IN ('NORMAL', 'HIGH')
        AND published_by_type IN ('USER', 'SERVICE')
        AND (published_by_type <> 'USER' OR published_by_id IS NOT NULL)
    )
);
CREATE INDEX IF NOT EXISTS idx_wf_10_definition_versions
    ON workflow_version (tenant_id, definition_id, published_at, workflow_version_id);

CREATE TABLE IF NOT EXISTS workflow_instance (
    tenant_id BINARY(16) NOT NULL,
    workflow_instance_id BINARY(16) NOT NULL,
    ticket_id BINARY(16) NOT NULL,
    start_intent_key BINARY(32) NOT NULL,
    definition_id BINARY(16) NOT NULL,
    workflow_version_id BINARY(16) NOT NULL,
    workflow_version_no INT NOT NULL,
    input_snapshot_json JSON NOT NULL,
    input_snapshot_sha256 BINARY(32) NOT NULL,
    condition_result BOOLEAN NOT NULL,
    status VARCHAR(32) NOT NULL,
    current_level SMALLINT NULL,
    started_at TIMESTAMP(6) NOT NULL,
    completed_at TIMESTAMP(6) NULL,
    lock_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by_type VARCHAR(16) NOT NULL DEFAULT 'SERVICE',
    created_by_id BINARY(16) NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_by_type VARCHAR(16) NOT NULL DEFAULT 'SERVICE',
    updated_by_id BINARY(16) NULL,
    CONSTRAINT pk_wf_11_instance PRIMARY KEY (tenant_id, workflow_instance_id),
    CONSTRAINT uk_wf_12_instance_ticket UNIQUE (tenant_id, ticket_id),
    CONSTRAINT uk_wf_13_start_intent UNIQUE (tenant_id, start_intent_key),
    CONSTRAINT fk_wf_instance_version FOREIGN KEY (
        tenant_id, workflow_version_id, definition_id, workflow_version_no
    ) REFERENCES workflow_version (
        tenant_id, workflow_version_id, definition_id, version_no
    ),
    CONSTRAINT ck_wf_instance_status_level_times CHECK (
        (status = 'RUNNING' AND current_level IN (1, 2) AND completed_at IS NULL)
        OR (status IN ('COMPLETED_PASSED', 'COMPLETED_REJECTED')
            AND current_level IS NULL AND completed_at IS NOT NULL
            AND completed_at >= started_at)
    ),
    CONSTRAINT ck_wf_instance_condition_result CHECK (
        condition_result IN (FALSE, TRUE)
        AND (condition_result = TRUE OR status = 'COMPLETED_PASSED')
    ),
    CONSTRAINT ck_wf_instance_actor CHECK (
        created_by_type = 'SERVICE'
        AND updated_by_type IN ('USER', 'SERVICE')
        AND (updated_by_type <> 'USER' OR updated_by_id IS NOT NULL)
    )
);
CREATE INDEX IF NOT EXISTS idx_wf_14_instance_status
    ON workflow_instance (tenant_id, status, updated_at, workflow_instance_id);
CREATE INDEX IF NOT EXISTS idx_wf_15_instance_version_trace
    ON workflow_instance (
        tenant_id, workflow_version_id, definition_id, workflow_version_no,
        created_at, workflow_instance_id
    );

CREATE TABLE IF NOT EXISTS approval_task (
    tenant_id BINARY(16) NOT NULL,
    approval_task_id BINARY(16) NOT NULL,
    workflow_instance_id BINARY(16) NOT NULL,
    level_no SMALLINT NOT NULL,
    approver_user_id BINARY(16) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    decision_type VARCHAR(16) NULL,
    decision_reason VARCHAR(1000) NULL,
    decision_intent_key BINARY(32) NULL,
    decided_at TIMESTAMP(6) NULL,
    decided_by_user_id BINARY(16) NULL,
    pending_instance_guard BINARY(16)
        GENERATED ALWAYS AS (
            CASE WHEN status = 'PENDING' THEN workflow_instance_id ELSE NULL END
        ),
    lock_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by_type VARCHAR(16) NOT NULL DEFAULT 'SERVICE',
    created_by_id BINARY(16) NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_by_type VARCHAR(16) NOT NULL DEFAULT 'SERVICE',
    updated_by_id BINARY(16) NULL,
    CONSTRAINT pk_wf_16_task PRIMARY KEY (tenant_id, approval_task_id),
    CONSTRAINT uk_wf_17_instance_level UNIQUE (tenant_id, workflow_instance_id, level_no),
    CONSTRAINT uk_wf_18_pending_instance UNIQUE (tenant_id, pending_instance_guard),
    CONSTRAINT fk_wf_task_instance FOREIGN KEY (tenant_id, workflow_instance_id)
        REFERENCES workflow_instance (tenant_id, workflow_instance_id),
    CONSTRAINT ck_wf_task_level_status CHECK (
        level_no IN (1, 2) AND status IN ('PENDING', 'APPROVED', 'REJECTED')
    ),
    CONSTRAINT ck_wf_task_decision_tuple CHECK (
        (status = 'PENDING' AND decision_type IS NULL AND decision_reason IS NULL
            AND decision_intent_key IS NULL AND decided_at IS NULL
            AND decided_by_user_id IS NULL)
        OR (status = 'APPROVED' AND decision_type = 'APPROVE'
            AND decision_intent_key IS NOT NULL AND decided_at IS NOT NULL
            AND decided_by_user_id IS NOT NULL)
        OR (status = 'REJECTED' AND decision_type = 'REJECT'
            AND decision_reason IS NOT NULL
            AND CHAR_LENGTH(TRIM(decision_reason)) > 0
            AND decision_intent_key IS NOT NULL AND decided_at IS NOT NULL
            AND decided_by_user_id IS NOT NULL)
    ),
    CONSTRAINT ck_wf_task_decider CHECK (
        created_by_type = 'SERVICE'
        AND updated_by_type IN ('USER', 'SERVICE')
        AND (updated_by_type <> 'USER' OR updated_by_id IS NOT NULL)
        AND (decided_by_user_id IS NULL OR decided_by_user_id = approver_user_id)
    )
);
CREATE INDEX IF NOT EXISTS idx_wf_19_approver_tasks
    ON approval_task (tenant_id, approver_user_id, status, created_at, approval_task_id);
CREATE INDEX IF NOT EXISTS idx_wf_20_instance_tasks
    ON approval_task (tenant_id, workflow_instance_id, status, level_no);

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
    CONSTRAINT pk_wf_21_outbox PRIMARY KEY (tenant_id, outbox_id),
    CONSTRAINT uk_wf_22_message UNIQUE (tenant_id, message_id),
    CONSTRAINT uk_wf_23_publication UNIQUE (tenant_id, publication_key),
    CONSTRAINT ck_wf_outbox_state_times CHECK (
        status IN ('PENDING', 'PUBLISHING', 'PUBLISHED', 'RECOVERY_REQUIRED')
        AND ((status = 'PUBLISHED' AND broker_confirmed_at IS NOT NULL)
            OR (status <> 'PUBLISHED' AND broker_confirmed_at IS NULL))
    ),
    CONSTRAINT ck_wf_outbox_attempt_lease CHECK (
        attempt_count >= 0 AND contract_version > 0
        AND ((status = 'PUBLISHING' AND locked_by IS NOT NULL AND locked_at IS NOT NULL)
            OR (status <> 'PUBLISHING' AND locked_by IS NULL AND locked_at IS NULL))
        AND (status <> 'RECOVERY_REQUIRED' OR last_error_code IS NOT NULL)
    )
);
CREATE INDEX IF NOT EXISTS idx_wf_24_outbox_worker
    ON outbox_event (tenant_id, status, next_attempt_at, outbox_id);
CREATE INDEX IF NOT EXISTS idx_wf_25_outbox_aggregate
    ON outbox_event (tenant_id, aggregate_type, aggregate_id, created_at, outbox_id);

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
    CONSTRAINT pk_wf_26_inbox PRIMARY KEY (tenant_id, inbox_id),
    CONSTRAINT uk_wf_27_producer_message
        UNIQUE (tenant_id, producer_service, message_id),
    CONSTRAINT uk_wf_28_producer_intent
        UNIQUE (tenant_id, producer_service, contract_ref, business_intent_key),
    CONSTRAINT ck_wf_inbox_state_result CHECK (
        status IN ('PROCESSED', 'REJECTED', 'RECOVERY_REQUIRED')
        AND updated_by_type IN ('USER', 'SERVICE', 'SCHEDULER')
        AND (updated_by_type <> 'USER' OR updated_by_id IS NOT NULL)
        AND (
            (status = 'PROCESSED' AND result_type IS NOT NULL
                AND result_id IS NOT NULL AND processed_at IS NOT NULL)
            OR (status = 'REJECTED' AND result_type IS NULL AND result_id IS NULL
                AND processed_at IS NOT NULL AND last_error_code IS NOT NULL)
            OR (status = 'RECOVERY_REQUIRED' AND result_type IS NULL
                AND result_id IS NULL AND processed_at IS NULL
                AND last_error_code IS NOT NULL)
        )
    ),
    CONSTRAINT ck_wf_inbox_attempt_times CHECK (
        contract_version > 0 AND attempt_count >= 1
        AND last_attempt_at >= first_received_at
        AND (processed_at IS NULL OR processed_at >= first_received_at)
    )
);
CREATE INDEX IF NOT EXISTS idx_wf_29_inbox_recovery
    ON inbox_message (tenant_id, status, updated_at, inbox_id);
CREATE INDEX IF NOT EXISTS idx_wf_30_inbox_result
    ON inbox_message (tenant_id, result_type, result_id, processed_at, inbox_id);

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
    CONSTRAINT pk_wf_31_audit PRIMARY KEY (tenant_id, audit_id),
    CONSTRAINT ck_wf_audit_outcome_actor CHECK (
        outcome IN ('SUCCEEDED', 'REJECTED', 'FAILED')
        AND actor_type IN ('USER', 'SERVICE', 'SCHEDULER')
        AND (actor_type <> 'USER' OR actor_id IS NOT NULL)
    )
);
CREATE INDEX IF NOT EXISTS idx_wf_32_audit_target
    ON audit_record (tenant_id, target_type, target_id, occurred_at, audit_id);
CREATE INDEX IF NOT EXISTS idx_wf_33_audit_actor
    ON audit_record (tenant_id, actor_type, actor_id, occurred_at, audit_id);
CREATE INDEX IF NOT EXISTS idx_wf_34_audit_correlation
    ON audit_record (tenant_id, correlation_id, occurred_at, audit_id);

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
    CONSTRAINT pk_wf_35_recovery PRIMARY KEY (tenant_id, recovery_id),
    CONSTRAINT uk_wf_36_recovery_source UNIQUE (tenant_id, direction, source_record_id),
    CONSTRAINT ck_wf_recovery_state_times CHECK (
        direction IN ('OUTBOX', 'INBOX')
        AND state IN ('OPEN', 'REPLAYING', 'RESOLVED')
        AND attempt_count >= 0 AND replay_count >= 0
        AND updated_by_type IN ('USER', 'SERVICE', 'SCHEDULER')
        AND (updated_by_type <> 'USER' OR updated_by_id IS NOT NULL)
        AND (
            (replay_count = 0 AND last_replayed_at IS NULL
                AND last_replayed_by_type IS NULL AND last_replayed_by_id IS NULL
                AND last_replay_reason IS NULL)
            OR (replay_count > 0 AND last_replayed_at IS NOT NULL
                AND last_replayed_by_type IN ('USER', 'SERVICE')
                AND (last_replayed_by_type <> 'USER' OR last_replayed_by_id IS NOT NULL)
                AND last_replay_reason IS NOT NULL)
        )
        AND ((state = 'RESOLVED' AND resolved_at IS NOT NULL)
            OR (state <> 'RESOLVED' AND resolved_at IS NULL))
        AND (state <> 'REPLAYING' OR replay_count > 0)
        AND (last_replayed_at IS NULL OR last_replayed_at >= recovery_required_at)
        AND (resolved_at IS NULL OR resolved_at >= recovery_required_at)
        AND (dead_lettered_at IS NULL OR recovery_required_at >= dead_lettered_at)
    ),
    CONSTRAINT ck_wf_recovery_replay_correlation CHECK (
        (replay_count = 0 AND last_replay_correlation_id IS NULL)
        OR (replay_count > 0 AND last_replay_correlation_id IS NOT NULL)
    )
);
CREATE INDEX IF NOT EXISTS idx_wf_37_recovery_queue
    ON message_recovery (tenant_id, state, next_attempt_at, recovery_id);
CREATE INDEX IF NOT EXISTS idx_wf_38_dead_letter
    ON message_recovery (tenant_id, dead_lettered_at, recovery_id);
CREATE TABLE IF NOT EXISTS workflow_approver_override (
    tenant_id BINARY(16) NOT NULL,
    workflow_instance_id BINARY(16) NOT NULL,
    level_no INT NOT NULL,
    approver_user_id BINARY(16) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (tenant_id,workflow_instance_id,level_no)
);
CREATE TABLE IF NOT EXISTS workflow_approver_change (
    tenant_id BINARY(16) NOT NULL,
    change_id BINARY(16) NOT NULL,
    workflow_instance_id BINARY(16) NOT NULL,
    level_no INT NOT NULL,
    previous_approver_user_id BINARY(16) NOT NULL,
    approver_user_id BINARY(16) NOT NULL,
    changed_by_user_id BINARY(16) NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    request_digest VARCHAR(64) NOT NULL,
    changed_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (tenant_id,change_id)
);
