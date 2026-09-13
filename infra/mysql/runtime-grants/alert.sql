-- Execute as the bootstrap DBA after the Alert Flyway baseline has succeeded.
-- The runtime principal already exists; this script creates no account and contains no credential.

-- Read-only migration metadata is required by the service readiness probe.
GRANT SELECT ON flowdesk_alert.flyway_schema_history
    TO 'fd_alert_runtime'@'%';

GRANT SELECT, INSERT ON flowdesk_alert.monitoring_integration
    TO 'fd_alert_runtime'@'%';
GRANT UPDATE (
    integration_name,
    status,
    api_key_verifier,
    api_key_verifier_scheme,
    signing_scheme_version,
    secret_material_version,
    valid_from,
    valid_until,
    lock_version,
    updated_at,
    updated_by_type,
    updated_by_id
) ON flowdesk_alert.monitoring_integration
    TO 'fd_alert_runtime'@'%';

-- Append-only security and event facts.
GRANT SELECT, INSERT ON flowdesk_alert.webhook_nonce
    TO 'fd_alert_runtime'@'%';
GRANT SELECT, INSERT ON flowdesk_alert.alert_event
    TO 'fd_alert_runtime'@'%';

GRANT SELECT, INSERT ON flowdesk_alert.incident
    TO 'fd_alert_runtime'@'%';
GRANT UPDATE (
    status,
    last_event_id,
    event_count,
    last_event_at,
    recovered_at,
    recovery_event_id,
    recovery_review_state,
    summary,
    lock_version,
    updated_at,
    updated_by_type,
    updated_by_id
) ON flowdesk_alert.incident
    TO 'fd_alert_runtime'@'%';

GRANT SELECT, INSERT ON flowdesk_alert.incident_ticket_link
    TO 'fd_alert_runtime'@'%';
GRANT UPDATE (
    status,
    ticket_id,
    established_at,
    recovery_required_at,
    recovery_reason_code,
    retry_count,
    last_attempt_at,
    lock_version,
    updated_at,
    updated_by_type,
    updated_by_id
) ON flowdesk_alert.incident_ticket_link
    TO 'fd_alert_runtime'@'%';

GRANT SELECT, INSERT ON flowdesk_alert.outbox_event
    TO 'fd_alert_runtime'@'%';
GRANT UPDATE (
    status,
    attempt_count,
    next_attempt_at,
    locked_by,
    locked_at,
    last_error_code,
    broker_confirmed_at,
    lock_version,
    updated_at,
    updated_by_type,
    updated_by_id
) ON flowdesk_alert.outbox_event
    TO 'fd_alert_runtime'@'%';

GRANT SELECT, INSERT ON flowdesk_alert.inbox_message
    TO 'fd_alert_runtime'@'%';
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
) ON flowdesk_alert.inbox_message
    TO 'fd_alert_runtime'@'%';

-- Audit is append-only.
GRANT SELECT, INSERT ON flowdesk_alert.audit_record
    TO 'fd_alert_runtime'@'%';

GRANT SELECT, INSERT ON flowdesk_alert.message_recovery
    TO 'fd_alert_runtime'@'%';
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
    last_replay_correlation_id,
    resolved_at,
    lock_version,
    updated_at,
    updated_by_type,
    updated_by_id
) ON flowdesk_alert.message_recovery
    TO 'fd_alert_runtime'@'%';
