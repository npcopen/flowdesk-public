-- FlowDesk Workflow schema baseline.
-- The connection's default schema must be flowdesk_workflow. This migration
-- never qualifies or accesses another schema.

CREATE TABLE workflow_definition (
    tenant_id BINARY(16) NOT NULL,
    definition_id BINARY(16) NOT NULL,
    definition_code VARCHAR(64) NOT NULL,
    definition_name VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    lock_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by_type VARCHAR(16) NOT NULL,
    created_by_id BINARY(16) NULL DEFAULT NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_by_type VARCHAR(16) NOT NULL,
    updated_by_id BINARY(16) NULL DEFAULT NULL,
    CONSTRAINT pk_wf_01_definition PRIMARY KEY (tenant_id, definition_id),
    CONSTRAINT uk_wf_02_definition_code
        UNIQUE (tenant_id, definition_code),
    KEY idx_wf_03_definition_list
        (tenant_id, status, definition_name, definition_id),
    CONSTRAINT ck_wf_definition_status_actor CHECK (
        status = 'ACTIVE'
        AND created_by_type IN ('USER', 'SERVICE')
        AND updated_by_type IN ('USER', 'SERVICE')
        AND (created_by_type <> 'USER' OR created_by_id IS NOT NULL)
        AND (updated_by_type <> 'USER' OR updated_by_id IS NOT NULL)
    )
) ENGINE = InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;

