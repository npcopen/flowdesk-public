-- FlowDesk Ticket demo seed (DATABASE-DESIGN §12.4).
-- One ACTIVE Incident-default SLA policy and exactly two Requester catalog items per tenant.
-- Executes exactly once via Flyway; assertions fail the migration on any count drift.

INSERT INTO sla_policy
    (tenant_id, sla_policy_id, policy_code, policy_name, first_response_seconds,
     resolution_seconds, first_response_reminder_before_seconds,
     resolution_reminder_before_seconds, is_incident_default, status, lock_version,
     created_by_type, created_by_id, updated_by_type, updated_by_id)
SELECT t.tenant_id, UUID_TO_BIN('8eee84ca-e2c3-5c9e-af49-87f3c171e1cc'),
       'INCIDENT_DEFAULT_24X7', '事件默认 SLA（24×7）', 900, 14400, 300, 3600,
       1, 'ACTIVE', 0, 'SERVICE', NULL, 'SERVICE', NULL
FROM (
    SELECT UUID_TO_BIN('12d7dca8-380c-5a6c-84ae-953502959486') AS tenant_id
    UNION ALL SELECT UUID_TO_BIN('978d86fe-2cb8-5d4b-9968-e2f738a5c3e2')
) AS t;

-- M8 uses a tenant-owned concrete recipient rather than resolving a role name
-- during an SLA trigger. The paired snapshot is selected from the target row so
-- its checkedAt/validUntil values are byte-for-byte identical to the gate input.
INSERT INTO sla_escalation_target
    (tenant_id, target_id, purpose, recipient_user_id, status, effective_at,
     qualification_version, qualification_checked_at, qualification_valid_until,
     qualification_state, qualification_reason_code, lock_version,
     created_by_type, created_by_id, updated_by_type, updated_by_id)
SELECT t.tenant_id, t.target_id, 'SLA',
       UUID_TO_BIN('601b6f4e-2d6d-59f1-a826-60ad58c334b5'), 'ACTIVE',
       t.checked_at, 1, t.checked_at, DATE_ADD(t.checked_at, INTERVAL 5 MINUTE),
       'ELIGIBLE', 'ELIGIBLE', 0, 'SERVICE', NULL, 'SERVICE', NULL
FROM (
    SELECT UUID_TO_BIN('12d7dca8-380c-5a6c-84ae-953502959486') AS tenant_id,
           UUID_TO_BIN('b4ee38d0-2056-5e75-a6cc-b791ea807c90') AS target_id,
           UTC_TIMESTAMP(6) AS checked_at
    UNION ALL
    SELECT UUID_TO_BIN('978d86fe-2cb8-5d4b-9968-e2f738a5c3e2'),
           UUID_TO_BIN('29aa3641-93ae-5b63-bb34-19b45deeaec4'), UTC_TIMESTAMP(6)
) AS t;

INSERT INTO sla_qualification_snapshot
    (tenant_id, target_id, recipient_user_id, purpose, qualification_version,
     checked_at, valid_until, eligible, reason_code, created_by_type, created_by_id)
SELECT tenant_id, target_id, recipient_user_id, purpose, qualification_version,
       qualification_checked_at, qualification_valid_until, 1, 'ELIGIBLE', 'SERVICE', NULL
FROM sla_escalation_target
WHERE purpose = 'SLA' AND status = 'ACTIVE';

INSERT INTO service_catalog_item
    (tenant_id, catalog_item_id, item_code, item_name, description, risk_level,
     requires_approval, workflow_definition_id, workflow_version_id,
     workflow_version_no, sla_policy_id, status, lock_version,
     created_by_type, created_by_id, updated_by_type, updated_by_id)
SELECT t.tenant_id, i.catalog_item_id, i.item_code, i.item_name, i.description,
       i.risk_level, i.requires_approval,
       CASE WHEN i.requires_approval = 1
            THEN UUID_TO_BIN('0848bf3c-d603-5ece-9529-f9bba79f1498')
            ELSE NULL END,
       CASE WHEN i.requires_approval = 1
            THEN UUID_TO_BIN('f777230e-43b7-5656-8bf1-8c3a358173be')
            ELSE NULL END,
       CASE WHEN i.requires_approval = 1 THEN 1 ELSE NULL END,
       UUID_TO_BIN('8eee84ca-e2c3-5c9e-af49-87f3c171e1cc'),
       'ACTIVE', 0, 'SERVICE', NULL, 'SERVICE', NULL
FROM (
    SELECT UUID_TO_BIN('12d7dca8-380c-5a6c-84ae-953502959486') AS tenant_id
    UNION ALL SELECT UUID_TO_BIN('978d86fe-2cb8-5d4b-9968-e2f738a5c3e2')
) AS t
CROSS JOIN (
    SELECT UUID_TO_BIN('310a0d4c-5c04-5cf0-abf3-cd4120dd9472') AS catalog_item_id,
           'LAPTOP_REQUEST' AS item_code, '笔记本电脑申请' AS item_name,
           '普通办公设备申请，走人工工单闭环。' AS description,
           'NORMAL' AS risk_level, 0 AS requires_approval
    UNION ALL
    SELECT UUID_TO_BIN('dafb0463-774c-503d-89cb-abc3d77e48cb'),
           'PROD_DB_ACCESS', '生产数据库权限申请',
           '高风险权限申请，命中两级顺序审批。',
           'HIGH', 1
) AS i;

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
    ('ticket sla policies', (SELECT COUNT(*) FROM sla_policy), 2),
    ('ticket incident default guards', (SELECT COUNT(*) FROM sla_policy WHERE status = 'ACTIVE' AND is_incident_default = 1), 2),
    ('ticket active SLA targets', (SELECT COUNT(*) FROM sla_escalation_target WHERE purpose = 'SLA' AND status = 'ACTIVE'), 2),
    ('ticket eligible SLA snapshots', (SELECT COUNT(*) FROM sla_qualification_snapshot WHERE purpose = 'SLA' AND eligible = 1 AND reason_code = 'ELIGIBLE'), 2),
    ('ticket catalog items', (SELECT COUNT(*) FROM service_catalog_item), 4),
('ticket normal catalog items', (SELECT COUNT(*) FROM service_catalog_item WHERE risk_level = 'NORMAL' AND requires_approval = 0), 2),
('ticket high catalog items', (SELECT COUNT(*) FROM service_catalog_item WHERE risk_level = 'HIGH' AND requires_approval = 1 AND workflow_definition_id IS NOT NULL AND workflow_version_id IS NOT NULL AND workflow_version_no = 1), 2);

DROP TRIGGER seed_assertion_bi;
DROP TABLE seed_assertion;
