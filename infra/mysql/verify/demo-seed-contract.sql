-- Cross-schema read-only assertions for the FlowDesk demo seed contract.
-- Runs as the compose bootstrap DBA after all five demo Flyway migrations succeeded.
USE flowdesk_iam;

DROP PROCEDURE IF EXISTS seed_contract_assert_equal;

DELIMITER //
CREATE PROCEDURE seed_contract_assert_equal(
    IN assertion_name VARCHAR(64),
    IN actual_value BIGINT,
    IN expected_value BIGINT
)
BEGIN
    IF actual_value <> expected_value THEN
        SET @assertion_message = CONCAT(
            assertion_name, ': expected ', expected_value, ', got ', actual_value
        );
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = @assertion_message;
    END IF;
END//
DELIMITER ;

CALL seed_contract_assert_equal('iam tenants',
    (SELECT COUNT(*) FROM flowdesk_iam.tenant), 2);
CALL seed_contract_assert_equal('iam roles',
    (SELECT COUNT(*) FROM flowdesk_iam.role), 10);
CALL seed_contract_assert_equal('iam permissions',
    (SELECT COUNT(*) FROM flowdesk_iam.permission), 74);
CALL seed_contract_assert_equal('iam users',
    (SELECT COUNT(*) FROM flowdesk_iam.user_account), 12);
CALL seed_contract_assert_equal('iam credentials',
    (SELECT COUNT(*) FROM flowdesk_iam.user_credential), 12);
CALL seed_contract_assert_equal('iam user role grants',
    (SELECT COUNT(*) FROM flowdesk_iam.user_role_assignment), 12);
CALL seed_contract_assert_equal('iam role permission grants',
    (SELECT COUNT(*) FROM flowdesk_iam.role_permission), 92);
CALL seed_contract_assert_equal('iam roles per tenant',
    (SELECT COUNT(*) FROM (SELECT tenant_id, COUNT(*) AS row_count
        FROM flowdesk_iam.role GROUP BY tenant_id HAVING row_count <> 5) AS skew), 0);
CALL seed_contract_assert_equal('iam permissions per tenant',
    (SELECT COUNT(*) FROM (SELECT tenant_id, COUNT(*) AS row_count
        FROM flowdesk_iam.permission GROUP BY tenant_id HAVING row_count <> 37) AS skew), 0);
CALL seed_contract_assert_equal('iam users per tenant',
    (SELECT COUNT(*) FROM (SELECT tenant_id, COUNT(*) AS row_count
        FROM flowdesk_iam.user_account GROUP BY tenant_id HAVING row_count <> 6) AS skew), 0);
CALL seed_contract_assert_equal('iam user roles per tenant',
    (SELECT COUNT(*) FROM (SELECT tenant_id, COUNT(*) AS row_count
        FROM flowdesk_iam.user_role_assignment GROUP BY tenant_id HAVING row_count <> 6) AS skew), 0);
CALL seed_contract_assert_equal('iam role permissions per tenant',
    (SELECT COUNT(*) FROM (SELECT tenant_id, COUNT(*) AS row_count
        FROM flowdesk_iam.role_permission GROUP BY tenant_id HAVING row_count <> 46) AS skew), 0);
CALL seed_contract_assert_equal('iam permissions without any role grant',
    (SELECT COUNT(*)
       FROM flowdesk_iam.permission p
      WHERE NOT EXISTS (
          SELECT 1
            FROM flowdesk_iam.role_permission rp
           WHERE rp.tenant_id = p.tenant_id
             AND rp.permission_id = p.permission_id
             AND rp.state = 'GRANTED')), 0);
CALL seed_contract_assert_equal('iam service desk recovery grant completeness',
    (SELECT COUNT(*)
       FROM flowdesk_iam.role r
      WHERE r.role_code = 'SERVICE_DESK_ADMIN'
        AND (SELECT COUNT(*)
               FROM flowdesk_iam.role_permission rp
               JOIN flowdesk_iam.permission p
                 ON p.tenant_id = rp.tenant_id
                AND p.permission_id = rp.permission_id
              WHERE rp.tenant_id = r.tenant_id
                AND rp.role_id = r.role_id
                AND rp.state = 'GRANTED'
                AND p.permission_code IN (
                    'RECOVERY_READ_TENANT', 'RECOVERY_REPLAY_TENANT')) <> 2), 0);
