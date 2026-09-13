-- FlowDesk IAM demo seed (DATABASE-DESIGN §12.4).
-- Two fictional tenants, five roles, 36 permission codes and six demo users per tenant.
-- Executes exactly once via Flyway; assertions fail the migration on any count drift.

INSERT INTO tenant
    (tenant_id, tenant_code, display_name, lock_version,
     created_by_type, created_by_id, updated_by_type, updated_by_id)
SELECT t.tenant_id, t.tenant_code, t.display_name, 0,
       'SERVICE', NULL, 'SERVICE', NULL
FROM (
    SELECT UUID_TO_BIN('12d7dca8-380c-5a6c-84ae-953502959486') AS tenant_id, 'SHANCHENG_TECH' AS tenant_code, '山城科技' AS display_name
    UNION ALL SELECT UUID_TO_BIN('978d86fe-2cb8-5d4b-9968-e2f738a5c3e2'), 'HAIYUE_MFG', '海岳制造'
) AS t;

INSERT INTO role
    (tenant_id, role_id, role_code, role_name, status, lock_version,
     created_by_type, created_by_id, updated_by_type, updated_by_id)
SELECT t.tenant_id, r.role_id, r.role_code, r.role_name, 'ACTIVE', 0,
       'SERVICE', NULL, 'SERVICE', NULL
FROM (
    SELECT UUID_TO_BIN('12d7dca8-380c-5a6c-84ae-953502959486') AS tenant_id
    UNION ALL SELECT UUID_TO_BIN('978d86fe-2cb8-5d4b-9968-e2f738a5c3e2')
) AS t
CROSS JOIN (
    SELECT UUID_TO_BIN('b27272c2-1ae3-51b3-ab40-6b44467f7769') AS role_id, 'REQUESTER' AS role_code, '申请人' AS role_name    UNION ALL SELECT UUID_TO_BIN('a75e6bb7-6869-5b9f-9890-ad898ba5fed0') AS role_id, 'AGENT' AS role_code, '工单处理人' AS role_name    UNION ALL SELECT UUID_TO_BIN('760d5199-79f8-5efd-9c82-1501b9bd67f2') AS role_id, 'APPROVER' AS role_code, '审批人' AS role_name    UNION ALL SELECT UUID_TO_BIN('9cba0c7e-bade-5be2-ab1d-aade24015586') AS role_id, 'SERVICE_DESK_ADMIN' AS role_code, '服务台管理员' AS role_name    UNION ALL SELECT UUID_TO_BIN('5cd2d846-7ef3-536f-9c5d-223dcd37e3db') AS role_id, 'TENANT_ADMIN' AS role_code, '租户管理员' AS role_name
) AS r;

INSERT INTO permission
    (tenant_id, permission_id, permission_code, permission_name, description,
     created_by_type, created_by_id)
SELECT t.tenant_id, p.permission_id, p.permission_code, p.permission_code,
       CONCAT('Demo seed for ', p.permission_code), 'SERVICE', NULL