CREATE TABLE workflow_draft (
    tenant_id BINARY(16) NOT NULL,
    draft_id BINARY(16) NOT NULL,
    definition_id BINARY(16) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'EDITABLE',
    condition_field_code VARCHAR(64) NOT NULL,
    condition_operator VARCHAR(16) NOT NULL,
    condition_value_type VARCHAR(16) NOT NULL,
    condition_value_text VARCHAR(255) NOT NULL,
    approval_level_count TINYINT UNSIGNED NOT NULL DEFAULT 2,
    level1_approver_user_id BINARY(16) NOT NULL,
    level2_approver_user_id BINARY(16) NOT NULL,
    validation_state VARCHAR(16) NOT NULL DEFAULT 'UNVALIDATED',
    validation_digest BINARY(32) NULL DEFAULT NULL,
    validated_at TIMESTAMP(6) NULL DEFAULT NULL,
    consumed_at TIMESTAMP(6) NULL DEFAULT NULL,
    editable_definition_guard BINARY(16)
        GENERATED ALWAYS AS (
            CASE WHEN status = 'EDITABLE' THEN definition_id ELSE NULL END
        ) STORED,
    lock_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by_type VARCHAR(16) NOT NULL,
    created_by_id BINARY(16) NULL DEFAULT NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_by_type VARCHAR(16) NOT NULL,
    updated_by_id BINARY(16) NULL DEFAULT NULL,
    CONSTRAINT pk_wf_04_draft PRIMARY KEY (tenant_id, draft_id),
    CONSTRAINT uk_wf_05_editable_definition
        UNIQUE (tenant_id, editable_definition_guard),
    KEY idx_wf_06_definition_drafts
        (tenant_id, definition_id, status, updated_at, draft_id),
    CONSTRAINT fk_wf_draft_definition
        FOREIGN KEY (tenant_id, definition_id)
        REFERENCES workflow_definition (tenant_id, definition_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT ck_wf_draft_status_times CHECK (
        (status = 'EDITABLE' AND consumed_at IS NULL)
        OR (status = 'CONSUMED'
            AND consumed_at IS NOT NULL
            AND consumed_at >= created_at)
    ),
    CONSTRAINT ck_wf_draft_condition CHECK (
        condition_field_code = 'SERVICE_RISK_LEVEL'
        AND condition_operator = 'EQUALS'
        AND condition_value_type = 'STRING'
        AND condition_value_text IN ('NORMAL', 'HIGH')
    ),
    CONSTRAINT ck_wf_draft_two_levels CHECK (
        approval_level_count = 2
    ),
    CONSTRAINT ck_wf_draft_validation CHECK (
        created_by_type IN ('USER', 'SERVICE')
        AND updated_by_type IN ('USER', 'SERVICE')
        AND (created_by_type <> 'USER' OR created_by_id IS NOT NULL)
        AND (updated_by_type <> 'USER' OR updated_by_id IS NOT NULL)
        AND (
            (validation_state = 'UNVALIDATED'
                AND validation_digest IS NULL
                AND validated_at IS NULL)
            OR
            (validation_state = 'VALID'
                AND validation_digest IS NOT NULL
                AND validated_at IS NOT NULL
                AND validated_at >= created_at)
        )
        AND (status <> 'CONSUMED' OR validation_state = 'VALID')
        AND (consumed_at IS NULL OR consumed_at >= validated_at)
    )
) ENGINE = InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;

CREATE TABLE workflow_version (
    tenant_id BINARY(16) NOT NULL,
    workflow_version_id BINARY(16) NOT NULL,
    definition_id BINARY(16) NOT NULL,
    version_no INT UNSIGNED NOT NULL,
    source_draft_id BINARY(16) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PUBLISHED',
    condition_field_code VARCHAR(64) NOT NULL,
    condition_operator VARCHAR(16) NOT NULL,
    condition_value_type VARCHAR(16) NOT NULL,
    condition_value_text VARCHAR(255) NOT NULL,
    approval_level_count TINYINT UNSIGNED NOT NULL DEFAULT 2,
    level1_approver_user_id BINARY(16) NOT NULL,
    level2_approver_user_id BINARY(16) NOT NULL,
    snapshot_sha256 BINARY(32) NOT NULL,
    published_at TIMESTAMP(6) NOT NULL,
    published_by_type VARCHAR(16) NOT NULL,
    published_by_id BINARY(16) NULL DEFAULT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_wf_07_version
        PRIMARY KEY (tenant_id, workflow_version_id),
    CONSTRAINT uk_wf_08_definition_version
        UNIQUE (tenant_id, definition_id, version_no),
    CONSTRAINT uk_wf_09_source_draft
        UNIQUE (tenant_id, source_draft_id),
    KEY idx_wf_10_definition_versions
        (tenant_id, definition_id, published_at, workflow_version_id),
    CONSTRAINT uk_wf_39_version_identity
        UNIQUE (
            tenant_id, workflow_version_id, definition_id, version_no
        ),
    CONSTRAINT fk_wf_version_definition
        FOREIGN KEY (tenant_id, definition_id)
        REFERENCES workflow_definition (tenant_id, definition_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_wf_version_draft
        FOREIGN KEY (tenant_id, source_draft_id)
        REFERENCES workflow_draft (tenant_id, draft_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT ck_wf_version_published_levels CHECK (
        status = 'PUBLISHED'
        AND version_no > 0
        AND approval_level_count = 2
    ),
    CONSTRAINT ck_wf_version_condition_actor CHECK (
        condition_field_code = 'SERVICE_RISK_LEVEL'
        AND condition_operator = 'EQUALS'
        AND condition_value_type = 'STRING'
        AND condition_value_text IN ('NORMAL', 'HIGH')
        AND published_by_type IN ('USER', 'SERVICE')
        AND (published_by_type <> 'USER' OR published_by_id IS NOT NULL)
    )
) ENGINE = InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;

CREATE TABLE workflow_instance (
    tenant_id BINARY(16) NOT NULL,
    workflow_instance_id BINARY(16) NOT NULL,
    ticket_id BINARY(16) NOT NULL,
    start_intent_key BINARY(32) NOT NULL,
    definition_id BINARY(16) NOT NULL,
    workflow_version_id BINARY(16) NOT NULL,
    workflow_version_no INT UNSIGNED NOT NULL,
    input_snapshot_json JSON NOT NULL,
    input_snapshot_sha256 BINARY(32) NOT NULL,
    condition_result TINYINT(1) NOT NULL,
    status VARCHAR(32) NOT NULL,
    current_level TINYINT UNSIGNED NULL DEFAULT NULL,
    started_at TIMESTAMP(6) NOT NULL,
    completed_at TIMESTAMP(6) NULL DEFAULT NULL,
    lock_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by_type VARCHAR(16) NOT NULL DEFAULT 'SERVICE',
    created_by_id BINARY(16) NULL DEFAULT NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_by_type VARCHAR(16) NOT NULL DEFAULT 'SERVICE',
    updated_by_id BINARY(16) NULL DEFAULT NULL,
    CONSTRAINT pk_wf_11_instance
        PRIMARY KEY (tenant_id, workflow_instance_id),
    CONSTRAINT uk_wf_12_instance_ticket UNIQUE (tenant_id, ticket_id),
    CONSTRAINT uk_wf_13_start_intent UNIQUE (tenant_id, start_intent_key),
    KEY idx_wf_14_instance_status
        (tenant_id, status, updated_at, workflow_instance_id),
    KEY idx_wf_15_instance_version_trace (
        tenant_id, workflow_version_id, definition_id, workflow_version_no,
        created_at, workflow_instance_id
    ),
    CONSTRAINT fk_wf_instance_version
        FOREIGN KEY (
            tenant_id, workflow_version_id, definition_id,
            workflow_version_no
        )
        REFERENCES workflow_version (
            tenant_id, workflow_version_id, definition_id, version_no
        )
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT ck_wf_instance_status_level_times CHECK (
        (
            (status = 'RUNNING'
                AND current_level IN (1, 2)
                AND completed_at IS NULL)
            OR
            (status IN ('COMPLETED_PASSED', 'COMPLETED_REJECTED')
                AND current_level IS NULL
                AND completed_at IS NOT NULL)
        )
        AND (completed_at IS NULL OR completed_at >= started_at)
    ),
    CONSTRAINT ck_wf_instance_condition_result CHECK (
        condition_result IN (0, 1)
        AND (condition_result = 1 OR status = 'COMPLETED_PASSED')
    ),
    CONSTRAINT ck_wf_instance_actor CHECK (
        created_by_type = 'SERVICE'
        AND updated_by_type IN ('USER', 'SERVICE')
        AND (updated_by_type <> 'USER' OR updated_by_id IS NOT NULL)
    )
) ENGINE = InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;

CREATE TABLE approval_task (
    tenant_id BINARY(16) NOT NULL,
    approval_task_id BINARY(16) NOT NULL,
    workflow_instance_id BINARY(16) NOT NULL,
    level_no TINYINT UNSIGNED NOT NULL,
    approver_user_id BINARY(16) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    decision_type VARCHAR(16) NULL DEFAULT NULL,
    decision_reason VARCHAR(1000) NULL DEFAULT NULL,
    decision_intent_key BINARY(32) NULL DEFAULT NULL,
    decided_at TIMESTAMP(6) NULL DEFAULT NULL,
    decided_by_user_id BINARY(16) NULL DEFAULT NULL,
    pending_instance_guard BINARY(16)
        GENERATED ALWAYS AS (
            CASE WHEN status = 'PENDING' THEN workflow_instance_id ELSE NULL END
        ) STORED,
    lock_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by_type VARCHAR(16) NOT NULL DEFAULT 'SERVICE',
    created_by_id BINARY(16) NULL DEFAULT NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_by_type VARCHAR(16) NOT NULL DEFAULT 'SERVICE',
    updated_by_id BINARY(16) NULL DEFAULT NULL,
    CONSTRAINT pk_wf_16_task PRIMARY KEY (tenant_id, approval_task_id),
    CONSTRAINT uk_wf_17_instance_level
        UNIQUE (tenant_id, workflow_instance_id, level_no),
    CONSTRAINT uk_wf_18_pending_instance
        UNIQUE (tenant_id, pending_instance_guard),
    KEY idx_wf_19_approver_tasks
        (tenant_id, approver_user_id, status, created_at, approval_task_id),
    KEY idx_wf_20_instance_tasks
        (tenant_id, workflow_instance_id, status, level_no),
    CONSTRAINT fk_wf_task_instance
        FOREIGN KEY (tenant_id, workflow_instance_id)
        REFERENCES workflow_instance (tenant_id, workflow_instance_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT ck_wf_task_level_status CHECK (
        level_no IN (1, 2)
        AND status IN ('PENDING', 'APPROVED', 'REJECTED')
    ),
    CONSTRAINT ck_wf_task_decision_tuple CHECK (
        (status = 'PENDING'
            AND decision_type IS NULL
            AND decision_reason IS NULL
            AND decision_intent_key IS NULL
            AND decided_at IS NULL
            AND decided_by_user_id IS NULL)
        OR
        (status = 'APPROVED'
            AND decision_type = 'APPROVE'
            AND decision_intent_key IS NOT NULL
            AND decided_at IS NOT NULL
            AND decided_at >= created_at
            AND decided_by_user_id IS NOT NULL)
        OR
        (status = 'REJECTED'
            AND decision_type = 'REJECT'
            AND decision_reason IS NOT NULL
            AND CHAR_LENGTH(decision_reason) > 0
            AND decision_intent_key IS NOT NULL
            AND decided_at IS NOT NULL
            AND decided_at >= created_at
            AND decided_by_user_id IS NOT NULL)
    ),
    CONSTRAINT ck_wf_task_decider CHECK (
        created_by_type = 'SERVICE'
        AND updated_by_type IN ('USER', 'SERVICE')
        AND (updated_by_type <> 'USER' OR updated_by_id IS NOT NULL)
        AND (decided_by_user_id IS NULL
            OR decided_by_user_id = approver_user_id)
        AND (status = 'PENDING'
            OR (updated_by_type = 'USER'
                AND updated_by_id = approver_user_id))
    )
) ENGINE = InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;

CREATE TABLE outbox_event (
    tenant_id BINARY(16) NOT NULL,
    outbox_id BINARY(16) NOT NULL,
    message_id BINARY(16) NOT NULL,
    publication_key BINARY(32) NOT NULL,
    business_intent_key BINARY(32) NOT NULL,
    aggregate_type VARCHAR(48) NOT NULL,
    aggregate_id BINARY(16) NOT NULL,
    contract_ref VARCHAR(128) NOT NULL,
    contract_version INT UNSIGNED NOT NULL,
    body_bytes LONGBLOB NOT NULL,
    body_sha256 BINARY(32) NOT NULL,
    correlation_id BINARY(16) NOT NULL,
    causation_id BINARY(16) NULL DEFAULT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    attempt_count INT UNSIGNED NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    locked_by VARCHAR(96) NULL DEFAULT NULL,
    locked_at TIMESTAMP(6) NULL DEFAULT NULL,
    last_error_code VARCHAR(96) NULL DEFAULT NULL,
    broker_confirmed_at TIMESTAMP(6) NULL DEFAULT NULL,
    lock_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by_type VARCHAR(16) NOT NULL,
    created_by_id BINARY(16) NULL DEFAULT NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_by_type VARCHAR(16) NOT NULL DEFAULT 'SERVICE',
    updated_by_id BINARY(16) NULL DEFAULT NULL,
    CONSTRAINT pk_wf_21_outbox PRIMARY KEY (tenant_id, outbox_id),
    CONSTRAINT uk_wf_22_message UNIQUE (tenant_id, message_id),
    CONSTRAINT uk_wf_23_publication UNIQUE (tenant_id, publication_key),
    KEY idx_wf_24_outbox_worker
        (tenant_id, status, next_attempt_at, outbox_id),
    KEY idx_wf_25_outbox_aggregate
        (tenant_id, aggregate_type, aggregate_id, created_at, outbox_id),
    CONSTRAINT ck_wf_outbox_state_times CHECK (
        created_by_type IN ('USER', 'SERVICE')
        AND updated_by_type IN ('USER', 'SERVICE', 'SCHEDULER')
        AND (created_by_type <> 'USER' OR created_by_id IS NOT NULL)
        AND (updated_by_type <> 'USER' OR updated_by_id IS NOT NULL)
        AND status IN (
            'PENDING', 'PUBLISHING', 'PUBLISHED', 'RECOVERY_REQUIRED'
        )
        AND ((status = 'PUBLISHED' AND broker_confirmed_at IS NOT NULL)
            OR (status <> 'PUBLISHED' AND broker_confirmed_at IS NULL))
        AND (broker_confirmed_at IS NULL
            OR broker_confirmed_at >= created_at)
    ),
    CONSTRAINT ck_wf_outbox_attempt_lease CHECK (
        attempt_count >= 0
        AND contract_version > 0
        AND ((status = 'PUBLISHING'
                AND locked_by IS NOT NULL
                AND locked_at IS NOT NULL
                AND locked_at >= created_at)
            OR (status <> 'PUBLISHING'
                AND locked_by IS NULL
                AND locked_at IS NULL))
        AND (status <> 'RECOVERY_REQUIRED' OR last_error_code IS NOT NULL)
    )
) ENGINE = InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;

CREATE TABLE inbox_message (
    tenant_id BINARY(16) NOT NULL,
    inbox_id BINARY(16) NOT NULL,
    producer_service VARCHAR(64) NOT NULL,
    message_id BINARY(16) NOT NULL,
    business_intent_key BINARY(32) NOT NULL,
    contract_ref VARCHAR(128) NOT NULL,
    contract_version INT UNSIGNED NOT NULL,
    body_sha256 BINARY(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    result_type VARCHAR(48) NULL DEFAULT NULL,
    result_id BINARY(16) NULL DEFAULT NULL,
    attempt_count INT UNSIGNED NOT NULL DEFAULT 1,
    last_error_code VARCHAR(96) NULL DEFAULT NULL,
    first_received_at TIMESTAMP(6) NOT NULL,
    last_attempt_at TIMESTAMP(6) NOT NULL,
    processed_at TIMESTAMP(6) NULL DEFAULT NULL,
    lock_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_by_type VARCHAR(16) NOT NULL DEFAULT 'SERVICE',
    updated_by_id BINARY(16) NULL DEFAULT NULL,
    CONSTRAINT pk_wf_26_inbox PRIMARY KEY (tenant_id, inbox_id),
    CONSTRAINT uk_wf_27_producer_message
        UNIQUE (tenant_id, producer_service, message_id),
    CONSTRAINT uk_wf_28_producer_intent
        UNIQUE (
            tenant_id, producer_service, contract_ref, business_intent_key
        ),
    KEY idx_wf_29_inbox_recovery
        (tenant_id, status, updated_at, inbox_id),
    KEY idx_wf_30_inbox_result
        (tenant_id, result_type, result_id, processed_at, inbox_id),
    CONSTRAINT ck_wf_inbox_state_result CHECK (
        status IN ('PROCESSED', 'REJECTED', 'RECOVERY_REQUIRED')
        AND updated_by_type IN ('USER', 'SERVICE', 'SCHEDULER')
        AND (updated_by_type <> 'USER' OR updated_by_id IS NOT NULL)
        AND (
            (status = 'PROCESSED'
                AND result_type IS NOT NULL
                AND result_id IS NOT NULL
                AND processed_at IS NOT NULL)
            OR
            (status = 'REJECTED'
                AND result_type IS NULL
                AND result_id IS NULL
                AND processed_at IS NOT NULL
                AND last_error_code IS NOT NULL)
            OR
            (status = 'RECOVERY_REQUIRED'
                AND result_type IS NULL
                AND result_id IS NULL
                AND processed_at IS NULL
                AND last_error_code IS NOT NULL)
        )
    ),
    CONSTRAINT ck_wf_inbox_attempt_times CHECK (
        contract_version > 0
        AND attempt_count >= 1
        AND last_attempt_at >= first_received_at
        AND (processed_at IS NULL OR processed_at >= first_received_at)
    )
) ENGINE = InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;

CREATE TABLE audit_record (
    tenant_id BINARY(16) NOT NULL,
    audit_id BINARY(16) NOT NULL,
    action_code VARCHAR(96) NOT NULL,
    target_type VARCHAR(48) NOT NULL,
    target_id BINARY(16) NULL DEFAULT NULL,
    outcome VARCHAR(16) NOT NULL,
    actor_type VARCHAR(16) NOT NULL,
    actor_id BINARY(16) NULL DEFAULT NULL,
    reason VARCHAR(1000) NULL DEFAULT NULL,
    error_code VARCHAR(96) NULL DEFAULT NULL,
    correlation_id BINARY(16) NOT NULL,
    causation_id BINARY(16) NULL DEFAULT NULL,
    occurred_at TIMESTAMP(6) NOT NULL,
    recorded_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_wf_31_audit PRIMARY KEY (tenant_id, audit_id),
    KEY idx_wf_32_audit_target
        (tenant_id, target_type, target_id, occurred_at, audit_id),
    KEY idx_wf_33_audit_actor
        (tenant_id, actor_type, actor_id, occurred_at, audit_id),
    KEY idx_wf_34_audit_correlation
        (tenant_id, correlation_id, occurred_at, audit_id),
    CONSTRAINT ck_wf_audit_outcome_actor CHECK (
        outcome IN ('SUCCEEDED', 'REJECTED', 'FAILED')
        AND actor_type IN ('USER', 'SERVICE', 'SCHEDULER')
        AND (actor_type <> 'USER' OR actor_id IS NOT NULL)
    )
) ENGINE = InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;

CREATE TABLE message_recovery (
    tenant_id BINARY(16) NOT NULL,
    recovery_id BINARY(16) NOT NULL,
    direction VARCHAR(16) NOT NULL,
    source_record_id BINARY(16) NOT NULL,
    business_intent_key BINARY(32) NOT NULL,
    state VARCHAR(32) NOT NULL DEFAULT 'OPEN',
    attempt_count INT UNSIGNED NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP(6) NULL DEFAULT NULL,
    last_error_code VARCHAR(96) NOT NULL,
    last_error_summary VARCHAR(1000) NULL DEFAULT NULL,
    dead_lettered_at TIMESTAMP(6) NULL DEFAULT NULL,
    recovery_required_at TIMESTAMP(6) NOT NULL,
    replay_count INT UNSIGNED NOT NULL DEFAULT 0,
    last_replayed_at TIMESTAMP(6) NULL DEFAULT NULL,
    last_replayed_by_type VARCHAR(16) NULL DEFAULT NULL,
    last_replayed_by_id BINARY(16) NULL DEFAULT NULL,
    last_replay_reason VARCHAR(1000) NULL DEFAULT NULL,
    resolved_at TIMESTAMP(6) NULL DEFAULT NULL,
    lock_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_by_type VARCHAR(16) NOT NULL DEFAULT 'SERVICE',
    updated_by_id BINARY(16) NULL DEFAULT NULL,
    CONSTRAINT pk_wf_35_recovery PRIMARY KEY (tenant_id, recovery_id),
    CONSTRAINT uk_wf_36_recovery_source
        UNIQUE (tenant_id, direction, source_record_id),
    KEY idx_wf_37_recovery_queue
        (tenant_id, state, next_attempt_at, recovery_id),
    KEY idx_wf_38_dead_letter
        (tenant_id, dead_lettered_at, recovery_id),
    CONSTRAINT ck_wf_recovery_state_times CHECK (
        direction IN ('OUTBOX', 'INBOX')
        AND state IN ('OPEN', 'REPLAYING', 'RESOLVED')
        AND attempt_count >= 0
        AND replay_count >= 0
        AND updated_by_type IN ('USER', 'SERVICE', 'SCHEDULER')
        AND (updated_by_type <> 'USER' OR updated_by_id IS NOT NULL)
        AND (
            (replay_count = 0
                AND last_replayed_at IS NULL
                AND last_replayed_by_type IS NULL
                AND last_replayed_by_id IS NULL
                AND last_replay_reason IS NULL)
            OR
            (replay_count > 0
                AND last_replayed_at IS NOT NULL
                AND last_replayed_by_type IN ('USER', 'SERVICE')
                AND (last_replayed_by_type <> 'USER'
                    OR last_replayed_by_id IS NOT NULL)
                AND last_replay_reason IS NOT NULL)
        )
        AND ((state = 'RESOLVED' AND resolved_at IS NOT NULL)
            OR (state <> 'RESOLVED' AND resolved_at IS NULL))
        AND (state <> 'REPLAYING' OR replay_count > 0)
        AND (last_replayed_at IS NULL
            OR last_replayed_at >= recovery_required_at)
        AND (resolved_at IS NULL OR resolved_at >= recovery_required_at)
        AND (dead_lettered_at IS NULL
            OR recovery_required_at >= dead_lettered_at)
    )
) ENGINE = InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;

DELIMITER //

CREATE TRIGGER trg_wf_draft_before_update
BEFORE UPDATE ON workflow_draft
FOR EACH ROW
BEGIN
    IF NOT (NEW.tenant_id <=> OLD.tenant_id)
       OR NOT (NEW.draft_id <=> OLD.draft_id)
       OR NOT (NEW.definition_id <=> OLD.definition_id)
       OR NOT (NEW.created_at <=> OLD.created_at)
       OR NOT (NEW.created_by_type <=> OLD.created_by_type)
       OR NOT (NEW.created_by_id <=> OLD.created_by_id) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'workflow draft identity is immutable';
    END IF;

    IF OLD.status = 'CONSUMED' THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'consumed workflow draft is terminal';
    END IF;
END//

CREATE TRIGGER trg_wf_instance_before_update
BEFORE UPDATE ON workflow_instance
FOR EACH ROW
BEGIN
    DECLARE approved_level_one_count INT DEFAULT 0;

    IF NOT (NEW.tenant_id <=> OLD.tenant_id)
       OR NOT (NEW.workflow_instance_id <=> OLD.workflow_instance_id)
       OR NOT (NEW.ticket_id <=> OLD.ticket_id)
       OR NOT (NEW.start_intent_key <=> OLD.start_intent_key)
       OR NOT (NEW.definition_id <=> OLD.definition_id)
       OR NOT (NEW.workflow_version_id <=> OLD.workflow_version_id)
       OR NOT (NEW.workflow_version_no <=> OLD.workflow_version_no)
       OR NOT (NEW.input_snapshot_json <=> OLD.input_snapshot_json)
       OR NOT (NEW.input_snapshot_sha256 <=> OLD.input_snapshot_sha256)
       OR NOT (NEW.condition_result <=> OLD.condition_result)
       OR NOT (NEW.started_at <=> OLD.started_at)
       OR NOT (NEW.created_at <=> OLD.created_at)
       OR NOT (NEW.created_by_type <=> OLD.created_by_type)
       OR NOT (NEW.created_by_id <=> OLD.created_by_id) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'workflow instance identity is immutable';
    END IF;

    IF OLD.status IN ('COMPLETED_PASSED', 'COMPLETED_REJECTED') THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'workflow instance is terminal';
    END IF;

    IF OLD.status = 'RUNNING'
       AND OLD.current_level = 1
       AND NEW.status = 'RUNNING'
       AND NEW.current_level = 2 THEN
        SELECT COUNT(*) INTO approved_level_one_count
        FROM approval_task
        WHERE tenant_id = OLD.tenant_id
          AND workflow_instance_id = OLD.workflow_instance_id
          AND level_no = 1
          AND status = 'APPROVED';

        IF approved_level_one_count <> 1 THEN
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = 'level one approval is required';
        END IF;
    END IF;

    IF OLD.status = 'RUNNING' THEN
        IF NEW.status = 'RUNNING'
           AND NEW.current_level <> OLD.current_level
           AND NOT (OLD.current_level = 1 AND NEW.current_level = 2) THEN
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = 'invalid workflow level transition';
        END IF;

        IF NEW.status = 'COMPLETED_PASSED' THEN
            IF OLD.current_level = 1 THEN
                SIGNAL SQLSTATE '45000'
                    SET MESSAGE_TEXT = 'level two approval is required';
            END IF;

            SELECT COUNT(*) INTO approved_level_one_count
            FROM approval_task
            WHERE tenant_id = OLD.tenant_id
              AND workflow_instance_id = OLD.workflow_instance_id
              AND level_no = 2
              AND status = 'APPROVED';

            IF approved_level_one_count <> 1 THEN
                SIGNAL SQLSTATE '45000'
                    SET MESSAGE_TEXT = 'level two approval is required';
            END IF;
        END IF;

        IF NEW.status = 'COMPLETED_REJECTED' THEN
            SELECT COUNT(*) INTO approved_level_one_count
            FROM approval_task
            WHERE tenant_id = OLD.tenant_id
              AND workflow_instance_id = OLD.workflow_instance_id
              AND status = 'REJECTED';

            IF approved_level_one_count <> 1 THEN
                SIGNAL SQLSTATE '45000'
                    SET MESSAGE_TEXT = 'rejected approval task is required';
            END IF;
        END IF;
    END IF;
END//

CREATE TRIGGER trg_wf_task_before_insert
BEFORE INSERT ON approval_task
FOR EACH ROW
BEGIN
    DECLARE instance_count INT DEFAULT 0;
    DECLARE instance_status VARCHAR(32) DEFAULT NULL;
    DECLARE instance_level TINYINT UNSIGNED DEFAULT NULL;
    DECLARE approved_level_one_count INT DEFAULT 0;

    SELECT COUNT(*), MAX(status), MAX(current_level)
      INTO instance_count, instance_status, instance_level
    FROM workflow_instance
    WHERE tenant_id = NEW.tenant_id
      AND workflow_instance_id = NEW.workflow_instance_id;

    IF instance_count <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'workflow instance does not exist';
    END IF;

    IF NEW.status <> 'PENDING' THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'approval task must be born pending';
    END IF;

    IF NEW.level_no = 1 THEN
        IF instance_status <> 'RUNNING' OR instance_level <> 1 THEN
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = 'level one task is out of sequence';
        END IF;
    ELSEIF NEW.level_no = 2 THEN
        SELECT COUNT(*) INTO approved_level_one_count
        FROM approval_task
        WHERE tenant_id = NEW.tenant_id
          AND workflow_instance_id = NEW.workflow_instance_id
          AND level_no = 1
          AND status = 'APPROVED';

        IF instance_status <> 'RUNNING'
           OR instance_level <> 2
           OR approved_level_one_count <> 1 THEN
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = 'level two task is out of sequence';
        END IF;
    ELSE
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'unsupported approval level';
    END IF;
END//

CREATE TRIGGER trg_wf_task_before_update
BEFORE UPDATE ON approval_task
FOR EACH ROW
BEGIN
    IF NOT (NEW.tenant_id <=> OLD.tenant_id)
       OR NOT (NEW.approval_task_id <=> OLD.approval_task_id)
       OR NOT (NEW.workflow_instance_id <=> OLD.workflow_instance_id)
       OR NOT (NEW.level_no <=> OLD.level_no)
       OR NOT (NEW.approver_user_id <=> OLD.approver_user_id)
       OR NOT (NEW.created_at <=> OLD.created_at)
       OR NOT (NEW.created_by_type <=> OLD.created_by_type)
       OR NOT (NEW.created_by_id <=> OLD.created_by_id) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'approval task identity is immutable';
    END IF;

    IF OLD.status IN ('APPROVED', 'REJECTED') THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'approval task is terminal';
    END IF;

    IF OLD.status <> 'PENDING'
       OR NEW.status NOT IN ('APPROVED', 'REJECTED') THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'invalid approval task transition';
    END IF;
END//

DELIMITER ;