CALL seed_contract_assert_equal('iam recovery grants outside service desk',
    (SELECT COUNT(*)
       FROM flowdesk_iam.role_permission rp
       JOIN flowdesk_iam.role r
         ON r.tenant_id = rp.tenant_id
        AND r.role_id = rp.role_id
       JOIN flowdesk_iam.permission p
         ON p.tenant_id = rp.tenant_id
        AND p.permission_id = rp.permission_id
      WHERE rp.state = 'GRANTED'
        AND p.permission_code IN (
            'RECOVERY_READ_TENANT', 'RECOVERY_REPLAY_TENANT')
        AND r.role_code <> 'SERVICE_DESK_ADMIN'), 0);

CALL seed_contract_assert_equal('workflow definitions',
    (SELECT COUNT(*) FROM flowdesk_workflow.workflow_definition), 2);
CALL seed_contract_assert_equal('workflow consumed drafts',
    (SELECT COUNT(*) FROM flowdesk_workflow.workflow_draft
      WHERE status = 'CONSUMED' AND validation_state = 'VALID'), 4);
CALL seed_contract_assert_equal('workflow published versions',
    (SELECT COUNT(*) FROM flowdesk_workflow.workflow_version
      WHERE status = 'PUBLISHED'), 4);
CALL seed_contract_assert_equal('workflow legacy versions preserved',
    (SELECT COUNT(*) FROM flowdesk_workflow.workflow_version
      WHERE workflow_version_id = UUID_TO_BIN('f777230e-43b7-5656-8bf1-8c3a358173be')
        AND version_no = 1), 2);
CALL seed_contract_assert_equal('workflow canonical versions',
    (SELECT COUNT(*) FROM flowdesk_workflow.workflow_version
      WHERE workflow_version_id = UUID_TO_BIN('5c75cbbd-0bc5-4c7b-87ef-0d68fb07062b')
        AND source_draft_id = UUID_TO_BIN('8de800af-7183-4316-9da1-edd24c328fe3')
        AND version_no = 2 AND status = 'PUBLISHED'), 2);
CALL seed_contract_assert_equal('workflow canonical tenant-one digest',
    (SELECT COUNT(*) FROM flowdesk_workflow.workflow_version
      WHERE tenant_id = UUID_TO_BIN('12d7dca8-380c-5a6c-84ae-953502959486')
        AND workflow_version_id = UUID_TO_BIN('5c75cbbd-0bc5-4c7b-87ef-0d68fb07062b')
        AND snapshot_sha256 = UNHEX('a35fc9b13fe585e5a11c002ec4bd3e52e575b33aa77dded9360e8c5d809102e6')), 1);
CALL seed_contract_assert_equal('workflow canonical tenant-two digest',
    (SELECT COUNT(*) FROM flowdesk_workflow.workflow_version
      WHERE tenant_id = UUID_TO_BIN('978d86fe-2cb8-5d4b-9968-e2f738a5c3e2')
        AND workflow_version_id = UUID_TO_BIN('5c75cbbd-0bc5-4c7b-87ef-0d68fb07062b')
        AND snapshot_sha256 = UNHEX('b5f88cb00a46437c3340436e92ce37ded28b25bb919650ba7c95d059f2972b0f')), 1);
CALL seed_contract_assert_equal('workflow definitions per tenant',
    (SELECT COUNT(*) FROM (SELECT tenant_id, COUNT(*) AS row_count
        FROM flowdesk_workflow.workflow_definition GROUP BY tenant_id HAVING row_count <> 1) AS skew), 0);
CALL seed_contract_assert_equal('workflow consumed drafts per tenant',
    (SELECT COUNT(*) FROM (SELECT tenant_id, COUNT(*) AS row_count
        FROM flowdesk_workflow.workflow_draft
       WHERE status = 'CONSUMED' AND validation_state = 'VALID'
       GROUP BY tenant_id HAVING row_count <> 2) AS skew), 0);
CALL seed_contract_assert_equal('workflow versions per tenant',
    (SELECT COUNT(*) FROM (SELECT tenant_id, COUNT(*) AS row_count
        FROM flowdesk_workflow.workflow_version
       WHERE status = 'PUBLISHED'
       GROUP BY tenant_id HAVING row_count <> 2) AS skew), 0);

CALL seed_contract_assert_equal('ticket sla policies',
    (SELECT COUNT(*) FROM flowdesk_ticket.sla_policy), 2);
CALL seed_contract_assert_equal('ticket active incident default guards',
    (SELECT COUNT(*) FROM flowdesk_ticket.sla_policy
      WHERE status = 'ACTIVE' AND is_incident_default = 1), 2);