FROM (
    SELECT UUID_TO_BIN('12d7dca8-380c-5a6c-84ae-953502959486') AS tenant_id
    UNION ALL SELECT UUID_TO_BIN('978d86fe-2cb8-5d4b-9968-e2f738a5c3e2')
) AS t
CROSS JOIN (
    SELECT UUID_TO_BIN('cdd7d2d9-5898-5567-8d61-6469eada868c') AS permission_id, 'CATALOG_READ_ACTIVE' AS permission_code    UNION ALL SELECT UUID_TO_BIN('f089814c-2ce8-5fa1-b9e6-97c098299caf') AS permission_id, 'TICKET_CREATE' AS permission_code    UNION ALL SELECT UUID_TO_BIN('31767a72-ff57-55bd-81fe-8ad473a355eb') AS permission_id, 'TICKET_READ_OWN' AS permission_code    UNION ALL SELECT UUID_TO_BIN('ede810bc-2687-5b07-974e-76cbd0986404') AS permission_id, 'TICKET_COMMENT_OWN' AS permission_code    UNION ALL SELECT UUID_TO_BIN('ea418a05-7c99-5cb8-8690-d943ed377e0a') AS permission_id, 'TICKET_CLOSE_OWN' AS permission_code    UNION ALL SELECT UUID_TO_BIN('92c8368f-bf49-577a-bd21-e15a690c641d') AS permission_id, 'TICKET_REOPEN_OWN' AS permission_code    UNION ALL SELECT UUID_TO_BIN('85b2c058-b384-5e82-b4ac-073f4157f95f') AS permission_id, 'TICKET_READ_ASSIGNED' AS permission_code    UNION ALL SELECT UUID_TO_BIN('b174dceb-5c30-5632-aed3-80a046e16f00') AS permission_id, 'TICKET_ACCEPT_ASSIGNED' AS permission_code    UNION ALL SELECT UUID_TO_BIN('f4e87cd9-5cb3-5033-94ff-1f3144677487') AS permission_id, 'TICKET_TRANSFER_ASSIGNED' AS permission_code    UNION ALL SELECT UUID_TO_BIN('1af5f723-01b3-5f11-80e4-713d39824459') AS permission_id, 'TICKET_COMMENT_ASSIGNED' AS permission_code    UNION ALL SELECT UUID_TO_BIN('096b3c73-de69-57ae-8e52-b2cb98287e10') AS permission_id, 'TICKET_RESOLVE_ASSIGNED' AS permission_code    UNION ALL SELECT UUID_TO_BIN('a66536dc-1b1a-5fe7-a518-fd12dd552b52') AS permission_id, 'INCIDENT_READ_TENANT' AS permission_code    UNION ALL SELECT UUID_TO_BIN('a44930f0-7235-53ab-894f-4e346163eafe') AS permission_id, 'APPROVAL_READ_ASSIGNED' AS permission_code    UNION ALL SELECT UUID_TO_BIN('b1662cf9-6f19-52b9-a4b0-cc145103df6b') AS permission_id, 'APPROVAL_DECIDE_ASSIGNED' AS permission_code    UNION ALL SELECT UUID_TO_BIN('2ce28e26-9259-511a-b19b-43f197d19a50') AS permission_id, 'IAM_ELIGIBILITY_CANDIDATE_READ' AS permission_code    UNION ALL SELECT UUID_TO_BIN('1e3ca59f-3a0a-5684-9d76-90f0cc28bf15') AS permission_id, 'CATALOG_READ_TENANT' AS permission_code    UNION ALL SELECT UUID_TO_BIN('90c9a1c5-b013-5177-adbe-2bbd9c941576') AS permission_id, 'CATALOG_UPDATE_MINIMAL' AS permission_code    UNION ALL SELECT UUID_TO_BIN('02884966-061e-5a8e-adbc-adcc58adbc24') AS permission_id, 'TICKET_READ_TENANT' AS permission_code    UNION ALL SELECT UUID_TO_BIN('921a38e3-0cbd-5ec6-9e8c-d6c0f5d73f5a') AS permission_id, 'TICKET_ASSIGN_TENANT' AS permission_code    UNION ALL SELECT UUID_TO_BIN('21988052-97e5-5f79-b87b-6f7d423bb087') AS permission_id, 'TICKET_TRANSFER_TENANT' AS permission_code    UNION ALL SELECT UUID_TO_BIN('b4fd3ee9-f4be-59e3-80fc-1654d33957b9') AS permission_id, 'WORKFLOW_READ_TENANT' AS permission_code    UNION ALL SELECT UUID_TO_BIN('fded3770-248b-5960-93c9-b3bfb6746254') AS permission_id, 'WORKFLOW_DRAFT_MANAGE' AS permission_code    UNION ALL SELECT UUID_TO_BIN('bc2cd163-ce98-514c-9eef-bc353ffb141b') AS permission_id, 'WORKFLOW_PUBLISH' AS permission_code    UNION ALL SELECT UUID_TO_BIN('adc240e4-5028-51f9-8a25-b5a93d406948') AS permission_id, 'WORKFLOW_RUNTIME_READ_TENANT' AS permission_code    UNION ALL SELECT UUID_TO_BIN('e66bed3a-0506-55ea-8bdb-acff6ca6c35a') AS permission_id, 'ALERT_EVENT_READ_TENANT' AS permission_code    UNION ALL SELECT UUID_TO_BIN('ce3b8a6c-421d-5d32-b2c2-86b1826a05d3') AS permission_id, 'RECOVERY_READ_TENANT' AS permission_code    UNION ALL SELECT UUID_TO_BIN('083979c8-5252-584e-a906-735d8ed0d53c') AS permission_id, 'RECOVERY_REPLAY_TENANT' AS permission_code    UNION ALL SELECT UUID_TO_BIN('5828628e-491d-537e-b8c4-00b1a59d282a') AS permission_id, 'IAM_USER_READ' AS permission_code    UNION ALL SELECT UUID_TO_BIN('aac3d7d1-76e8-53ae-b8e6-98d49be554b8') AS permission_id, 'IAM_USER_MANAGE' AS permission_code    UNION ALL SELECT UUID_TO_BIN('e944b27c-da93-5878-9e2a-4e2c8d9f7e93') AS permission_id, 'IAM_ROLE_READ' AS permission_code    UNION ALL SELECT UUID_TO_BIN('3d8cb03e-3cb2-52f8-b5cf-d2694e459da3') AS permission_id, 'IAM_ROLE_MANAGE' AS permission_code    UNION ALL SELECT UUID_TO_BIN('c0fd49ba-2324-521c-9258-2e87262b155b') AS permission_id, 'IAM_PERMISSION_READ' AS permission_code    UNION ALL SELECT UUID_TO_BIN('1fb9a34c-a0ea-54b4-9f9d-c3dff7feac2f') AS permission_id, 'IAM_BINDING_READ' AS permission_code    UNION ALL SELECT UUID_TO_BIN('c337e9ef-3fb6-53dc-83ef-b0a1d629914e') AS permission_id, 'IAM_BINDING_MANAGE' AS permission_code    UNION ALL SELECT UUID_TO_BIN('7a58be91-2ee1-522e-90ea-79e4df658a26') AS permission_id, 'NOTIFICATION_READ_SELF' AS permission_code    UNION ALL SELECT UUID_TO_BIN('a56af54b-28f9-50a8-91aa-edbff604d571') AS permission_id, 'NOTIFICATION_WRITE_SELF' AS permission_code
) AS p;

