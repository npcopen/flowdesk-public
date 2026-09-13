-- M6 forward-only demo evolution.  Keep the historical M5 version/draft
-- untouched and add one canonical restricted-rule version per demo tenant.
-- The two validation digests were computed with the frozen length-framed
-- FD-WORKFLOW-VERSION-V1 algorithm; tenant_id is therefore part of each value.

INSERT INTO workflow_draft
    (tenant_id, draft_id, definition_id, status, condition_field_code,
     condition_operator, condition_value_type, condition_value_text,
     approval_level_count, level1_approver_user_id, level2_approver_user_id,
     validation_state, validation_digest, validated_at, consumed_at,
     lock_version, created_at, created_by_type, created_by_id, updated_at,
     updated_by_type, updated_by_id)
SELECT x.tenant_id, UUID_TO_BIN('8de800af-7183-4316-9da1-edd24c328fe3'),
       UUID_TO_BIN('0848bf3c-d603-5ece-9529-f9bba79f1498'),
       'CONSUMED', 'SERVICE_RISK_LEVEL', 'EQUALS', 'STRING', 'HIGH', 2,
       UUID_TO_BIN('33f0b408-5548-5f2b-9865-f1dd288c0493'),
       UUID_TO_BIN('fdecef4b-2a69-538e-9fa5-249048115d3d'),
       'VALID', UNHEX(x.digest_hex),
       UTC_TIMESTAMP(6) - INTERVAL 5 SECOND, UTC_TIMESTAMP(6), 0,
       UTC_TIMESTAMP(6) - INTERVAL 10 SECOND, 'SERVICE', NULL,
       UTC_TIMESTAMP(6), 'SERVICE', NULL
FROM (
    SELECT UUID_TO_BIN('12d7dca8-380c-5a6c-84ae-953502959486') AS tenant_id,
           'a35fc9b13fe585e5a11c002ec4bd3e52e575b33aa77dded9360e8c5d809102e6'
               AS digest_hex
    UNION ALL
    SELECT UUID_TO_BIN('978d86fe-2cb8-5d4b-9968-e2f738a5c3e2'),
           'b5f88cb00a46437c3340436e92ce37ded28b25bb919650ba7c95d059f2972b0f'
) AS x;

INSERT INTO workflow_version
    (tenant_id, workflow_version_id, definition_id, version_no,
     source_draft_id, status, condition_field_code, condition_operator,
     condition_value_type, condition_value_text, approval_level_count,
     level1_approver_user_id, level2_approver_user_id, snapshot_sha256,
     published_at, published_by_type, published_by_id)
SELECT x.tenant_id, UUID_TO_BIN('5c75cbbd-0bc5-4c7b-87ef-0d68fb07062b'),
       UUID_TO_BIN('0848bf3c-d603-5ece-9529-f9bba79f1498'), 2,
       UUID_TO_BIN('8de800af-7183-4316-9da1-edd24c328fe3'),
       'PUBLISHED', 'SERVICE_RISK_LEVEL', 'EQUALS', 'STRING', 'HIGH', 2,
       UUID_TO_BIN('33f0b408-5548-5f2b-9865-f1dd288c0493'),
       UUID_TO_BIN('fdecef4b-2a69-538e-9fa5-249048115d3d'),
       UNHEX(x.digest_hex), UTC_TIMESTAMP(6), 'SERVICE', NULL
FROM (
    SELECT UUID_TO_BIN('12d7dca8-380c-5a6c-84ae-953502959486') AS tenant_id,
           'a35fc9b13fe585e5a11c002ec4bd3e52e575b33aa77dded9360e8c5d809102e6'
               AS digest_hex
    UNION ALL
    SELECT UUID_TO_BIN('978d86fe-2cb8-5d4b-9968-e2f738a5c3e2'),
           'b5f88cb00a46437c3340436e92ce37ded28b25bb919650ba7c95d059f2972b0f'
) AS x;

DROP TABLE IF EXISTS seed_assertion_m6;
CREATE TABLE seed_assertion_m6 (
    assertion_name VARCHAR(64) NOT NULL,
    actual_value BIGINT NOT NULL,
    expected_value BIGINT NOT NULL
) ENGINE = InnoDB;

DELIMITER //
DROP TRIGGER IF EXISTS seed_assertion_m6_bi//
CREATE TRIGGER seed_assertion_m6_bi
BEFORE INSERT ON seed_assertion_m6
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

INSERT INTO seed_assertion_m6 (assertion_name, actual_value, expected_value) VALUES
('legacy workflow version preserved',
 (SELECT COUNT(*) FROM workflow_version
   WHERE workflow_version_id=UUID_TO_BIN('f777230e-43b7-5656-8bf1-8c3a358173be')
     AND version_no=1), 2),
('m6 canonical drafts',
 (SELECT COUNT(*) FROM workflow_draft
   WHERE draft_id=UUID_TO_BIN('8de800af-7183-4316-9da1-edd24c328fe3')
     AND status='CONSUMED' AND validation_state='VALID'), 2),
('m6 canonical versions',
 (SELECT COUNT(*) FROM workflow_version
   WHERE workflow_version_id=UUID_TO_BIN('5c75cbbd-0bc5-4c7b-87ef-0d68fb07062b')
     AND version_no=2 AND status='PUBLISHED'), 2),
('m6 tenant one digest',
 (SELECT COUNT(*) FROM workflow_version
   WHERE tenant_id=UUID_TO_BIN('12d7dca8-380c-5a6c-84ae-953502959486')
     AND workflow_version_id=UUID_TO_BIN('5c75cbbd-0bc5-4c7b-87ef-0d68fb07062b')
     AND snapshot_sha256=UNHEX('a35fc9b13fe585e5a11c002ec4bd3e52e575b33aa77dded9360e8c5d809102e6')), 1),
('m6 tenant two digest',
 (SELECT COUNT(*) FROM workflow_version
   WHERE tenant_id=UUID_TO_BIN('978d86fe-2cb8-5d4b-9968-e2f738a5c3e2')
     AND workflow_version_id=UUID_TO_BIN('5c75cbbd-0bc5-4c7b-87ef-0d68fb07062b')
     AND snapshot_sha256=UNHEX('b5f88cb00a46437c3340436e92ce37ded28b25bb919650ba7c95d059f2972b0f')), 1);

DROP TRIGGER seed_assertion_m6_bi;
DROP TABLE seed_assertion_m6;
