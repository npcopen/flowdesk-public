-- FlowDesk Workflow demo seed (DATABASE-DESIGN §12.4).
-- One consumed draft and one published HIGH-risk two-level approval version per tenant.
-- Executes exactly once via Flyway; assertions fail the migration on any count drift.

INSERT INTO workflow_definition
    (tenant_id, definition_id, definition_code, definition_name, status, lock_version,
     created_by_type, created_by_id, updated_by_type, updated_by_id)
SELECT t.tenant_id, UUID_TO_BIN('0848bf3c-d603-5ece-9529-f9bba79f1498'),
       'PROD_DB_ACCESS_APPROVAL', '生产数据库权限审批', 'ACTIVE', 0,
       'SERVICE', NULL, 'SERVICE', NULL
FROM (
    SELECT UUID_TO_BIN('12d7dca8-380c-5a6c-84ae-953502959486') AS tenant_id
    UNION ALL SELECT UUID_TO_BIN('978d86fe-2cb8-5d4b-9968-e2f738a5c3e2')
) AS t;

INSERT INTO workflow_draft
    (tenant_id, draft_id, definition_id, status, condition_field_code, condition_operator,
     condition_value_type, condition_value_text, approval_level_count,
     level1_approver_user_id, level2_approver_user_id, validation_state,
     validation_digest, validated_at, consumed_at, lock_version,
     created_at, created_by_type, created_by_id, updated_at, updated_by_type, updated_by_id)
SELECT t.tenant_id, UUID_TO_BIN('c2af8046-1e26-569b-afb5-c117c76c7a25'),
       UUID_TO_BIN('0848bf3c-d603-5ece-9529-f9bba79f1498'),
       'CONSUMED', 'SERVICE_RISK_LEVEL', 'EQUALS', 'STRING', 'HIGH', 2,
       UUID_TO_BIN('33f0b408-5548-5f2b-9865-f1dd288c0493'),
       UUID_TO_BIN('fdecef4b-2a69-538e-9fa5-249048115d3d'),
       'VALID', UNHEX(SHA2('flowdesk-demo-draft-validation', 256)),
       UTC_TIMESTAMP(6) - INTERVAL 5 SECOND,
       UTC_TIMESTAMP(6),
       0,
       UTC_TIMESTAMP(6) - INTERVAL 10 SECOND, 'SERVICE', NULL,
       UTC_TIMESTAMP(6), 'SERVICE', NULL
FROM (
    SELECT UUID_TO_BIN('12d7dca8-380c-5a6c-84ae-953502959486') AS tenant_id
    UNION ALL SELECT UUID_TO_BIN('978d86fe-2cb8-5d4b-9968-e2f738a5c3e2')
) AS t;

INSERT INTO workflow_version
    (tenant_id, workflow_version_id, definition_id, version_no, source_draft_id,
     status, condition_field_code, condition_operator, condition_value_type,
     condition_value_text, approval_level_count, level1_approver_user_id,
     level2_approver_user_id, snapshot_sha256, published_at,
     published_by_type, published_by_id)
SELECT t.tenant_id, UUID_TO_BIN('f777230e-43b7-5656-8bf1-8c3a358173be'),
       UUID_TO_BIN('0848bf3c-d603-5ece-9529-f9bba79f1498'), 1,
       UUID_TO_BIN('c2af8046-1e26-569b-afb5-c117c76c7a25'),
       'PUBLISHED', 'SERVICE_RISK_LEVEL', 'EQUALS', 'STRING', 'HIGH', 2,
       UUID_TO_BIN('33f0b408-5548-5f2b-9865-f1dd288c0493'),
       UUID_TO_BIN('fdecef4b-2a69-538e-9fa5-249048115d3d'),
       UNHEX(SHA2('flowdesk-demo-workflow-version-snapshot', 256)),
       UTC_TIMESTAMP(6), 'SERVICE', NULL
FROM (
    SELECT UUID_TO_BIN('12d7dca8-380c-5a6c-84ae-953502959486') AS tenant_id
    UNION ALL SELECT UUID_TO_BIN('978d86fe-2cb8-5d4b-9968-e2f738a5c3e2')
) AS t;

DROP TABLE IF EXISTS seed_assertion;
CREATE TABLE seed_assertion (
    assertion_name VARCHAR(64) NOT NULL,
    actual_value BIGINT NOT NULL,
    expected_value BIGINT NOT NULL
) ENGINE = InnoDB;

DELIMITER //

DROP TRIGGER IF EXISTS seed_assertion_bi//
CREATE TRIGGER seed_assertion_bi
BEFORE INSERT ON seed_assertion
FOR EACH ROW
BEGIN
    IF NEW.actual_value <> NEW.expected_value THEN
        SET @assertion_message = CONCAT(
            NEW.assertion_name, ': expected ', NEW.expected_value,
            ', got ', NEW.actual_value
        );
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = @assertion_message;
    END IF;
END//

DELIMITER ;

INSERT INTO seed_assertion (assertion_name, actual_value, expected_value) VALUES
('workflow definitions', (SELECT COUNT(*) FROM workflow_definition), 2),
('workflow drafts', (SELECT COUNT(*) FROM workflow_draft), 2),
('workflow consumed valid drafts', (SELECT COUNT(*) FROM workflow_draft WHERE status = 'CONSUMED' AND validation_state = 'VALID'), 2),
('workflow versions', (SELECT COUNT(*) FROM workflow_version), 2);

DROP TRIGGER seed_assertion_bi;
DROP TABLE seed_assertion;