INSERT INTO user_account
    (tenant_id, user_id, username, display_name, status, lock_version,
     created_by_type, created_by_id, updated_by_type, updated_by_id)
SELECT t.tenant_id, u.user_id, u.username, u.display_name, 'ACTIVE', 0,
       'SERVICE', NULL, 'SERVICE', NULL
FROM (
    SELECT UUID_TO_BIN('12d7dca8-380c-5a6c-84ae-953502959486') AS tenant_id
    UNION ALL SELECT UUID_TO_BIN('978d86fe-2cb8-5d4b-9968-e2f738a5c3e2')
) AS t
CROSS JOIN (
    SELECT UUID_TO_BIN('7cf4974e-9441-5bd6-aa47-2a1abdeded41') AS user_id, 'requester' AS username, '演示申请人' AS display_name    UNION ALL SELECT UUID_TO_BIN('b7a8439c-372d-588d-9da2-73fd1ea65f91') AS user_id, 'agent' AS username, '演示处理人' AS display_name    UNION ALL SELECT UUID_TO_BIN('33f0b408-5548-5f2b-9865-f1dd288c0493') AS user_id, 'approver1' AS username, '演示一级审批人' AS display_name    UNION ALL SELECT UUID_TO_BIN('fdecef4b-2a69-538e-9fa5-249048115d3d') AS user_id, 'approver2' AS username, '演示二级审批人' AS display_name    UNION ALL SELECT UUID_TO_BIN('601b6f4e-2d6d-59f1-a826-60ad58c334b5') AS user_id, 'servicedesk' AS username, '演示服务台管理员' AS display_name    UNION ALL SELECT UUID_TO_BIN('680a82dd-10b1-52cf-b793-441d19bec631') AS user_id, 'tenantadmin' AS username, '演示租户管理员' AS display_name
) AS u;

