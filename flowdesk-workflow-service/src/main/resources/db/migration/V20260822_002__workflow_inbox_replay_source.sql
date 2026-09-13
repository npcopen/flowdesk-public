-- Persist the validated Workflow-start source envelope for DLQ acceptance and
-- same-path replay. The Workflow consumer is not enabled before this forward
-- migration, so no legacy Inbox payload requires an unsafe synthetic backfill.
--
-- MySQL DDL auto-commits. Fail before the first ALTER when either source table
-- contains facts that cannot be reconstructed. The unsafe PREPARE branch names
-- an intentionally absent column, producing error 1054 without requiring the
-- migrator to own CREATE ROUTINE or broader runtime privileges.

SET @fd_wf_m5_preflight_sql = IF(
    EXISTS (SELECT 1 FROM inbox_message LIMIT 1)
        OR EXISTS (
            SELECT 1
            FROM message_recovery
            WHERE replay_count > 0
            LIMIT 1
        ),
    'SELECT __flowdesk_m5_upgrade_blocked_existing_rows__ FROM inbox_message LIMIT 0',
    'SELECT 1'
);
PREPARE fd_wf_m5_preflight_stmt FROM @fd_wf_m5_preflight_sql;
EXECUTE fd_wf_m5_preflight_stmt;
DEALLOCATE PREPARE fd_wf_m5_preflight_stmt;
SET @fd_wf_m5_preflight_sql = NULL;

ALTER TABLE inbox_message
    ADD COLUMN body_bytes LONGBLOB NOT NULL AFTER body_sha256,
    ADD COLUMN correlation_id BINARY(16) NOT NULL AFTER body_bytes,
    ADD COLUMN causation_id BINARY(16) NULL DEFAULT NULL AFTER correlation_id,
    ADD COLUMN occurred_at TIMESTAMP(6) NOT NULL AFTER causation_id,
    ADD CONSTRAINT ck_wf_inbox_m5_requires_empty CHECK (FALSE);

-- The constant-false guard is part of the same atomic ALTER as the NOT NULL
-- columns, so a legacy insert racing the preflight also blocks the migration.
ALTER TABLE inbox_message
    DROP CHECK ck_wf_inbox_m5_requires_empty;

ALTER TABLE message_recovery
    ADD COLUMN last_replay_correlation_id BINARY(16) NULL DEFAULT NULL
        AFTER last_replay_reason,
    ADD CONSTRAINT ck_wf_recovery_replay_correlation CHECK (
        (replay_count = 0 AND last_replay_correlation_id IS NULL)
        OR (replay_count > 0 AND last_replay_correlation_id IS NOT NULL)
    );
