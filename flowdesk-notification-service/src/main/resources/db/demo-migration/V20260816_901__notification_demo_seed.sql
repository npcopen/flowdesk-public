-- FlowDesk Notification demo seed (DATABASE-DESIGN §12.4).
-- One welcome notification per demo user (UNREAD), produced by a service identity.
-- Executes exactly once via Flyway; assertions fail the migration on any count drift.

INSERT INTO notification
    (tenant_id, notification_id, recipient_user_id, notification_kind,
     source_service, source_effect_key, source_ref_type, source_ref_id,
     title, body, state, read_at, lock_version,
     created_by_type, created_by_id, updated_by_type, updated_by_id)
SELECT t.tenant_id, UNHEX(LEFT(SHA2(CONCAT('flowdesk-demo-notification-id-', t.tenant_code, '-', u.username), 256), 32)),
       u.user_id, 'WELCOME_DEMO',
       'flowdesk-ticket-service',
       UNHEX(SHA2(CONCAT('flowdesk-demo-welcome-', t.tenant_code, '-', u.username), 256)),
       'TENANT', t.tenant_id,
       '欢迎使用 FlowDesk 演示环境',
       CONCAT(u.display_name, '，欢迎使用 FlowDesk 演示环境。'),
       'UNREAD', NULL, 0, 'SERVICE', NULL, 'SERVICE', NULL
FROM (
    SELECT UUID_TO_BIN('12d7dca8-380c-5a6c-84ae-953502959486') AS tenant_id, 'SHANCHENG_TECH' AS tenant_code
    UNION ALL SELECT UUID_TO_BIN('978d86fe-2cb8-5d4b-9968-e2f738a5c3e2'), 'HAIYUE_MFG'
) AS t
JOIN (
    SELECT UUID_TO_BIN('7cf4974e-9441-5bd6-aa47-2a1abdeded41') AS user_id, 'requester' AS username, '演示申请人' AS display_name
    UNION ALL SELECT UUID_TO_BIN('b7a8439c-372d-588d-9da2-73fd1ea65f91'), 'agent', '演示处理人'
    UNION ALL SELECT UUID_TO_BIN('33f0b408-5548-5f2b-9865-f1dd288c0493'), 'approver1', '演示一级审批人'
    UNION ALL SELECT UUID_TO_BIN('fdecef4b-2a69-538e-9fa5-249048115d3d'), 'approver2', '演示二级审批人'
    UNION ALL SELECT UUID_TO_BIN('601b6f4e-2d6d-59f1-a826-60ad58c334b5'), 'servicedesk', '演示服务台管理员'
    UNION ALL SELECT UUID_TO_BIN('680a82dd-10b1-52cf-b793-441d19bec631'), 'tenantadmin', '演示租户管理员'
) AS u;

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
('notification welcome rows', (SELECT COUNT(*) FROM notification), 12),
('notification unread rows', (SELECT COUNT(*) FROM notification WHERE state = 'UNREAD'), 12);

DROP TRIGGER seed_assertion_bi;
DROP TABLE seed_assertion;