INSERT INTO user_credential
    (tenant_id, user_id, credential_kind, password_hash, password_hash_scheme,
     failed_attempt_count, locked_until, password_changed_at, lock_version,
     created_by_type, created_by_id, updated_by_type, updated_by_id)
SELECT t.tenant_id, u.user_id, 'PASSWORD',
       X'70626b6466325f7368613235362432313030303024414245694d3052565a6e65496d6171377a4e33752f773d3d244b5356386e332f76794d46706b3955365362653156676f2f7a6c4d76543773597847667a5059664b4431493d',
       'pbkdf2_sha256',
       0, NULL, UTC_TIMESTAMP(6), 0,
       'SERVICE', NULL, 'SERVICE', NULL
FROM (
    SELECT UUID_TO_BIN('12d7dca8-380c-5a6c-84ae-953502959486') AS tenant_id
    UNION ALL SELECT UUID_TO_BIN('978d86fe-2cb8-5d4b-9968-e2f738a5c3e2')
) AS t
JOIN user_account u ON u.tenant_id = t.tenant_id;

INSERT INTO user_role_assignment
    (tenant_id, user_id, role_id, state, granted_at, granted_by_type, granted_by_id,
     revoked_at, revoked_by_type, revoked_by_id, lock_version)
SELECT t.tenant_id, u.user_id, r.role_id, 'GRANTED', UTC_TIMESTAMP(6),
       'SERVICE', NULL, NULL, NULL, NULL, 0
FROM (
    SELECT UUID_TO_BIN('12d7dca8-380c-5a6c-84ae-953502959486') AS tenant_id
    UNION ALL SELECT UUID_TO_BIN('978d86fe-2cb8-5d4b-9968-e2f738a5c3e2')
) AS t
JOIN user_account u ON u.tenant_id = t.tenant_id
JOIN role r ON r.tenant_id = t.tenant_id
JOIN (
    SELECT UUID_TO_BIN('7cf4974e-9441-5bd6-aa47-2a1abdeded41') AS user_id, 'REQUESTER' AS role_code    UNION ALL SELECT UUID_TO_BIN('b7a8439c-372d-588d-9da2-73fd1ea65f91') AS user_id, 'AGENT' AS role_code    UNION ALL SELECT UUID_TO_BIN('33f0b408-5548-5f2b-9865-f1dd288c0493') AS user_id, 'APPROVER' AS role_code    UNION ALL SELECT UUID_TO_BIN('fdecef4b-2a69-538e-9fa5-249048115d3d') AS user_id, 'APPROVER' AS role_code    UNION ALL SELECT UUID_TO_BIN('601b6f4e-2d6d-59f1-a826-60ad58c334b5') AS user_id, 'SERVICE_DESK_ADMIN' AS role_code    UNION ALL SELECT UUID_TO_BIN('680a82dd-10b1-52cf-b793-441d19bec631') AS user_id, 'TENANT_ADMIN' AS role_code
) AS a ON a.user_id = u.user_id AND a.role_code = r.role_code;

INSERT INTO role_permission
    (tenant_id, role_id, permission_id, state, granted_at, granted_by_type, granted_by_id,
     revoked_at, revoked_by_type, revoked_by_id, lock_version)
SELECT t.tenant_id, r.role_id, p.permission_id, 'GRANTED', UTC_TIMESTAMP(6),
       'SERVICE', NULL, NULL, NULL, NULL, 0
