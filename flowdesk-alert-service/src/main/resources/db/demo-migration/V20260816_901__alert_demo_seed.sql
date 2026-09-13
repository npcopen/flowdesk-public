-- FlowDesk Alert demo seed (DATABASE-DESIGN §12.4).
-- One non-secret placeholder MonitoringIntegration per tenant; no HMAC/raw body material.
-- Executes exactly once via Flyway; assertions fail the migration on any count drift.

INSERT INTO monitoring_integration
    (tenant_id, integration_id, integration_code, integration_name, status,
     api_key_selector_hash, api_key_verifier, api_key_verifier_scheme,
     signing_scheme_version, hmac_secret_ref, hmac_secret_ciphertext,
     credential_storage_mode, secret_material_version, valid_from, valid_until,
     lock_version, created_by_type, created_by_id, updated_by_type, updated_by_id)
SELECT t.tenant_id, UUID_TO_BIN('f611e78b-646f-5174-a4e6-133727194124'),
       'DEMO_MONITOR', '演示监控脚本', 'ACTIVE',
       UNHEX(SHA2(CONCAT('flowdesk-demo-selector-', t.tenant_code), 256)),
       UNHEX(SHA2(CONCAT('flowdesk-demo-verifier-', t.tenant_code), 256)),
       'DEMO_PLACEHOLDER_SHA256',
       1, NULL, NULL, NULL, 1, UTC_TIMESTAMP(6), NULL,
       0, 'SERVICE', NULL, 'SERVICE', NULL
FROM (
    SELECT UUID_TO_BIN('12d7dca8-380c-5a6c-84ae-953502959486') AS tenant_id, 'SHANCHENG_TECH' AS tenant_code
    UNION ALL SELECT UUID_TO_BIN('978d86fe-2cb8-5d4b-9968-e2f738a5c3e2'), 'HAIYUE_MFG'
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
('alert integrations', (SELECT COUNT(*) FROM monitoring_integration), 2),
('alert integrations without secret material', (SELECT COUNT(*) FROM monitoring_integration WHERE hmac_secret_ref IS NULL AND hmac_secret_ciphertext IS NULL AND credential_storage_mode IS NULL), 2);

DROP TRIGGER seed_assertion_bi;
DROP TABLE seed_assertion;
