-- Acceptance-only M6 fixture.  This file is mounted only into the isolated
-- verification job; it is deliberately not a Flyway demo migration.
-- A HIGH-risk catalog is bound to a published version whose restricted rule
-- expects NORMAL, so a Ticket created through Gateway exercises condition=false.

START TRANSACTION;

INSERT INTO flowdesk_workflow.workflow_draft
    (tenant_id, draft_id, definition_id, status, condition_field_code,
     condition_operator, condition_value_type, condition_value_text,
     approval_level_count, level1_approver_user_id, level2_approver_user_id,
     validation_state, validation_digest, validated_at, consumed_at,
     lock_version, created_at, created_by_type, created_by_id, updated_at,
     updated_by_type, updated_by_id)
SELECT fixture.tenant_id,
       UUID_TO_BIN('ee856821-9666-4c27-a96f-61f10c609d38'),
       UUID_TO_BIN('0848bf3c-d603-5ece-9529-f9bba79f1498'),
       'CONSUMED', 'SERVICE_RISK_LEVEL', 'EQUALS', 'STRING', 'NORMAL', 2,
       UUID_TO_BIN('33f0b408-5548-5f2b-9865-f1dd288c0493'),
       UUID_TO_BIN('fdecef4b-2a69-538e-9fa5-249048115d3d'),
       'VALID', UNHEX(fixture.digest_hex),
       UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 0, UTC_TIMESTAMP(6),
       'SERVICE', NULL, UTC_TIMESTAMP(6), 'SERVICE', NULL
FROM (
    SELECT UUID_TO_BIN('12d7dca8-380c-5a6c-84ae-953502959486') AS tenant_id,
           '0a70275da588dced66dfc9c5dada6a1fb041f1b2928152cb88d1c8aca4d5ae42'
               AS digest_hex
    UNION ALL
    SELECT UUID_TO_BIN('978d86fe-2cb8-5d4b-9968-e2f738a5c3e2'),
           'ae566506b18df22c8663e77e24df64c1bfe6a199133a04b59a7841c8686be58a'
) AS fixture;

INSERT INTO flowdesk_workflow.workflow_version
    (tenant_id, workflow_version_id, definition_id, version_no,
     source_draft_id, status, condition_field_code, condition_operator,
     condition_value_type, condition_value_text, approval_level_count,
     level1_approver_user_id, level2_approver_user_id, snapshot_sha256,
     published_at, published_by_type, published_by_id)
SELECT fixture.tenant_id,
       UUID_TO_BIN('20fa8b79-f318-4c66-86e2-e997722a316a'),
       UUID_TO_BIN('0848bf3c-d603-5ece-9529-f9bba79f1498'), 3,
       UUID_TO_BIN('ee856821-9666-4c27-a96f-61f10c609d38'),
       'PUBLISHED', 'SERVICE_RISK_LEVEL', 'EQUALS', 'STRING', 'NORMAL', 2,
       UUID_TO_BIN('33f0b408-5548-5f2b-9865-f1dd288c0493'),
       UUID_TO_BIN('fdecef4b-2a69-538e-9fa5-249048115d3d'),
       UNHEX(fixture.digest_hex), UTC_TIMESTAMP(6), 'SERVICE', NULL
FROM (
    SELECT UUID_TO_BIN('12d7dca8-380c-5a6c-84ae-953502959486') AS tenant_id,
           '0a70275da588dced66dfc9c5dada6a1fb041f1b2928152cb88d1c8aca4d5ae42'
               AS digest_hex
    UNION ALL
    SELECT UUID_TO_BIN('978d86fe-2cb8-5d4b-9968-e2f738a5c3e2'),
           'ae566506b18df22c8663e77e24df64c1bfe6a199133a04b59a7841c8686be58a'
) AS fixture;

INSERT INTO flowdesk_ticket.service_catalog_item
    (tenant_id, catalog_item_id, item_code, item_name, description, risk_level,
     requires_approval, workflow_definition_id, workflow_version_id,
     workflow_version_no, sla_policy_id, status, lock_version,
     created_by_type, created_by_id, updated_by_type, updated_by_id)
SELECT tenant.tenant_id,
       UUID_TO_BIN('44073091-f271-4cdd-a0d4-cf1b757aa482'),
       'M6_CONDITION_FALSE', 'M6 验收条件不命中目录',
       '仅用于隔离的里程碑 6 验收项目。', 'HIGH', 1,
       UUID_TO_BIN('0848bf3c-d603-5ece-9529-f9bba79f1498'),
       UUID_TO_BIN('20fa8b79-f318-4c66-86e2-e997722a316a'), 3,
       UUID_TO_BIN('8eee84ca-e2c3-5c9e-af49-87f3c171e1cc'),
       'ACTIVE', 0, 'SERVICE', NULL, 'SERVICE', NULL
FROM (
    SELECT UUID_TO_BIN('12d7dca8-380c-5a6c-84ae-953502959486') AS tenant_id
    UNION ALL
    SELECT UUID_TO_BIN('978d86fe-2cb8-5d4b-9968-e2f738a5c3e2')
) AS tenant;

COMMIT;

SET @fd_m6_false_fixture_count =
    (SELECT COUNT(*)
       FROM flowdesk_ticket.service_catalog_item
      WHERE catalog_item_id=UUID_TO_BIN('44073091-f271-4cdd-a0d4-cf1b757aa482')
        AND risk_level='HIGH' AND requires_approval=1
        AND workflow_version_id=UUID_TO_BIN('20fa8b79-f318-4c66-86e2-e997722a316a')
        AND workflow_version_no=3)
  + (SELECT COUNT(*)
       FROM flowdesk_workflow.workflow_version
      WHERE workflow_version_id=UUID_TO_BIN('20fa8b79-f318-4c66-86e2-e997722a316a')
        AND condition_value_text='NORMAL' AND status='PUBLISHED');
SET @fd_m6_false_fixture_sql = IF(
    @fd_m6_false_fixture_count = 4,
    'SELECT ''FLOWDESK_MILESTONE_6_CONDITION_FALSE_FIXTURE_OK'' AS verification_result',
    'SELECT __flowdesk_m6_condition_false_fixture_incomplete__ FROM flowdesk_ticket.service_catalog_item LIMIT 0'
);
PREPARE fd_m6_false_fixture_stmt FROM @fd_m6_false_fixture_sql;
EXECUTE fd_m6_false_fixture_stmt;
DEALLOCATE PREPARE fd_m6_false_fixture_stmt;