FROM (
    SELECT UUID_TO_BIN('12d7dca8-380c-5a6c-84ae-953502959486') AS tenant_id
    UNION ALL SELECT UUID_TO_BIN('978d86fe-2cb8-5d4b-9968-e2f738a5c3e2')
) AS t
JOIN role r ON r.tenant_id = t.tenant_id
JOIN permission p ON p.tenant_id = t.tenant_id
WHERE (r.role_code = 'REQUESTER' AND p.permission_code IN ('CATALOG_READ_ACTIVE', 'TICKET_CREATE', 'TICKET_READ_OWN', 'TICKET_COMMENT_OWN', 'TICKET_CLOSE_OWN', 'TICKET_REOPEN_OWN', 'NOTIFICATION_READ_SELF', 'NOTIFICATION_WRITE_SELF')) OR (r.role_code = 'AGENT' AND p.permission_code IN ('TICKET_READ_ASSIGNED', 'TICKET_ACCEPT_ASSIGNED', 'TICKET_TRANSFER_ASSIGNED', 'TICKET_COMMENT_ASSIGNED', 'TICKET_RESOLVE_ASSIGNED', 'INCIDENT_READ_TENANT', 'NOTIFICATION_READ_SELF', 'NOTIFICATION_WRITE_SELF')) OR (r.role_code = 'APPROVER' AND p.permission_code IN ('APPROVAL_READ_ASSIGNED', 'APPROVAL_DECIDE_ASSIGNED', 'NOTIFICATION_READ_SELF', 'NOTIFICATION_WRITE_SELF')) OR (r.role_code = 'SERVICE_DESK_ADMIN' AND p.permission_code IN ('IAM_ELIGIBILITY_CANDIDATE_READ', 'INCIDENT_READ_TENANT', 'CATALOG_READ_TENANT', 'CATALOG_UPDATE_MINIMAL', 'TICKET_READ_TENANT', 'TICKET_ASSIGN_TENANT', 'TICKET_TRANSFER_TENANT', 'WORKFLOW_READ_TENANT', 'WORKFLOW_DRAFT_MANAGE', 'WORKFLOW_PUBLISH', 'WORKFLOW_RUNTIME_READ_TENANT', 'ALERT_EVENT_READ_TENANT', 'RECOVERY_READ_TENANT', 'RECOVERY_REPLAY_TENANT', 'NOTIFICATION_READ_SELF', 'NOTIFICATION_WRITE_SELF')) OR (r.role_code = 'TENANT_ADMIN' AND p.permission_code IN ('IAM_USER_READ', 'IAM_USER_MANAGE', 'IAM_ROLE_READ', 'IAM_ROLE_MANAGE', 'IAM_PERMISSION_READ', 'IAM_BINDING_READ', 'IAM_BINDING_MANAGE', 'NOTIFICATION_READ_SELF', 'NOTIFICATION_WRITE_SELF'));

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
('iam tenants', (SELECT COUNT(*) FROM tenant), 2),
('iam roles', (SELECT COUNT(*) FROM role), 10),
('iam permissions', (SELECT COUNT(*) FROM permission), 72),
('iam users', (SELECT COUNT(*) FROM user_account), 12),
('iam credentials', (SELECT COUNT(*) FROM user_credential), 12),
('iam user roles', (SELECT COUNT(*) FROM user_role_assignment), 12),
('iam role permissions', (SELECT COUNT(*) FROM role_permission), 90),
('iam service desk recovery grant completeness',
 (SELECT COUNT(*)
    FROM role r
   WHERE r.role_code = 'SERVICE_DESK_ADMIN'
     AND (SELECT COUNT(*)
            FROM role_permission rp
            JOIN permission p
              ON p.tenant_id = rp.tenant_id
             AND p.permission_id = rp.permission_id
           WHERE rp.tenant_id = r.tenant_id
             AND rp.role_id = r.role_id
             AND rp.state = 'GRANTED'
             AND p.permission_code IN ('RECOVERY_READ_TENANT', 'RECOVERY_REPLAY_TENANT')) <> 2), 0),
('iam recovery grants outside service desk',
 (SELECT COUNT(*)
    FROM role_permission rp
    JOIN role r
      ON r.tenant_id = rp.tenant_id
     AND r.role_id = rp.role_id
    JOIN permission p
      ON p.tenant_id = rp.tenant_id
     AND p.permission_id = rp.permission_id
   WHERE rp.state = 'GRANTED'
     AND p.permission_code IN ('RECOVERY_READ_TENANT', 'RECOVERY_REPLAY_TENANT')
     AND r.role_code <> 'SERVICE_DESK_ADMIN'), 0);

DROP TRIGGER seed_assertion_bi;
DROP TABLE seed_assertion;
