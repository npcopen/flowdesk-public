-- FlowDesk Ticket service physical baseline.
-- This migration owns only flowdesk_ticket objects. Cross-schema references remain opaque IDs.

CREATE TABLE sla_policy (
    tenant_id BINARY(16) NOT NULL,
    sla_policy_id BINARY(16) NOT NULL,
    policy_code VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    policy_name VARCHAR(128) NOT NULL,
    first_response_seconds INT UNSIGNED NOT NULL,
    resolution_seconds INT UNSIGNED NOT NULL,
    first_response_reminder_before_seconds INT UNSIGNED NULL DEFAULT NULL,
    resolution_reminder_before_seconds INT UNSIGNED NULL DEFAULT NULL,
    is_incident_default TINYINT(1) NOT NULL DEFAULT 0,
    active_incident_default_guard TINYINT UNSIGNED
        GENERATED ALWAYS AS (
            CASE
                WHEN status = 'ACTIVE' AND is_incident_default = 1 THEN 1
                ELSE NULL
            END
        ) STORED,
    status VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'ACTIVE',
    lock_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by_type VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    created_by_id BINARY(16) NULL DEFAULT NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_by_type VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    updated_by_id BINARY(16) NULL DEFAULT NULL,

    CONSTRAINT pk_tkt_04 PRIMARY KEY (tenant_id, sla_policy_id),
    CONSTRAINT uk_tkt_05 UNIQUE KEY uk_tkt_05 (tenant_id, policy_code),
    KEY idx_tkt_06 (tenant_id, status, policy_name, sla_policy_id),
    CONSTRAINT uk_tkt_52 UNIQUE KEY uk_tkt_52 (tenant_id, active_incident_default_guard),

    CONSTRAINT ck_tkt_policy_status_default CHECK (
        status IN ('ACTIVE', 'INACTIVE')
        AND is_incident_default IN (0, 1)
    ),
    CONSTRAINT ck_tkt_policy_durations CHECK (
        first_response_seconds > 0
        AND resolution_seconds > 0
    ),
    CONSTRAINT ck_tkt_policy_reminders CHECK (
        (
            first_response_reminder_before_seconds IS NULL
            OR (
                first_response_reminder_before_seconds <= first_response_seconds
            )
        )
        AND (
            resolution_reminder_before_seconds IS NULL
            OR (
                resolution_reminder_before_seconds <= resolution_seconds
            )
        )
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;

CREATE TABLE service_catalog_item (
    tenant_id BINARY(16) NOT NULL,
    catalog_item_id BINARY(16) NOT NULL,
    item_code VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    item_name VARCHAR(128) NOT NULL,
    description VARCHAR(1000) NULL DEFAULT NULL,
    risk_level VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'NORMAL',
    requires_approval TINYINT(1) NOT NULL DEFAULT 0,
    workflow_definition_id BINARY(16) NULL DEFAULT NULL,
    workflow_version_id BINARY(16) NULL DEFAULT NULL,
    workflow_version_no INT UNSIGNED NULL DEFAULT NULL,
    sla_policy_id BINARY(16) NOT NULL,
    status VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'ACTIVE',
    lock_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by_type VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    created_by_id BINARY(16) NULL DEFAULT NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_by_type VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    updated_by_id BINARY(16) NULL DEFAULT NULL,

    CONSTRAINT pk_tkt_01 PRIMARY KEY (tenant_id, catalog_item_id),
    CONSTRAINT uk_tkt_02 UNIQUE KEY uk_tkt_02 (tenant_id, item_code),
    KEY idx_tkt_03 (tenant_id, status, risk_level, item_name, catalog_item_id),
    KEY idx_tkt_53 (tenant_id, sla_policy_id, catalog_item_id),

    CONSTRAINT ck_tkt_catalog_status_risk CHECK (
        status IN ('ACTIVE', 'INACTIVE')
        AND risk_level IN ('NORMAL', 'HIGH')
        AND requires_approval IN (0, 1)
    ),
    CONSTRAINT ck_tkt_catalog_approval_refs CHECK (
        (
            risk_level = 'NORMAL'
            AND requires_approval = 0
            AND workflow_definition_id IS NULL
            AND workflow_version_id IS NULL
            AND workflow_version_no IS NULL
        )
        OR (
            risk_level = 'HIGH'
            AND requires_approval = 1
            AND workflow_definition_id IS NOT NULL
            AND workflow_version_id IS NOT NULL
            AND workflow_version_no IS NOT NULL
            AND workflow_version_no > 0
        )
    ),
    CONSTRAINT ck_tkt_catalog_actor CHECK (
        created_by_type IN ('USER', 'SERVICE', 'INTEGRATION', 'SCHEDULER')
        AND updated_by_type IN ('USER', 'SERVICE', 'INTEGRATION', 'SCHEDULER')
        AND (created_by_type <> 'USER' OR created_by_id IS NOT NULL)
        AND (updated_by_type <> 'USER' OR updated_by_id IS NOT NULL)
    ),

    CONSTRAINT fk_tkt_catalog_policy
        FOREIGN KEY (tenant_id, sla_policy_id)
        REFERENCES sla_policy (tenant_id, sla_policy_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;

CREATE TABLE ticket (
    tenant_id BINARY(16) NOT NULL,
    ticket_id BINARY(16) NOT NULL,
    ticket_no VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    creation_intent_key BINARY(32) NOT NULL,
    origin_type VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    catalog_item_id BINARY(16) NULL DEFAULT NULL,
    requester_user_id BINARY(16) NULL DEFAULT NULL,
    previous_attempt_ticket_id BINARY(16) NULL DEFAULT NULL,
    subject VARCHAR(255) NOT NULL,
    description TEXT NOT NULL,
    status VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    assignee_user_id BINARY(16) NULL DEFAULT NULL,
    workflow_definition_id BINARY(16) NULL DEFAULT NULL,
    workflow_version_id BINARY(16) NULL DEFAULT NULL,
    workflow_version_no INT UNSIGNED NULL DEFAULT NULL,
    first_processable_at TIMESTAMP(6) NULL DEFAULT NULL,
    resolved_at TIMESTAMP(6) NULL DEFAULT NULL,
    closed_at TIMESTAMP(6) NULL DEFAULT NULL,
    last_reopened_at TIMESTAMP(6) NULL DEFAULT NULL,
    reopen_count INT UNSIGNED NOT NULL DEFAULT 0,
    terminal_at TIMESTAMP(6) NULL DEFAULT NULL,
    lock_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by_type VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    created_by_id BINARY(16) NULL DEFAULT NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_by_type VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    updated_by_id BINARY(16) NULL DEFAULT NULL,

    CONSTRAINT pk_tkt_07 PRIMARY KEY (tenant_id, ticket_id),
    CONSTRAINT uk_tkt_08 UNIQUE KEY uk_tkt_08 (tenant_id, ticket_no),
    CONSTRAINT uk_tkt_09 UNIQUE KEY uk_tkt_09 (tenant_id, creation_intent_key),
    KEY idx_tkt_10 (tenant_id, status, updated_at, ticket_id),
    KEY idx_tkt_11 (tenant_id, requester_user_id, created_at, ticket_id),
    KEY idx_tkt_12 (tenant_id, assignee_user_id, status, updated_at, ticket_id),
    KEY idx_tkt_13 (tenant_id, previous_attempt_ticket_id, created_at, ticket_id),
    KEY idx_tkt_54 (tenant_id, catalog_item_id, created_at, ticket_id),

    CONSTRAINT ck_tkt_status CHECK (
        status IN (
            'AWAITING_APPROVAL',
            'PENDING_ASSIGNMENT',
            'ASSIGNED',
            'IN_PROGRESS',
            'RESOLVED',
            'CLOSED',
            'APPROVAL_REJECTED'
        )
    ),
    CONSTRAINT ck_tkt_origin_refs CHECK (
        (
            origin_type = 'MANUAL'
            AND catalog_item_id IS NOT NULL
            AND requester_user_id IS NOT NULL
            AND created_by_type = 'USER'
            AND created_by_id = requester_user_id
        )
        OR (
            origin_type = 'INCIDENT'
            AND catalog_item_id IS NULL
            AND requester_user_id IS NULL
            AND created_by_type = 'SERVICE'
        )
    ),
    CONSTRAINT ck_tkt_workflow_refs CHECK (
        (
            workflow_definition_id IS NULL
            AND workflow_version_id IS NULL
            AND workflow_version_no IS NULL
        )
        OR (
            origin_type = 'MANUAL'
            AND workflow_definition_id IS NOT NULL
            AND workflow_version_id IS NOT NULL
            AND workflow_version_no IS NOT NULL
            AND workflow_version_no > 0
        )
    ),
    CONSTRAINT ck_tkt_terminal_times CHECK (
        ((status IN ('CLOSED', 'APPROVAL_REJECTED')) = (terminal_at IS NOT NULL))
        AND ((status = 'CLOSED') = (closed_at IS NOT NULL))
        AND (status <> 'CLOSED' OR terminal_at = closed_at)
        AND (status NOT IN ('RESOLVED', 'CLOSED') OR resolved_at IS NOT NULL)
        AND (status <> 'CLOSED' OR resolved_at <= closed_at)
        AND (
            status NOT IN ('PENDING_ASSIGNMENT', 'ASSIGNED', 'IN_PROGRESS', 'RESOLVED', 'CLOSED')
            OR first_processable_at IS NOT NULL
        )
        AND (
            status NOT IN ('AWAITING_APPROVAL', 'APPROVAL_REJECTED')
            OR first_processable_at IS NULL
        )
    ),
    CONSTRAINT ck_tkt_reopen_fields CHECK (
        (
            reopen_count = 0
            AND last_reopened_at IS NULL
        )
        OR (
            reopen_count > 0
            AND origin_type = 'MANUAL'
            AND last_reopened_at IS NOT NULL
            AND resolved_at IS NOT NULL
            AND last_reopened_at >= resolved_at
        )
    ),

    CONSTRAINT fk_tkt_ticket_catalog
        FOREIGN KEY (tenant_id, catalog_item_id)
        REFERENCES service_catalog_item (tenant_id, catalog_item_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_tkt_ticket_previous_attempt
        FOREIGN KEY (tenant_id, previous_attempt_ticket_id)
        REFERENCES ticket (tenant_id, ticket_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;

CREATE TABLE incident_ticket_mapping (
    tenant_id BINARY(16) NOT NULL,
    incident_id BINARY(16) NOT NULL,
    ticket_id BINARY(16) NOT NULL,
    creation_intent_key BINARY(32) NOT NULL,
    established_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by_type VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'SERVICE',
    created_by_id BINARY(16) NULL DEFAULT NULL,

    CONSTRAINT pk_tkt_14 PRIMARY KEY (tenant_id, incident_id),
    CONSTRAINT uk_tkt_15 UNIQUE KEY uk_tkt_15 (tenant_id, ticket_id),
    KEY idx_tkt_16 (tenant_id, established_at, incident_id),

    CONSTRAINT fk_tkt_mapping_ticket
        FOREIGN KEY (tenant_id, ticket_id)
        REFERENCES ticket (tenant_id, ticket_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;

CREATE TABLE workflow_start_link (
    tenant_id BINARY(16) NOT NULL,
    ticket_id BINARY(16) NOT NULL,
    start_intent_key BINARY(32) NOT NULL,
    status VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'REQUESTED',
    workflow_instance_id BINARY(16) NULL DEFAULT NULL,
    requested_at TIMESTAMP(6) NOT NULL,
    established_at TIMESTAMP(6) NULL DEFAULT NULL,
    recovery_required_at TIMESTAMP(6) NULL DEFAULT NULL,
    recovery_reason_code VARCHAR(96) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL DEFAULT NULL,
    retry_count INT UNSIGNED NOT NULL DEFAULT 0,
    last_attempt_at TIMESTAMP(6) NULL DEFAULT NULL,
    lock_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by_type VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    created_by_id BINARY(16) NULL DEFAULT NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_by_type VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    updated_by_id BINARY(16) NULL DEFAULT NULL,

    CONSTRAINT pk_tkt_17 PRIMARY KEY (tenant_id, ticket_id),
    CONSTRAINT uk_tkt_18 UNIQUE KEY uk_tkt_18 (tenant_id, start_intent_key),
    KEY idx_tkt_19 (tenant_id, status, updated_at, ticket_id),

    CONSTRAINT ck_tkt_workflow_link_state_times CHECK (
        status IN ('REQUESTED', 'ESTABLISHED', 'RECOVERY_REQUIRED')
        AND (
            (
                status = 'ESTABLISHED'
                AND workflow_instance_id IS NOT NULL
                AND established_at IS NOT NULL
            )
            OR (
                status <> 'ESTABLISHED'
                AND workflow_instance_id IS NULL
                AND established_at IS NULL
            )
        )
        AND (established_at IS NULL OR established_at >= requested_at)
        AND (
            recovery_required_at IS NULL
            AND recovery_reason_code IS NULL
            OR recovery_required_at IS NOT NULL
            AND recovery_reason_code IS NOT NULL
            AND CHAR_LENGTH(TRIM(recovery_reason_code)) > 0
            AND recovery_required_at >= requested_at
        )
        AND (
            status <> 'RECOVERY_REQUIRED'
            OR (recovery_required_at IS NOT NULL AND recovery_reason_code IS NOT NULL)
        )
    ),
    CONSTRAINT ck_tkt_workflow_link_attempts CHECK (
        (
            retry_count = 0
            AND last_attempt_at IS NULL
        )
        OR (
            retry_count > 0
            AND last_attempt_at IS NOT NULL
            AND last_attempt_at >= requested_at
        )
        AND (status <> 'RECOVERY_REQUIRED' OR retry_count > 0)
    ),

    CONSTRAINT fk_tkt_workflow_link_ticket
        FOREIGN KEY (tenant_id, ticket_id)
        REFERENCES ticket (tenant_id, ticket_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;

CREATE TABLE ticket_comment (
    tenant_id BINARY(16) NOT NULL,
    comment_id BINARY(16) NOT NULL,
    ticket_id BINARY(16) NOT NULL,
    body TEXT NOT NULL,
    correlation_id BINARY(16) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by_type VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    created_by_id BINARY(16) NOT NULL,

    CONSTRAINT pk_tkt_20 PRIMARY KEY (tenant_id, comment_id),
    KEY idx_tkt_21 (tenant_id, ticket_id, created_at, comment_id),

    CONSTRAINT ck_tkt_comment_actor_body CHECK (
        created_by_type = 'USER'
        AND created_by_id IS NOT NULL
        AND CHAR_LENGTH(TRIM(body)) > 0
    ),

    CONSTRAINT fk_tkt_comment_ticket
        FOREIGN KEY (tenant_id, ticket_id)
        REFERENCES ticket (tenant_id, ticket_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;

CREATE TABLE ticket_timeline_entry (
    tenant_id BINARY(16) NOT NULL,
    timeline_id BINARY(16) NOT NULL,
    ticket_id BINARY(16) NOT NULL,
    effect_key BINARY(32) NOT NULL,
    event_type VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    summary VARCHAR(1000) NOT NULL,
    related_ref_type VARCHAR(48) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL DEFAULT NULL,
    related_ref_id BINARY(16) NULL DEFAULT NULL,
    actor_type VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    actor_id BINARY(16) NULL DEFAULT NULL,
    occurred_at TIMESTAMP(6) NOT NULL,
    correlation_id BINARY(16) NOT NULL,
    causation_id BINARY(16) NULL DEFAULT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT pk_tkt_22 PRIMARY KEY (tenant_id, timeline_id),
    CONSTRAINT uk_tkt_23 UNIQUE KEY uk_tkt_23 (tenant_id, ticket_id, effect_key),
    KEY idx_tkt_24 (tenant_id, ticket_id, occurred_at, timeline_id),
    KEY idx_tkt_25 (tenant_id, related_ref_type, related_ref_id, occurred_at, timeline_id),

    CONSTRAINT ck_tkt_timeline_refs_actor CHECK (
        (
            related_ref_type IS NULL
            AND related_ref_id IS NULL
            OR related_ref_type IS NOT NULL
            AND related_ref_id IS NOT NULL
        )
        AND actor_type IN ('USER', 'SERVICE', 'INTEGRATION', 'SCHEDULER')
        AND (actor_type <> 'USER' OR actor_id IS NOT NULL)
    ),

    CONSTRAINT fk_tkt_timeline_ticket
        FOREIGN KEY (tenant_id, ticket_id)
        REFERENCES ticket (tenant_id, ticket_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;

CREATE TABLE sla_clock (
    tenant_id BINARY(16) NOT NULL,
    sla_clock_id BINARY(16) NOT NULL,
    ticket_id BINARY(16) NOT NULL,
    sla_policy_id BINARY(16) NOT NULL,
    clock_type VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    status VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'RUNNING',
    started_at TIMESTAMP(6) NOT NULL,
    deadline_at TIMESTAMP(6) NOT NULL,
    reminder_due_at TIMESTAMP(6) NULL DEFAULT NULL,
    policy_duration_seconds INT UNSIGNED NOT NULL,
    policy_reminder_before_seconds INT UNSIGNED NULL DEFAULT NULL,
    milestone_at TIMESTAMP(6) NULL DEFAULT NULL,
    breached_at TIMESTAMP(6) NULL DEFAULT NULL,
    satisfied_at TIMESTAMP(6) NULL DEFAULT NULL,
    lock_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by_type VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    created_by_id BINARY(16) NULL DEFAULT NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_by_type VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    updated_by_id BINARY(16) NULL DEFAULT NULL,

    CONSTRAINT pk_tkt_26 PRIMARY KEY (tenant_id, sla_clock_id),
    CONSTRAINT uk_tkt_27 UNIQUE KEY uk_tkt_27 (tenant_id, ticket_id, clock_type),
    KEY idx_tkt_28 (tenant_id, status, deadline_at, sla_clock_id),
    KEY idx_tkt_29 (tenant_id, ticket_id, status, sla_clock_id),
    KEY idx_tkt_55 (tenant_id, sla_policy_id, created_at, sla_clock_id),

    CONSTRAINT ck_tkt_clock_type_status CHECK (
        clock_type IN ('FIRST_RESPONSE', 'RESOLUTION')
        AND status IN ('RUNNING', 'SATISFIED_ON_TIME', 'BREACHED', 'SATISFIED_LATE')
        AND created_by_type = 'SERVICE'
        AND updated_by_type IN ('USER', 'SERVICE', 'SCHEDULER')
        AND (updated_by_type <> 'USER' OR updated_by_id IS NOT NULL)
    ),
    CONSTRAINT ck_tkt_clock_deadline CHECK (
        policy_duration_seconds > 0
        AND deadline_at > started_at
        AND deadline_at = TIMESTAMPADD(SECOND, policy_duration_seconds, started_at)
    ),
    CONSTRAINT ck_tkt_clock_reminder CHECK (
        (
            policy_reminder_before_seconds IS NULL
            AND reminder_due_at IS NULL
        )
        OR (
            policy_reminder_before_seconds IS NOT NULL
            AND policy_reminder_before_seconds <= policy_duration_seconds
            AND reminder_due_at IS NOT NULL
            AND reminder_due_at >= started_at
            AND reminder_due_at <= deadline_at
            AND reminder_due_at = TIMESTAMPADD(
                SECOND,
                -1 * CAST(policy_reminder_before_seconds AS SIGNED),
                deadline_at
            )
        )
    ),
    CONSTRAINT ck_tkt_clock_state_times CHECK (
        (
            status = 'RUNNING'
            AND milestone_at IS NULL
            AND breached_at IS NULL
            AND satisfied_at IS NULL
        )
        OR (
            status = 'SATISFIED_ON_TIME'
            AND milestone_at IS NOT NULL
            AND milestone_at <= deadline_at
            AND breached_at IS NULL
            AND satisfied_at IS NOT NULL
            AND satisfied_at >= milestone_at
        )
        OR (
            status = 'BREACHED'
            AND milestone_at IS NULL
            AND breached_at IS NOT NULL
            AND breached_at >= deadline_at
            AND satisfied_at IS NULL
        )
        OR (
            status = 'SATISFIED_LATE'
            AND milestone_at IS NOT NULL
            AND milestone_at > deadline_at
            AND breached_at IS NOT NULL
            AND breached_at >= deadline_at
            AND satisfied_at IS NOT NULL
            AND satisfied_at >= milestone_at
        )
    ),

    CONSTRAINT fk_tkt_clock_ticket
        FOREIGN KEY (tenant_id, ticket_id)
        REFERENCES ticket (tenant_id, ticket_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_tkt_clock_policy
        FOREIGN KEY (tenant_id, sla_policy_id)
        REFERENCES sla_policy (tenant_id, sla_policy_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;

CREATE TABLE sla_trigger (
    tenant_id BINARY(16) NOT NULL,
    sla_trigger_id BINARY(16) NOT NULL,
    sla_clock_id BINARY(16) NOT NULL,
    stage VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    effect_key BINARY(32) NOT NULL,
    due_at TIMESTAMP(6) NOT NULL,
    triggered_at TIMESTAMP(6) NOT NULL,
    publication_key BINARY(32) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by_type VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'SCHEDULER',
    created_by_id BINARY(16) NULL DEFAULT NULL,

    CONSTRAINT pk_tkt_30 PRIMARY KEY (tenant_id, sla_trigger_id),
    CONSTRAINT uk_tkt_31 UNIQUE KEY uk_tkt_31 (tenant_id, sla_clock_id, stage),
    CONSTRAINT uk_tkt_32 UNIQUE KEY uk_tkt_32 (tenant_id, effect_key),
    KEY idx_tkt_33 (tenant_id, triggered_at, sla_trigger_id),

    CONSTRAINT ck_tkt_trigger_stage_times CHECK (
        stage IN ('REMINDER', 'BREACH')
        AND triggered_at >= due_at
        AND created_by_type = 'SCHEDULER'
    ),

    CONSTRAINT fk_tkt_trigger_clock
        FOREIGN KEY (tenant_id, sla_clock_id)
        REFERENCES sla_clock (tenant_id, sla_clock_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;

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
    created_by_type VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    created_by_id BINARY(16) NULL DEFAULT NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_by_type VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'SERVICE',
    updated_by_id BINARY(16) NULL DEFAULT NULL,

    CONSTRAINT pk_tkt_34 PRIMARY KEY (tenant_id, outbox_id),
    CONSTRAINT uk_tkt_35 UNIQUE KEY uk_tkt_35 (tenant_id, message_id),
    CONSTRAINT uk_tkt_36 UNIQUE KEY uk_tkt_36 (tenant_id, publication_key),
    KEY idx_tkt_37 (tenant_id, status, next_attempt_at, outbox_id),
    KEY idx_tkt_38 (tenant_id, aggregate_type, aggregate_id, created_at, outbox_id),

    CONSTRAINT ck_tkt_outbox_state_times CHECK (
        status IN ('PENDING', 'PUBLISHING', 'PUBLISHED', 'RECOVERY_REQUIRED')
        AND ((status = 'PUBLISHED') = (broker_confirmed_at IS NOT NULL))
        AND (broker_confirmed_at IS NULL OR broker_confirmed_at >= created_at)
        AND created_by_type IN ('USER', 'SERVICE', 'SCHEDULER')
        AND updated_by_type IN ('USER', 'SERVICE')
        AND (created_by_type <> 'USER' OR created_by_id IS NOT NULL)
        AND (updated_by_type <> 'USER' OR updated_by_id IS NOT NULL)
    ),
    CONSTRAINT ck_tkt_outbox_attempt_lease CHECK (
        (
            locked_by IS NULL
            AND locked_at IS NULL
            OR locked_by IS NOT NULL
            AND locked_at IS NOT NULL
        )
        AND ((status = 'PUBLISHING') = (locked_by IS NOT NULL))
        AND (locked_at IS NULL OR locked_at >= created_at)
        AND next_attempt_at >= created_at
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;

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

    CONSTRAINT pk_tkt_39 PRIMARY KEY (tenant_id, inbox_id),
    CONSTRAINT uk_tkt_40 UNIQUE KEY uk_tkt_40 (tenant_id, producer_service, message_id),
    CONSTRAINT uk_tkt_41 UNIQUE KEY uk_tkt_41 (
        tenant_id,
        producer_service,
        contract_ref,
        business_intent_key
    ),
    KEY idx_tkt_42 (tenant_id, status, updated_at, inbox_id),
    KEY idx_tkt_43 (tenant_id, result_type, result_id, processed_at, inbox_id),

    CONSTRAINT ck_tkt_inbox_state_result CHECK (
        (
            status = 'PROCESSED'
            AND result_type IS NOT NULL
            AND result_id IS NOT NULL
            AND processed_at IS NOT NULL
        )
        OR (
            status = 'REJECTED'
            AND result_type IS NULL
            AND result_id IS NULL
            AND processed_at IS NOT NULL
        )
        OR (
            status = 'RECOVERY_REQUIRED'
            AND result_type IS NULL
            AND result_id IS NULL
            AND processed_at IS NULL
        )
    ),
    CONSTRAINT ck_tkt_inbox_attempt_times CHECK (
        attempt_count >= 1
        AND contract_version >= 1
        AND last_attempt_at >= first_received_at
        AND (processed_at IS NULL OR processed_at >= first_received_at)
        AND updated_by_type IN ('USER', 'SERVICE')
        AND (updated_by_type <> 'USER' OR updated_by_id IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;

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

    CONSTRAINT pk_tkt_44 PRIMARY KEY (tenant_id, audit_id),
    KEY idx_tkt_45 (tenant_id, target_type, target_id, occurred_at, audit_id),
    KEY idx_tkt_46 (tenant_id, actor_type, actor_id, occurred_at, audit_id),
    KEY idx_tkt_47 (tenant_id, correlation_id, occurred_at, audit_id),

    CONSTRAINT ck_tkt_audit_outcome CHECK (
        outcome IN ('SUCCEEDED', 'REJECTED', 'FAILED')
        AND (outcome <> 'SUCCEEDED' OR error_code IS NULL)
        AND (outcome <> 'FAILED' OR error_code IS NOT NULL)
    ),
    CONSTRAINT ck_tkt_audit_actor CHECK (
        actor_type IN ('USER', 'SERVICE', 'INTEGRATION', 'SCHEDULER')
        AND (actor_type <> 'USER' OR actor_id IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;

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

    CONSTRAINT pk_tkt_48 PRIMARY KEY (tenant_id, recovery_id),
    CONSTRAINT uk_tkt_49 UNIQUE KEY uk_tkt_49 (tenant_id, direction, source_record_id),
    KEY idx_tkt_50 (tenant_id, state, next_attempt_at, recovery_id),
    KEY idx_tkt_51 (tenant_id, dead_lettered_at, recovery_id),

    CONSTRAINT ck_tkt_recovery_state_times CHECK (
        direction IN ('OUTBOX', 'INBOX')
        AND state IN ('OPEN', 'REPLAYING', 'RESOLVED')
        AND CHAR_LENGTH(last_error_code) > 0
        AND (dead_lettered_at IS NULL OR dead_lettered_at <= recovery_required_at)
        AND ((state = 'RESOLVED') = (resolved_at IS NOT NULL))
        AND (resolved_at IS NULL OR resolved_at >= recovery_required_at)
        AND (next_attempt_at IS NULL OR next_attempt_at >= recovery_required_at)
        AND (state = 'OPEN' OR next_attempt_at IS NULL)
        AND (
            (
                replay_count = 0
                AND last_replayed_at IS NULL
                AND last_replayed_by_type IS NULL
                AND last_replayed_by_id IS NULL
                AND last_replay_reason IS NULL
            )
            OR (
                replay_count > 0
                AND last_replayed_at IS NOT NULL
                AND last_replayed_at >= recovery_required_at
                AND last_replayed_by_type IN ('USER', 'SERVICE', 'SCHEDULER')
                AND (last_replayed_by_type <> 'USER' OR last_replayed_by_id IS NOT NULL)
                AND last_replay_reason IS NOT NULL
                AND CHAR_LENGTH(TRIM(last_replay_reason)) > 0
            )
        )
        AND (state <> 'REPLAYING' OR replay_count > 0)
        AND updated_by_type IN ('USER', 'SERVICE', 'SCHEDULER')
        AND (updated_by_type <> 'USER' OR updated_by_id IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;

DELIMITER $$

CREATE TRIGGER trg_tkt_ticket_bu_guard
BEFORE UPDATE ON ticket
FOR EACH ROW
BEGIN
    IF NOT (NEW.tenant_id <=> OLD.tenant_id)
        OR NOT (NEW.ticket_id <=> OLD.ticket_id)
        OR NOT (NEW.ticket_no <=> OLD.ticket_no)
        OR NOT (NEW.creation_intent_key <=> OLD.creation_intent_key)
        OR NOT (NEW.origin_type <=> OLD.origin_type)
        OR NOT (NEW.catalog_item_id <=> OLD.catalog_item_id)
        OR NOT (NEW.requester_user_id <=> OLD.requester_user_id)
        OR NOT (NEW.previous_attempt_ticket_id <=> OLD.previous_attempt_ticket_id)
        OR NOT (NEW.workflow_definition_id <=> OLD.workflow_definition_id)
        OR NOT (NEW.workflow_version_id <=> OLD.workflow_version_id)
        OR NOT (NEW.workflow_version_no <=> OLD.workflow_version_no)
        OR NOT (NEW.created_at <=> OLD.created_at)
        OR NOT (NEW.created_by_type <=> OLD.created_by_type)
        OR NOT (NEW.created_by_id <=> OLD.created_by_id) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'ticket identity, ownership, intent, snapshot and creation fields are immutable';
    END IF;

    IF OLD.status IN ('CLOSED', 'APPROVAL_REJECTED') AND NEW.status <> OLD.status THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'terminal ticket status cannot be left';
    END IF;

    IF OLD.status = 'RESOLVED' AND NEW.status IN ('IN_PROGRESS', 'CLOSED') THEN
        IF OLD.origin_type <> 'MANUAL'
            OR NEW.updated_by_type <> 'USER'
            OR NEW.updated_by_id IS NULL
            OR NOT (NEW.updated_by_id <=> OLD.requester_user_id) THEN
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = 'resolved ticket reopen or close requires the original manual requester';
        END IF;
    END IF;

    IF OLD.first_processable_at IS NOT NULL
        AND NOT (NEW.first_processable_at <=> OLD.first_processable_at) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'ticket first_processable_at is immutable once set';
    END IF;
END$$

CREATE TRIGGER trg_tkt_workflow_start_link_bu_guard
BEFORE UPDATE ON workflow_start_link
FOR EACH ROW
BEGIN
    IF NOT (NEW.tenant_id <=> OLD.tenant_id)
        OR NOT (NEW.ticket_id <=> OLD.ticket_id)
        OR NOT (NEW.start_intent_key <=> OLD.start_intent_key)
        OR NOT (NEW.requested_at <=> OLD.requested_at)
        OR NOT (NEW.created_at <=> OLD.created_at)
        OR NOT (NEW.created_by_type <=> OLD.created_by_type)
        OR NOT (NEW.created_by_id <=> OLD.created_by_id) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'workflow start link identity, intent and creation fields are immutable';
    END IF;

    IF OLD.workflow_instance_id IS NOT NULL
        AND NOT (NEW.workflow_instance_id <=> OLD.workflow_instance_id) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'established workflow instance reference is immutable';
    END IF;

    IF OLD.established_at IS NOT NULL
        AND NOT (NEW.established_at <=> OLD.established_at) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'workflow start establishment time is immutable once set';
    END IF;

    IF OLD.status = 'ESTABLISHED' AND NEW.status <> 'ESTABLISHED' THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'established workflow start link cannot be downgraded';
    END IF;

    IF OLD.status = 'RECOVERY_REQUIRED' AND NEW.status = 'REQUESTED' THEN
        IF NEW.updated_by_type <> 'USER'
            OR NEW.updated_by_id IS NULL
            OR NEW.recovery_reason_code IS NULL
            OR CHAR_LENGTH(TRIM(NEW.recovery_reason_code)) = 0 THEN
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = 'workflow start replay requires an identified user and retained recovery reason';
        END IF;
    END IF;
END$$

CREATE TRIGGER trg_tkt_sla_clock_bu_guard
BEFORE UPDATE ON sla_clock
FOR EACH ROW
BEGIN
    IF NOT (NEW.tenant_id <=> OLD.tenant_id)
        OR NOT (NEW.sla_clock_id <=> OLD.sla_clock_id)
        OR NOT (NEW.ticket_id <=> OLD.ticket_id)
        OR NOT (NEW.sla_policy_id <=> OLD.sla_policy_id)
        OR NOT (NEW.clock_type <=> OLD.clock_type)
        OR NOT (NEW.started_at <=> OLD.started_at)
        OR NOT (NEW.deadline_at <=> OLD.deadline_at)
        OR NOT (NEW.reminder_due_at <=> OLD.reminder_due_at)
        OR NOT (NEW.policy_duration_seconds <=> OLD.policy_duration_seconds)
        OR NOT (NEW.policy_reminder_before_seconds <=> OLD.policy_reminder_before_seconds)
        OR NOT (NEW.created_at <=> OLD.created_at)
        OR NOT (NEW.created_by_type <=> OLD.created_by_type)
        OR NOT (NEW.created_by_id <=> OLD.created_by_id) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'SLA clock identity, ownership, schedule, snapshot and creation fields are immutable';
    END IF;

    IF NEW.status <> OLD.status AND NOT (
        OLD.status = 'RUNNING'
        AND NEW.status IN ('SATISFIED_ON_TIME', 'BREACHED')
        OR OLD.status = 'BREACHED'
        AND NEW.status = 'SATISFIED_LATE'
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'illegal SLA clock state transition';
    END IF;
END$$

DELIMITER ;
