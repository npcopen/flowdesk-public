-- Execute as the bootstrap DBA after the Notification Flyway baseline has succeeded.
-- The principals already exist; this script contains no credential.

-- Read-only migration metadata is required by the service readiness probe.
GRANT SELECT ON flowdesk_notification.flyway_schema_history
    TO 'fd_notification_runtime'@'%';

GRANT SELECT, INSERT ON flowdesk_notification.notification
    TO 'fd_notification_runtime'@'%';
GRANT UPDATE (
    state,
    read_at,
    lock_version,
    updated_at,
    updated_by_type,
    updated_by_id
) ON flowdesk_notification.notification
    TO 'fd_notification_runtime'@'%';

-- Earlier local baselines granted table-level Inbox SELECT.  Remove that
-- broader legacy grant only when it exists, then add the M8 column allowlist
-- below.  The dynamic statement keeps a fresh bootstrap idempotent.
SET @fd_not_runtime_inbox_table_select = (
    SELECT COUNT(*)
    FROM information_schema.table_privileges
    WHERE grantee = '''fd_notification_runtime''@''%'''
      AND table_schema = 'flowdesk_notification'
      AND table_name = 'inbox_message'
      AND privilege_type = 'SELECT'
);
SET @fd_not_runtime_inbox_revoke_sql = IF(
    @fd_not_runtime_inbox_table_select > 0,
    'REVOKE SELECT ON flowdesk_notification.inbox_message FROM ''fd_notification_runtime''@''%''',
    'SELECT 1'
);
PREPARE fd_not_runtime_inbox_revoke_stmt FROM @fd_not_runtime_inbox_revoke_sql;
EXECUTE fd_not_runtime_inbox_revoke_stmt;
DEALLOCATE PREPARE fd_not_runtime_inbox_revoke_stmt;
SET @fd_not_runtime_inbox_table_select = NULL;
SET @fd_not_runtime_inbox_revoke_sql = NULL;

-- `body_bytes` is an immutable replay source.  The normal consumer persists it
-- but must never read it; human APIs and routine message handling use this
-- explicitly projected non-secret column set instead.
GRANT INSERT ON flowdesk_notification.inbox_message
    TO 'fd_notification_runtime'@'%';
GRANT SELECT (
    tenant_id,
    inbox_id,
    producer_service,
    message_id,
    message_key,
    business_intent_key,
    publication_key,
    contract_ref,
    contract_version,
    aggregate_type,
    aggregate_id,
    body_sha256,
    topic,
    consumer_group,
    logical_destination,
    direction,
    correlation_id,
    causation_id,
    occurred_at,
    status,
    result_type,
    result_id,
    attempt_count,
    last_error_code,
    first_received_at,
    last_attempt_at,
    processed_at,
    lock_version,
    created_at,
    updated_at
) ON flowdesk_notification.inbox_message
    TO 'fd_notification_runtime'@'%';
GRANT UPDATE (
    status,
    result_type,
    result_id,
    attempt_count,
    last_error_code,
    last_attempt_at,
    processed_at,
    lock_version,
    updated_at,
    updated_by_type,
    updated_by_id
) ON flowdesk_notification.inbox_message
    TO 'fd_notification_runtime'@'%';

-- Audit is append-only.
GRANT SELECT, INSERT ON flowdesk_notification.audit_record
    TO 'fd_notification_runtime'@'%';

GRANT SELECT, INSERT ON flowdesk_notification.message_recovery
    TO 'fd_notification_runtime'@'%';
GRANT UPDATE (
    state,
    attempt_count,
    next_attempt_at,
    last_error_code,
    last_error_summary,
    dead_lettered_at,
    replay_count,
    last_replayed_at,
    last_replayed_by_type,
    last_replayed_by_id,
    last_replay_reason,
    resolved_at,
    lock_version,
    updated_at,
    updated_by_type,
    updated_by_id
) ON flowdesk_notification.message_recovery
    TO 'fd_notification_runtime'@'%';

-- The recovery worker is deliberately narrower than the consumer account: it
-- may read the persisted source bytes and settle only its own Recovery state;
-- it cannot create Notifications, mutate Inbox facts, delete data, or access a
-- foreign schema.  Replay delivery itself returns through the normal consumer
-- transaction and principal.
GRANT SELECT (
    tenant_id,
    inbox_id,
    producer_service,
    message_id,
    message_key,
    business_intent_key,
    publication_key,
    contract_ref,
    contract_version,
    aggregate_type,
    aggregate_id,
    body_sha256,
    body_bytes,
    topic,
    consumer_group,
    logical_destination,
    direction,
    correlation_id,
    causation_id,
    occurred_at,
    status,
    result_type,
    result_id,
    attempt_count,
    last_error_code,
    first_received_at,
    last_attempt_at,
    processed_at,
    lock_version,
    created_at,
    updated_at
) ON flowdesk_notification.inbox_message
    TO 'fd_notification_recovery'@'%';

GRANT SELECT ON flowdesk_notification.message_recovery
    TO 'fd_notification_recovery'@'%';
GRANT UPDATE (
    state,
    next_attempt_at,
    last_error_code,
    last_error_summary,
    replay_count,
    last_replayed_at,
    last_replayed_by_type,
    last_replayed_by_id,
    last_replay_reason,
    last_replay_correlation_id,
    resolved_at,
    lock_version,
    updated_at,
    updated_by_type,
    updated_by_id
) ON flowdesk_notification.message_recovery
    TO 'fd_notification_recovery'@'%';

GRANT SELECT, INSERT ON flowdesk_notification.audit_record
    TO 'fd_notification_recovery'@'%';
