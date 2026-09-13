-- M8 Ticket facts: concrete SLA escalation recipient, qualification snapshots and
-- the MySQL coordination record used when the Redis due-index is rebuilt.
-- No IAM foreign key is declared intentionally: Ticket owns these facts and IAM
-- remains a separate schema/service boundary.

CREATE TABLE sla_escalation_target (
    tenant_id BINARY(16) NOT NULL,
    target_id BINARY(16) NOT NULL,
    purpose VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    recipient_user_id BINARY(16) NOT NULL,
    status VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'ACTIVE',
    effective_at TIMESTAMP(6) NOT NULL,
    qualification_version BIGINT UNSIGNED NULL DEFAULT NULL,
    qualification_checked_at TIMESTAMP(6) NULL DEFAULT NULL,
    qualification_valid_until TIMESTAMP(6) NULL DEFAULT NULL,
    qualification_state VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'UNKNOWN',
    qualification_reason_code VARCHAR(96) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL DEFAULT NULL,
    active_sla_guard TINYINT UNSIGNED GENERATED ALWAYS AS (
        CASE WHEN purpose = 'SLA' AND status = 'ACTIVE' THEN 1 ELSE NULL END
    ) STORED,
    lock_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by_type VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'SERVICE',
    created_by_id BINARY(16) NULL DEFAULT NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_by_type VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'SERVICE',
    updated_by_id BINARY(16) NULL DEFAULT NULL,

    CONSTRAINT pk_tkt_m8_sla_target PRIMARY KEY (tenant_id, target_id),
    CONSTRAINT uk_tkt_m8_sla_target_active UNIQUE KEY (tenant_id, active_sla_guard),
    KEY idx_tkt_m8_sla_target_recipient (tenant_id, recipient_user_id, purpose, status),
    CONSTRAINT ck_tkt_m8_sla_target_values CHECK (
        purpose = 'SLA'
        AND status IN ('ACTIVE', 'INACTIVE')
        AND qualification_state IN ('UNKNOWN', 'ELIGIBLE', 'INELIGIBLE', 'DEGRADED')
        AND CHAR_LENGTH(TRIM(qualification_reason_code)) <= 96
        AND (qualification_valid_until IS NULL OR qualification_checked_at IS NOT NULL)
        AND created_by_type IN ('SERVICE', 'USER', 'SCHEDULER')
        AND updated_by_type IN ('SERVICE', 'USER', 'SCHEDULER')
        AND (created_by_type <> 'USER' OR created_by_id IS NOT NULL)
        AND (updated_by_type <> 'USER' OR updated_by_id IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;

CREATE TABLE sla_qualification_snapshot (
    tenant_id BINARY(16) NOT NULL,
    target_id BINARY(16) NOT NULL,
    recipient_user_id BINARY(16) NOT NULL,
    purpose VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    qualification_version BIGINT UNSIGNED NOT NULL,
    checked_at TIMESTAMP(6) NOT NULL,
    valid_until TIMESTAMP(6) NOT NULL,
    eligible TINYINT(1) NOT NULL,
    reason_code VARCHAR(96) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by_type VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'SERVICE',
    created_by_id BINARY(16) NULL DEFAULT NULL,
    CONSTRAINT pk_tkt_m8_sla_qualification PRIMARY KEY (tenant_id, target_id),
    KEY idx_tkt_m8_sla_qualification_expiry
        (tenant_id, purpose, eligible, valid_until, target_id),
    CONSTRAINT ck_tkt_m8_sla_qualification_values CHECK (
        purpose = 'SLA'
        AND eligible IN (0, 1)
        AND valid_until >= checked_at
        AND CHAR_LENGTH(reason_code) > 0
        AND created_by_type IN ('SERVICE', 'SCHEDULER')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;

CREATE TABLE sla_index_rebuild_lease (
    tenant_id BINARY(16) NOT NULL,
    fencing_epoch BIGINT UNSIGNED NOT NULL DEFAULT 0,
    run_id BINARY(16) NULL DEFAULT NULL,
    high_water_updated_at TIMESTAMP(6) NULL DEFAULT NULL,
    high_water_sla_clock_id BINARY(16) NULL DEFAULT NULL,
    expires_at TIMESTAMP(6) NULL DEFAULT NULL,
    state VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'IDLE',
    last_error_code VARCHAR(96) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL DEFAULT NULL,
    lock_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_by_type VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'SCHEDULER',
    updated_by_id BINARY(16) NULL DEFAULT NULL,
    CONSTRAINT pk_tkt_m8_sla_rebuild_lease PRIMARY KEY (tenant_id),
    KEY idx_tkt_m8_sla_rebuild_expiry (state, expires_at, tenant_id),
    CONSTRAINT ck_tkt_m8_sla_rebuild_values CHECK (
        state IN ('IDLE', 'RUNNING', 'CATCHING_UP', 'SWAPPING', 'DEGRADED')
        AND ((state = 'IDLE' AND run_id IS NULL) OR state <> 'IDLE')
        AND (run_id IS NULL OR expires_at IS NOT NULL)
        AND (high_water_sla_clock_id IS NULL OR high_water_updated_at IS NOT NULL)
        AND updated_by_type IN ('SERVICE', 'SCHEDULER')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;

-- Immutable concrete recipient snapshot for each SLA trigger.  The trigger's
-- root publication key is stable, while this table freezes the resolved UserRefs
-- so later assignment/target changes cannot rewrite an already-created effect.
CREATE TABLE sla_trigger_recipient (
    tenant_id BINARY(16) NOT NULL,
    sla_trigger_id BINARY(16) NOT NULL,
    recipient_user_id BINARY(16) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by_type VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'SCHEDULER',
    created_by_id BINARY(16) NULL DEFAULT NULL,
    CONSTRAINT pk_tkt_m8_sla_trigger_recipient
        PRIMARY KEY (tenant_id, sla_trigger_id, recipient_user_id),
    KEY idx_tkt_m8_sla_trigger_recipient_trigger
        (tenant_id, sla_trigger_id, recipient_user_id),
    CONSTRAINT ck_tkt_m8_sla_trigger_recipient_values CHECK (
        recipient_user_id <> 0x00000000000000000000000000000000
        AND created_by_type IN ('SERVICE', 'SCHEDULER')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;

ALTER TABLE sla_clock
    ADD KEY idx_tkt_m8_sla_clock_keyset
        (tenant_id, status, updated_at, sla_clock_id);

ALTER TABLE sla_trigger
    ADD KEY idx_tkt_m8_sla_trigger_stage
        (tenant_id, sla_clock_id, stage, triggered_at);

ALTER TABLE ticket_timeline_entry
    ADD KEY idx_tkt_m8_sla_timeline
        (tenant_id, ticket_id, event_type, related_ref_type, related_ref_id, occurred_at, timeline_id);