CALL seed_contract_assert_equal('ticket active SLA escalation targets',
    (SELECT COUNT(*) FROM flowdesk_ticket.sla_escalation_target
      WHERE purpose = 'SLA' AND status = 'ACTIVE'
        AND qualification_state = 'ELIGIBLE'
        AND qualification_reason_code = 'ELIGIBLE'), 2);
CALL seed_contract_assert_equal('ticket eligible SLA target snapshots',
    (SELECT COUNT(*)
       FROM flowdesk_ticket.sla_qualification_snapshot snapshot
       JOIN flowdesk_ticket.sla_escalation_target target
         ON target.tenant_id = snapshot.tenant_id
        AND target.target_id = snapshot.target_id
      WHERE snapshot.purpose = 'SLA' AND snapshot.eligible = 1
        AND snapshot.reason_code = 'ELIGIBLE'
        AND snapshot.qualification_version = target.qualification_version
        AND snapshot.checked_at = target.qualification_checked_at
        AND snapshot.valid_until = target.qualification_valid_until), 2);
CALL seed_contract_assert_equal('ticket catalog items',
    (SELECT COUNT(*) FROM flowdesk_ticket.service_catalog_item), 4);
CALL seed_contract_assert_equal('ticket sla policies per tenant',
    (SELECT COUNT(*) FROM (SELECT tenant_id, COUNT(*) AS row_count
        FROM flowdesk_ticket.sla_policy GROUP BY tenant_id HAVING row_count <> 1) AS skew), 0);
CALL seed_contract_assert_equal('ticket catalog items per tenant',
    (SELECT COUNT(*) FROM (SELECT tenant_id, COUNT(*) AS row_count
        FROM flowdesk_ticket.service_catalog_item GROUP BY tenant_id HAVING row_count <> 2) AS skew), 0);
CALL seed_contract_assert_equal('ticket high catalog version bindings',
    (SELECT COUNT(*)
       FROM flowdesk_ticket.service_catalog_item c
       JOIN flowdesk_workflow.workflow_version v
         ON v.tenant_id = c.tenant_id
        AND v.workflow_version_id = c.workflow_version_id
        AND v.definition_id = c.workflow_definition_id
        AND v.version_no = c.workflow_version_no
      WHERE c.risk_level = 'HIGH' AND c.requires_approval = 1
        AND c.workflow_version_id = UUID_TO_BIN('5c75cbbd-0bc5-4c7b-87ef-0d68fb07062b')
        AND c.workflow_version_no = 2), 2);
CALL seed_contract_assert_equal('ticket legacy catalog bindings retired',
    (SELECT COUNT(*) FROM flowdesk_ticket.service_catalog_item
      WHERE risk_level = 'HIGH' AND requires_approval = 1
        AND workflow_version_id = UUID_TO_BIN('f777230e-43b7-5656-8bf1-8c3a358173be')
        AND workflow_version_no = 1), 0);

CALL seed_contract_assert_equal('alert monitoring integrations',
    (SELECT COUNT(*) FROM flowdesk_alert.monitoring_integration), 2);
CALL seed_contract_assert_equal('alert integrations without secret material',
    (SELECT COUNT(*) FROM flowdesk_alert.monitoring_integration
      WHERE hmac_secret_ref IS NULL
        AND hmac_secret_ciphertext IS NULL
        AND credential_storage_mode IS NULL), 2);
CALL seed_contract_assert_equal('alert integrations per tenant',
    (SELECT COUNT(*) FROM (SELECT tenant_id, COUNT(*) AS row_count
        FROM flowdesk_alert.monitoring_integration GROUP BY tenant_id HAVING row_count <> 1) AS skew), 0);

CALL seed_contract_assert_equal('notification welcome rows',
    (SELECT COUNT(*) FROM flowdesk_notification.notification), 12);
CALL seed_contract_assert_equal('notification duplicate ids',
    (SELECT COUNT(*)
       FROM (SELECT notification_id
               FROM flowdesk_notification.notification
              GROUP BY notification_id
             HAVING COUNT(*) > 1) AS duplicates), 0);
CALL seed_contract_assert_equal('notification rows per tenant',
    (SELECT COUNT(*) FROM (SELECT tenant_id, COUNT(*) AS row_count
        FROM flowdesk_notification.notification GROUP BY tenant_id HAVING row_count <> 6) AS skew), 0);

CALL seed_contract_assert_equal('gateway objects remain zero',
    (SELECT COUNT(*) FROM information_schema.TABLES
      WHERE TABLE_SCHEMA = 'flowdesk_gateway'), 0);

DROP PROCEDURE seed_contract_assert_equal;
SELECT 'FLOWDESK_DEMO_SEED_OK' AS verification_result;
