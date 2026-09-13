-- M7 activates the first Alert consumer and requires exact same-path Inbox replay.
-- No executable Alert Inbox consumer existed before this forward migration.

SET @fd_alt_m7_preflight_sql = IF(
    EXISTS (SELECT 1 FROM inbox_message LIMIT 1)
        OR EXISTS (SELECT 1 FROM message_recovery WHERE direction='INBOX' OR replay_count>0 LIMIT 1)
        OR EXISTS (SELECT 1 FROM alert_event WHERE alert_cycle_ref IS NULL LIMIT 1),
    'SELECT __flowdesk_m7_alert_upgrade_blocked__ FROM inbox_message LIMIT 0',
    'SELECT 1'
);
PREPARE fd_alt_m7_preflight_stmt FROM @fd_alt_m7_preflight_sql;
EXECUTE fd_alt_m7_preflight_stmt;
DEALLOCATE PREPARE fd_alt_m7_preflight_stmt;
SET @fd_alt_m7_preflight_sql = NULL;

ALTER TABLE alert_event
    MODIFY alert_cycle_ref VARCHAR(128) CHARACTER SET utf8mb4
        COLLATE utf8mb4_0900_bin NOT NULL,
    ADD KEY idx_alt_m7_cycle
        (tenant_id, fingerprint, alert_cycle_ref, received_at, alert_event_id);

ALTER TABLE inbox_message
    ADD COLUMN body_bytes LONGBLOB NOT NULL AFTER body_sha256,
    ADD COLUMN correlation_id BINARY(16) NOT NULL AFTER body_bytes,
    ADD COLUMN causation_id BINARY(16) NULL DEFAULT NULL AFTER correlation_id,
    ADD COLUMN occurred_at TIMESTAMP(6) NOT NULL AFTER causation_id,
    ADD CONSTRAINT ck_alt_inbox_m7_requires_empty CHECK (FALSE);

ALTER TABLE inbox_message DROP CHECK ck_alt_inbox_m7_requires_empty;

ALTER TABLE message_recovery
    ADD COLUMN last_replay_correlation_id BINARY(16) NULL DEFAULT NULL
        AFTER last_replay_reason,
    ADD CONSTRAINT ck_alt_recovery_replay_correlation CHECK (
        (replay_count=0 AND last_replay_correlation_id IS NULL)
        OR (replay_count>0 AND last_replay_correlation_id IS NOT NULL)
    );
