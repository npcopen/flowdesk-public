-- Apply as the bootstrap DBA after the Ticket Flyway baseline has completed.
-- The runtime principal is created by bootstrap; this script only narrows its Ticket-schema DML.

-- Read-only migration metadata is required by the service readiness probe.
GRANT SELECT ON flowdesk_ticket.flyway_schema_history
    TO 'fd_ticket_runtime'@'%';

GRANT SELECT, INSERT
    ON `flowdesk_ticket`.`sla_policy`
    TO 'fd_ticket_runtime'@'%';
GRANT UPDATE (
        `policy_name`,
        `first_response_seconds`,
        `resolution_seconds`,
        `first_response_reminder_before_seconds`,
        `resolution_reminder_before_seconds`,
        `is_incident_default`,
        `status`,
        `lock_version`,
        `updated_at`,
        `updated_by_type`,
        `updated_by_id`
    )
    ON `flowdesk_ticket`.`sla_policy`
    TO 'fd_ticket_runtime'@'%';

GRANT SELECT, INSERT
    ON `flowdesk_ticket`.`service_catalog_item`
    TO 'fd_ticket_runtime'@'%';
GRANT UPDATE (
        `item_name`,
        `description`,
        `risk_level`,
        `requires_approval`,
        `workflow_definition_id`,
        `workflow_version_id`,
        `workflow_version_no`,
        `sla_policy_id`,
        `status`,
        `lock_version`,
        `updated_at`,
        `updated_by_type`,
        `updated_by_id`
    )
    ON `flowdesk_ticket`.`service_catalog_item`
    TO 'fd_ticket_runtime'@'%';

GRANT SELECT, INSERT
    ON `flowdesk_ticket`.`ticket`
    TO 'fd_ticket_runtime'@'%';
GRANT UPDATE (
        `status`,
        `assignee_user_id`,
        `first_processable_at`,
        `resolved_at`,
        `closed_at`,
        `last_reopened_at`,
        `reopen_count`,
        `terminal_at`,
        `lock_version`,
        `updated_at`,
        `updated_by_type`,
        `updated_by_id`
    )
    ON `flowdesk_ticket`.`ticket`
    TO 'fd_ticket_runtime'@'%';

-- The allocator inserts one tenant/day row, reads the locked value, and only advances
-- the sequence/timestamp columns. It has no DELETE or DDL privilege.
GRANT SELECT (`tenant_id`, `business_date`, `sequence_value`)
    ON `flowdesk_ticket`.`ticket_number_counter`
    TO 'fd_ticket_runtime'@'%';
GRANT INSERT (`tenant_id`, `business_date`, `sequence_value`)
    ON `flowdesk_ticket`.`ticket_number_counter`
    TO 'fd_ticket_runtime'@'%';
GRANT UPDATE (`sequence_value`, `updated_at`)
    ON `flowdesk_ticket`.`ticket_number_counter`
    TO 'fd_ticket_runtime'@'%';

GRANT SELECT, INSERT
    ON `flowdesk_ticket`.`incident_ticket_mapping`
    TO 'fd_ticket_runtime'@'%';

GRANT SELECT, INSERT
    ON `flowdesk_ticket`.`workflow_start_link`
    TO 'fd_ticket_runtime'@'%';
GRANT UPDATE (
        `status`,
        `workflow_instance_id`,
        `established_at`,
        `recovery_required_at`,
        `recovery_reason_code`,
        `retry_count`,
        `last_attempt_at`,
        `lock_version`,
        `updated_at`,
        `updated_by_type`,
        `updated_by_id`
    )
    ON `flowdesk_ticket`.`workflow_start_link`
    TO 'fd_ticket_runtime'@'%';

GRANT SELECT, INSERT
    ON `flowdesk_ticket`.`ticket_comment`
    TO 'fd_ticket_runtime'@'%';

GRANT SELECT, INSERT
    ON `flowdesk_ticket`.`ticket_timeline_entry`
    TO 'fd_ticket_runtime'@'%';

GRANT SELECT, INSERT
    ON `flowdesk_ticket`.`sla_clock`
    TO 'fd_ticket_runtime'@'%';
GRANT UPDATE (
        `status`,
        `milestone_at`,
        `breached_at`,
        `satisfied_at`,
        `lock_version`,
        `updated_at`,
        `updated_by_type`,
        `updated_by_id`
    )
    ON `flowdesk_ticket`.`sla_clock`
    TO 'fd_ticket_runtime'@'%';

