-- FlowDesk Notification schema baseline for MySQL 8.4.x.
-- The connection default schema is flowdesk_notification. No cross-schema object is referenced.

CREATE TABLE notification (
    tenant_id BINARY(16) NOT NULL,
    notification_id BINARY(16) NOT NULL,
    recipient_user_id BINARY(16) NOT NULL,
    notification_kind VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    source_service VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    source_effect_key BINARY(32) NOT NULL,
    source_ref_type VARCHAR(48) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    source_ref_id BINARY(16) NOT NULL,
    title VARCHAR(255) NOT NULL,
    body VARCHAR(2000) NOT NULL,
    state VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'UNREAD',
    read_at TIMESTAMP(6) NULL DEFAULT NULL,
    lock_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by_type VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'SERVICE',
    created_by_id BINARY(16) NULL DEFAULT NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_by_type VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'SERVICE',
    updated_by_id BINARY(16) NULL DEFAULT NULL,

    -- IDX-NOT-01
    CONSTRAINT pk_not_notification PRIMARY KEY (tenant_id, notification_id),
    -- IDX-NOT-02
    CONSTRAINT uk_not_notification_effect UNIQUE KEY idx_not_02
        (tenant_id, recipient_user_id, source_service, source_effect_key, notification_kind),
    -- IDX-NOT-03
    KEY idx_not_03 (tenant_id, recipient_user_id, state, created_at, notification_id),
    -- IDX-NOT-04
    KEY idx_not_04
        (tenant_id, source_service, source_ref_type, source_ref_id, created_at, notification_id),
    -- IDX-NOT-18
    KEY idx_not_18 (tenant_id, recipient_user_id, created_at, notification_id),

    CONSTRAINT ck_not_state_read CHECK (
        (state = 'UNREAD' AND read_at IS NULL)
        OR (state = 'READ' AND read_at IS NOT NULL)
    ),
    CONSTRAINT ck_not_source_actor CHECK (
        CHAR_LENGTH(notification_kind) > 0
        AND source_service IN ('flowdesk-ticket-service', 'flowdesk-workflow-service')
        AND CHAR_LENGTH(source_ref_type) > 0
        AND CHAR_LENGTH(TRIM(title)) > 0
        AND CHAR_LENGTH(TRIM(body)) > 0
        AND created_by_type = 'SERVICE'
        AND updated_by_type IN ('SERVICE', 'USER')
        AND (
            updated_by_type <> 'USER'
            OR (updated_by_id IS NOT NULL AND updated_by_id = recipient_user_id)
        )
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

    -- IDX-NOT-05
    CONSTRAINT pk_not_inbox_message PRIMARY KEY (tenant_id, inbox_id),
    -- IDX-NOT-06
    CONSTRAINT uk_not_inbox_message_id UNIQUE KEY idx_not_06
        (tenant_id, producer_service, message_id),
    -- IDX-NOT-07
    CONSTRAINT uk_not_inbox_business_intent UNIQUE KEY idx_not_07
        (tenant_id, producer_service, contract_ref, business_intent_key),
    -- IDX-NOT-08
    KEY idx_not_08 (tenant_id, status, updated_at, inbox_id),
    -- IDX-NOT-09
    KEY idx_not_09 (tenant_id, result_type, result_id, processed_at, inbox_id),

    CONSTRAINT ck_not_inbox_state_result CHECK (
        status IN ('PROCESSED', 'REJECTED', 'RECOVERY_REQUIRED')
        AND (
            (status = 'PROCESSED'
                AND processed_at IS NOT NULL
                AND result_type = 'NOTIFICATION'
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
    CONSTRAINT ck_not_inbox_attempt_times CHECK (
        producer_service IN ('flowdesk-ticket-service', 'flowdesk-workflow-service')
        AND attempt_count >= 1
        AND contract_version >= 1
        AND last_attempt_at >= first_received_at
        AND (processed_at IS NULL OR processed_at >= first_received_at)
        AND updated_by_type IN ('SERVICE', 'USER', 'SCHEDULER')
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

    -- IDX-NOT-10
    CONSTRAINT pk_not_audit_record PRIMARY KEY (tenant_id, audit_id),
    -- IDX-NOT-11
    KEY idx_not_11 (tenant_id, target_type, target_id, occurred_at, audit_id),
    -- IDX-NOT-12
    KEY idx_not_12 (tenant_id, actor_type, actor_id, occurred_at, audit_id),
    -- IDX-NOT-13
    KEY idx_not_13 (tenant_id, correlation_id, occurred_at, audit_id),

    CONSTRAINT ck_not_audit_outcome_actor CHECK (
        outcome IN ('SUCCEEDED', 'REJECTED', 'FAILED')
        AND actor_type IN ('USER', 'SERVICE', 'SCHEDULER')
        AND CHAR_LENGTH(action_code) > 0
        AND CHAR_LENGTH(target_type) > 0
    )
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE message_recovery (
    tenant_id BINARY(16) NOT NULL,
    recovery_id BINARY(16) NOT NULL,
    direction VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'INBOX',
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

    -- IDX-NOT-14
    CONSTRAINT pk_not_message_recovery PRIMARY KEY (tenant_id, recovery_id),
    -- IDX-NOT-15
    CONSTRAINT uk_not_recovery_source UNIQUE KEY idx_not_15
        (tenant_id, direction, source_record_id),
    -- IDX-NOT-16
    KEY idx_not_16 (tenant_id, state, next_attempt_at, recovery_id),
    -- IDX-NOT-17
    KEY idx_not_17 (tenant_id, dead_lettered_at, recovery_id),

    CONSTRAINT ck_not_recovery_inbox_state_times CHECK (
        direction = 'INBOX'
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
