-- M6 changes an IAM negative qualification from a deterministic rejection to a
-- recoverable external qualification failure.  Take over only the historical
-- M5 rows produced by that exact legacy classification.  All source envelope
-- identity and bytes remain immutable.

START TRANSACTION;

INSERT INTO message_recovery
    (tenant_id, recovery_id, direction, source_record_id, business_intent_key,
     state, attempt_count, next_attempt_at, last_error_code, last_error_summary,
     dead_lettered_at, recovery_required_at, replay_count, lock_version,
     created_at, updated_at, updated_by_type, updated_by_id)
SELECT i.tenant_id, UUID_TO_BIN(UUID()), 'INBOX', i.inbox_id,
       i.business_intent_key, 'OPEN', i.attempt_count, NULL,
       'WORKFLOW_APPROVER_NOT_ELIGIBLE',
       'Historical M5 IAM denial reclassified as recoverable by M6.',
       i.processed_at, UTC_TIMESTAMP(6), 0, 0,
       UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 'SERVICE', NULL
FROM inbox_message i
LEFT JOIN message_recovery r
  ON r.tenant_id = i.tenant_id
 AND r.direction = 'INBOX'
 AND r.source_record_id = i.inbox_id
WHERE i.status = 'REJECTED'
  AND i.last_error_code = 'WORKFLOW_APPROVER_NOT_ELIGIBLE'
  AND i.contract_ref = 'flowdesk.workflow.start.v1'
  AND i.contract_version = 1
  AND r.recovery_id IS NULL;

INSERT INTO audit_record
    (tenant_id, audit_id, action_code, target_type, target_id, outcome,
     actor_type, actor_id, reason, error_code, correlation_id, causation_id,
     occurred_at)
SELECT i.tenant_id, UUID_TO_BIN(UUID()),
       'WORKFLOW_START_IAM_DENIAL_TAKEN_OVER', 'INBOX_MESSAGE', i.inbox_id,
       'SUCCEEDED', 'SERVICE', NULL,
       'M6 reclassified the historical IAM denial as recoverable.', NULL,
       i.correlation_id, i.causation_id, UTC_TIMESTAMP(6)
FROM inbox_message i
WHERE i.status = 'REJECTED'
  AND i.last_error_code = 'WORKFLOW_APPROVER_NOT_ELIGIBLE'
  AND i.contract_ref = 'flowdesk.workflow.start.v1'
  AND i.contract_version = 1;

UPDATE inbox_message
SET status = 'RECOVERY_REQUIRED',
    result_type = NULL,
    result_id = NULL,
    processed_at = NULL,
    lock_version = lock_version + 1,
    updated_at = UTC_TIMESTAMP(6),
    updated_by_type = 'SERVICE',
    updated_by_id = NULL
WHERE status = 'REJECTED'
  AND last_error_code = 'WORKFLOW_APPROVER_NOT_ELIGIBLE'
  AND contract_ref = 'flowdesk.workflow.start.v1'
  AND contract_version = 1;

COMMIT;

-- Fail closed if a target row could not be taken over.  The M6 consumer also
-- repeats this assertion during opt-in startup.
SET @fd_wf_m6_takeover_sql = IF(
    EXISTS (
        SELECT 1
        FROM inbox_message i
        WHERE i.status = 'REJECTED'
          AND i.last_error_code = 'WORKFLOW_APPROVER_NOT_ELIGIBLE'
          AND i.contract_ref = 'flowdesk.workflow.start.v1'
          AND i.contract_version = 1
        LIMIT 1
    ),
    'SELECT __flowdesk_m6_iam_takeover_incomplete__ FROM inbox_message LIMIT 0',
    'SELECT 1'
);
PREPARE fd_wf_m6_takeover_stmt FROM @fd_wf_m6_takeover_sql;
EXECUTE fd_wf_m6_takeover_stmt;
DEALLOCATE PREPARE fd_wf_m6_takeover_stmt;
SET @fd_wf_m6_takeover_sql = NULL;