GRANT SELECT, INSERT
    ON `flowdesk_ticket`.`sla_trigger`
    TO 'fd_ticket_runtime'@'%';

-- M8 concrete escalation routing and qualification facts.  Mapping identities,
-- purposes, effective timestamps and creation audit columns remain immutable.
GRANT SELECT, INSERT
    ON `flowdesk_ticket`.`sla_escalation_target`
    TO 'fd_ticket_runtime'@'%';
GRANT UPDATE (
        `status`,
        `qualification_version`,
        `qualification_checked_at`,
        `qualification_valid_until`,
        `qualification_state`,
        `qualification_reason_code`,
        `lock_version`,
        `updated_at`,
        `updated_by_type`,
        `updated_by_id`
    )
    ON `flowdesk_ticket`.`sla_escalation_target`
    TO 'fd_ticket_runtime'@'%';

GRANT SELECT, INSERT
    ON `flowdesk_ticket`.`sla_qualification_snapshot`
    TO 'fd_ticket_runtime'@'%';
GRANT UPDATE (
        `recipient_user_id`,
        `qualification_version`,
        `checked_at`,
        `valid_until`,
        `eligible`,
        `reason_code`
    )
    ON `flowdesk_ticket`.`sla_qualification_snapshot`
    TO 'fd_ticket_runtime'@'%';

-- Redis rebuild ownership is coordinated in MySQL.  The tenant key and creation
-- audit facts are immutable; workers may only advance the fenced lease state.
GRANT SELECT, INSERT
    ON `flowdesk_ticket`.`sla_index_rebuild_lease`
    TO 'fd_ticket_runtime'@'%';
GRANT UPDATE (
        `fencing_epoch`,
        `run_id`,
        `high_water_updated_at`,
        `high_water_sla_clock_id`,
        `expires_at`,
        `state`,
        `last_error_code`,
        `lock_version`,
        `updated_at`,
        `updated_by_type`
    )
    ON `flowdesk_ticket`.`sla_index_rebuild_lease`
    TO 'fd_ticket_runtime'@'%';

-- Trigger recipients are an append-only snapshot once an effect is created.
GRANT SELECT, INSERT
    ON `flowdesk_ticket`.`sla_trigger_recipient`
    TO 'fd_ticket_runtime'@'%';

GRANT SELECT, INSERT
    ON `flowdesk_ticket`.`outbox_event`
    TO 'fd_ticket_runtime'@'%';
GRANT UPDATE (
        `status`,
        `attempt_count`,
        `next_attempt_at`,
        `locked_by`,
        `locked_at`,
        `last_error_code`,
        `broker_confirmed_at`,
        `lock_version`,
        `updated_at`,
        `updated_by_type`,
        `updated_by_id`
    )
    ON `flowdesk_ticket`.`outbox_event`
    TO 'fd_ticket_runtime'@'%';

GRANT SELECT, INSERT
    ON `flowdesk_ticket`.`inbox_message`
    TO 'fd_ticket_runtime'@'%';
GRANT UPDATE (
        `status`,
        `result_type`,
        `result_id`,
        `attempt_count`,
        `last_error_code`,
        `last_attempt_at`,
        `processed_at`,
        `lock_version`,
        `updated_at`,
        `updated_by_type`,
        `updated_by_id`
    )
    ON `flowdesk_ticket`.`inbox_message`
    TO 'fd_ticket_runtime'@'%';

GRANT SELECT, INSERT
    ON `flowdesk_ticket`.`audit_record`
    TO 'fd_ticket_runtime'@'%';

GRANT SELECT, INSERT
    ON `flowdesk_ticket`.`message_recovery`
    TO 'fd_ticket_runtime'@'%';
GRANT UPDATE (
        `state`,
        `attempt_count`,
        `next_attempt_at`,
        `last_error_code`,
        `last_error_summary`,
        `dead_lettered_at`,
        `replay_count`,
        `last_replayed_at`,
        `last_replayed_by_type`,
        `last_replayed_by_id`,
        `last_replay_reason`,
        `last_replay_correlation_id`,
        `resolved_at`,
        `lock_version`,
        `updated_at`,
        `updated_by_type`,
        `updated_by_id`
    )
    ON `flowdesk_ticket`.`message_recovery`
    TO 'fd_ticket_runtime'@'%';
