-- Persist the original Workflow->Ticket source envelope required by M6 Inbox
-- recovery.  No Ticket Inbox consumer exists before this migration, therefore
-- any pre-existing Inbox fact cannot be reconstructed safely and blocks DDL.

SET @fd_tkt_m6_preflight_sql = IF(
    EXISTS (SELECT 1 FROM inbox_message LIMIT 1)
        OR EXISTS (
            SELECT 1 FROM message_recovery
            WHERE direction = 'INBOX' OR replay_count > 0
            LIMIT 1
        ),
    'SELECT __flowdesk_m6_ticket_inbox_upgrade_blocked__ FROM inbox_message LIMIT 0',
    'SELECT 1'
);
PREPARE fd_tkt_m6_preflight_stmt FROM @fd_tkt_m6_preflight_sql;
EXECUTE fd_tkt_m6_preflight_stmt;
DEALLOCATE PREPARE fd_tkt_m6_preflight_stmt;
SET @fd_tkt_m6_preflight_sql = NULL;

ALTER TABLE inbox_message
    ADD COLUMN body_bytes LONGBLOB NOT NULL AFTER body_sha256,
    ADD COLUMN correlation_id BINARY(16) NOT NULL AFTER body_bytes,
    ADD COLUMN causation_id BINARY(16) NULL DEFAULT NULL AFTER correlation_id,
    ADD COLUMN occurred_at TIMESTAMP(6) NOT NULL AFTER causation_id,
    ADD CONSTRAINT ck_tkt_inbox_m6_requires_empty CHECK (FALSE);

ALTER TABLE inbox_message
    DROP CHECK ck_tkt_inbox_m6_requires_empty;

ALTER TABLE message_recovery
    ADD COLUMN last_replay_correlation_id BINARY(16) NULL DEFAULT NULL
        AFTER last_replay_reason,
    ADD CONSTRAINT ck_tkt_recovery_replay_correlation CHECK (
        (replay_count = 0 AND last_replay_correlation_id IS NULL)
        OR (replay_count > 0 AND last_replay_correlation_id IS NOT NULL)
    );
