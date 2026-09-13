-- Run as the bootstrap DBA after the Workflow Flyway baseline has succeeded.
-- The runtime user is created by bootstrap; this script contains no secrets.

-- Read-only migration metadata is required by the service readiness probe.
GRANT SELECT ON flowdesk_workflow.flyway_schema_history
    TO 'fd_workflow_runtime'@'%';

GRANT SELECT, INSERT ON flowdesk_workflow.workflow_definition
    TO 'fd_workflow_runtime'@'%';
GRANT UPDATE (
    definition_name,
    lock_version,
    updated_at,
    updated_by_type,
    updated_by_id
) ON flowdesk_workflow.workflow_definition TO 'fd_workflow_runtime'@'%';

GRANT SELECT, INSERT ON flowdesk_workflow.workflow_draft
    TO 'fd_workflow_runtime'@'%';
GRANT UPDATE (
    status,
    condition_field_code,
    condition_operator,
    condition_value_type,
    condition_value_text,
    level1_approver_user_id,
    level2_approver_user_id,
    validation_state,
    validation_digest,
    validated_at,
    consumed_at,
    lock_version,
    updated_at,
    updated_by_type,
    updated_by_id
) ON flowdesk_workflow.workflow_draft TO 'fd_workflow_runtime'@'%';

GRANT SELECT, INSERT ON flowdesk_workflow.workflow_version
    TO 'fd_workflow_runtime'@'%';

GRANT SELECT, INSERT ON flowdesk_workflow.workflow_instance
    TO 'fd_workflow_runtime'@'%';
GRANT UPDATE (
    status,
    current_level,
    completed_at,
    lock_version,
    updated_at,
    updated_by_type,
    updated_by_id
) ON flowdesk_workflow.workflow_instance TO 'fd_workflow_runtime'@'%';

GRANT SELECT, INSERT ON flowdesk_workflow.approval_task
    TO 'fd_workflow_runtime'@'%';
GRANT UPDATE (
    approver_user_id,
    status,
    decision_type,
    decision_reason,
    decision_intent_key,
    decided_at,
    decided_by_user_id,
    lock_version,
    updated_at,
    updated_by_type,
    updated_by_id
) ON flowdesk_workflow.approval_task TO 'fd_workflow_runtime'@'%';

GRANT SELECT, INSERT ON flowdesk_workflow.workflow_approver_override
    TO 'fd_workflow_runtime'@'%';
GRANT UPDATE (approver_user_id, updated_at)
    ON flowdesk_workflow.workflow_approver_override TO 'fd_workflow_runtime'@'%';
GRANT SELECT, INSERT ON flowdesk_workflow.workflow_approver_change
    TO 'fd_workflow_runtime'@'%';

GRANT SELECT, INSERT ON flowdesk_workflow.outbox_event
    TO 'fd_workflow_runtime'@'%';
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
) ON flowdesk_workflow.outbox_event TO 'fd_workflow_runtime'@'%';

GRANT SELECT, INSERT ON flowdesk_workflow.inbox_message
    TO 'fd_workflow_runtime'@'%';
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
) ON flowdesk_workflow.inbox_message TO 'fd_workflow_runtime'@'%';

GRANT SELECT, INSERT ON flowdesk_workflow.audit_record
    TO 'fd_workflow_runtime'@'%';

GRANT SELECT, INSERT ON flowdesk_workflow.message_recovery
    TO 'fd_workflow_runtime'@'%';
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
) ON flowdesk_workflow.message_recovery TO 'fd_workflow_runtime'@'%';
