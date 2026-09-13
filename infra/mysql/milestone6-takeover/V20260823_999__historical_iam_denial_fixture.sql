-- Acceptance-only history inserted after the M5 replay-source migration and
-- before the production M6 takeover.  Two exact legacy targets (one per demo
-- tenant) are surrounded by near misses that must remain untouched.

INSERT INTO inbox_message
    (tenant_id, inbox_id, producer_service, message_id, business_intent_key,
     contract_ref, contract_version, body_sha256, body_bytes, correlation_id,
     causation_id, occurred_at, status, result_type, result_id, attempt_count,
     last_error_code, first_received_at, last_attempt_at, processed_at,
     lock_version, created_at, updated_at, updated_by_type, updated_by_id)
SELECT fixture.tenant_id, fixture.inbox_id, 'flowdesk-ticket-service',
       fixture.message_id, fixture.intent_key, fixture.contract_ref,
       fixture.contract_version, UNHEX(SHA2('{}', 256)), X'7B7D',
       fixture.correlation_id, NULL, UTC_TIMESTAMP(6) - INTERVAL 10 SECOND,
       'REJECTED', NULL, NULL, 1, fixture.error_code,
       UTC_TIMESTAMP(6) - INTERVAL 10 SECOND,
       UTC_TIMESTAMP(6) - INTERVAL 9 SECOND,
       UTC_TIMESTAMP(6) - INTERVAL 9 SECOND,
       0, UTC_TIMESTAMP(6) - INTERVAL 10 SECOND,
       UTC_TIMESTAMP(6) - INTERVAL 9 SECOND, 'SERVICE', NULL
FROM (
    SELECT UUID_TO_BIN('12d7dca8-380c-5a6c-84ae-953502959486') tenant_id,
           UUID_TO_BIN('a6000000-0000-4000-8000-000000000001') inbox_id,
           UUID_TO_BIN('a6000000-0000-4000-8000-000000000002') message_id,
           UNHEX(REPEAT('11', 32)) intent_key,
           'flowdesk.workflow.start.v1' contract_ref, 1 contract_version,
           'WORKFLOW_APPROVER_NOT_ELIGIBLE' error_code,
           UUID_TO_BIN('a6000000-0000-4000-8000-000000000003') correlation_id
    UNION ALL
    SELECT UUID_TO_BIN('12d7dca8-380c-5a6c-84ae-953502959486'),
           UUID_TO_BIN('a6000000-0000-4000-8000-000000000011'),
           UUID_TO_BIN('a6000000-0000-4000-8000-000000000012'),
           UNHEX(REPEAT('12', 32)), 'flowdesk.workflow.start.v1', 2,
           'WORKFLOW_APPROVER_NOT_ELIGIBLE',
           UUID_TO_BIN('a6000000-0000-4000-8000-000000000013')
    UNION ALL
    SELECT UUID_TO_BIN('12d7dca8-380c-5a6c-84ae-953502959486'),
           UUID_TO_BIN('a6000000-0000-4000-8000-000000000021'),
           UUID_TO_BIN('a6000000-0000-4000-8000-000000000022'),
           UNHEX(REPEAT('13', 32)), 'flowdesk.workflow.start.v1', 1,
           'WORKFLOW_VERSION_MISMATCH',
           UUID_TO_BIN('a6000000-0000-4000-8000-000000000023')
    UNION ALL
    SELECT UUID_TO_BIN('978d86fe-2cb8-5d4b-9968-e2f738a5c3e2'),
           UUID_TO_BIN('b6000000-0000-4000-8000-000000000001'),
           UUID_TO_BIN('b6000000-0000-4000-8000-000000000002'),
           UNHEX(REPEAT('21', 32)), 'flowdesk.workflow.start.v1', 1,
           'WORKFLOW_APPROVER_NOT_ELIGIBLE',
           UUID_TO_BIN('b6000000-0000-4000-8000-000000000003')
    UNION ALL
    SELECT UUID_TO_BIN('978d86fe-2cb8-5d4b-9968-e2f738a5c3e2'),
           UUID_TO_BIN('b6000000-0000-4000-8000-000000000011'),
           UUID_TO_BIN('b6000000-0000-4000-8000-000000000012'),
           UNHEX(REPEAT('22', 32)), 'flowdesk.workflow.start.v1', 2,
           'WORKFLOW_APPROVER_NOT_ELIGIBLE',
           UUID_TO_BIN('b6000000-0000-4000-8000-000000000013')
    UNION ALL
    SELECT UUID_TO_BIN('978d86fe-2cb8-5d4b-9968-e2f738a5c3e2'),
           UUID_TO_BIN('b6000000-0000-4000-8000-000000000021'),
           UUID_TO_BIN('b6000000-0000-4000-8000-000000000022'),
           UNHEX(REPEAT('23', 32)), 'flowdesk.workflow.start.v1', 1,
           'WORKFLOW_VERSION_MISMATCH',
           UUID_TO_BIN('b6000000-0000-4000-8000-000000000023')
) AS fixture;

INSERT INTO audit_record
    (tenant_id, audit_id, action_code, target_type, target_id, outcome,
     actor_type, actor_id, reason, error_code, correlation_id, causation_id,
     occurred_at)
SELECT tenant_id, UUID_TO_BIN(UUID()), 'WORKFLOW_START_REJECTED',
       'INBOX_MESSAGE', inbox_id, 'REJECTED', 'SERVICE', NULL,
       'Historical audit must be preserved.', last_error_code,
       correlation_id, causation_id, processed_at
FROM inbox_message
WHERE contract_ref='flowdesk.workflow.start.v1'
  AND contract_version=1
  AND last_error_code='WORKFLOW_APPROVER_NOT_ELIGIBLE';
