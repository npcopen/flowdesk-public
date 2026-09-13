-- M8 forward migration.  The baseline only stored a body digest, which is not
-- sufficient to replay a message without guessing its route or identity.
-- Existing rows are deliberately refused: bytes and envelope fields cannot be
-- reconstructed safely.  The migrator therefore fails closed before its first
-- DDL statement when a non-empty legacy inbox/recovery is found.
SET @fd_not_m8_preflight_sql = IF(
    EXISTS (SELECT 1 FROM inbox_message LIMIT 1)
        OR EXISTS (SELECT 1 FROM message_recovery LIMIT 1),
    'SELECT __flowdesk_m8_notification_upgrade_blocked__ FROM inbox_message LIMIT 0',
    'SELECT 1'
);
PREPARE fd_not_m8_preflight_stmt FROM @fd_not_m8_preflight_sql;
EXECUTE fd_not_m8_preflight_stmt;
DEALLOCATE PREPARE fd_not_m8_preflight_stmt;
SET @fd_not_m8_preflight_sql = NULL;

ALTER TABLE inbox_message
    ADD COLUMN body_bytes LONGBLOB NOT NULL AFTER body_sha256,
    ADD COLUMN topic VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL
        AFTER body_bytes,
    ADD COLUMN consumer_group VARCHAR(160) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL
        AFTER topic,
    ADD COLUMN logical_destination VARCHAR(192) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL
        AFTER consumer_group,
    ADD COLUMN message_key VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL
        AFTER message_id,
    ADD COLUMN direction VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL
        DEFAULT 'INBOX' AFTER message_key,
    ADD COLUMN publication_key BINARY(32) NOT NULL AFTER business_intent_key,
    ADD COLUMN aggregate_type VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL
        AFTER contract_version,
    ADD COLUMN aggregate_id BINARY(16) NOT NULL AFTER aggregate_type,
    ADD COLUMN correlation_id BINARY(16) NOT NULL AFTER body_bytes,
    ADD COLUMN causation_id BINARY(16) NULL DEFAULT NULL AFTER correlation_id,
    ADD COLUMN occurred_at TIMESTAMP(6) NOT NULL AFTER causation_id;

ALTER TABLE message_recovery
    ADD COLUMN last_replay_correlation_id BINARY(16) NULL DEFAULT NULL
        AFTER last_replay_reason;

ALTER TABLE inbox_message
    ADD KEY idx_not_m8_route (tenant_id, topic, consumer_group, message_id),
    ADD KEY idx_not_m8_publication (tenant_id, publication_key),
    ADD CONSTRAINT ck_not_m8_inbox_source CHECK (
        direction = 'INBOX'
        AND OCTET_LENGTH(body_bytes) BETWEEN 1 AND 65536
        AND OCTET_LENGTH(body_sha256) = 32
        AND CHAR_LENGTH(message_key) > 0
        AND CHAR_LENGTH(topic) > 0
        AND CHAR_LENGTH(consumer_group) > 0
        AND CHAR_LENGTH(logical_destination) > 0
        AND CHAR_LENGTH(aggregate_type) > 0
        AND aggregate_id IS NOT NULL
        AND correlation_id IS NOT NULL
        AND occurred_at IS NOT NULL
    );

ALTER TABLE message_recovery
    ADD CONSTRAINT ck_not_m8_replay_correlation CHECK (
        (replay_count = 0 AND last_replay_correlation_id IS NULL)
        OR (replay_count > 0 AND last_replay_correlation_id IS NOT NULL)
    );
