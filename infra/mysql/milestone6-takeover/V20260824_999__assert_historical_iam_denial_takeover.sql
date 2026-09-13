DROP TABLE IF EXISTS milestone6_takeover_assertion;
CREATE TABLE milestone6_takeover_assertion (
    assertion_name VARCHAR(96) NOT NULL,
    actual_value BIGINT NOT NULL,
    expected_value BIGINT NOT NULL
) ENGINE=InnoDB;

DELIMITER //
CREATE TRIGGER milestone6_takeover_assertion_bi
BEFORE INSERT ON milestone6_takeover_assertion
FOR EACH ROW
BEGIN
    IF NEW.actual_value <> NEW.expected_value THEN
        SET @assertion_message = CONCAT(
            NEW.assertion_name, ': expected ', NEW.expected_value,
            ', got ', NEW.actual_value);
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT=@assertion_message;
    END IF;
END//
DELIMITER ;

INSERT INTO milestone6_takeover_assertion VALUES
('exact target rows taken over',
 (SELECT COUNT(*) FROM inbox_message
   WHERE status='RECOVERY_REQUIRED'
     AND last_error_code='WORKFLOW_APPROVER_NOT_ELIGIBLE'
     AND contract_ref='flowdesk.workflow.start.v1' AND contract_version=1), 2),
('one recovery per target',
 (SELECT COUNT(*) FROM message_recovery
   WHERE direction='INBOX' AND state='OPEN'
     AND last_error_code='WORKFLOW_APPROVER_NOT_ELIGIBLE'), 2),
('one migration audit per target',
 (SELECT COUNT(*) FROM audit_record
   WHERE action_code='WORKFLOW_START_IAM_DENIAL_TAKEN_OVER'
     AND outcome='SUCCEEDED'), 2),
('historical audits preserved',
 (SELECT COUNT(*) FROM audit_record
   WHERE action_code='WORKFLOW_START_REJECTED'
     AND error_code='WORKFLOW_APPROVER_NOT_ELIGIBLE'), 2),
('version near misses untouched',
 (SELECT COUNT(*) FROM inbox_message
   WHERE status='REJECTED'
     AND last_error_code='WORKFLOW_APPROVER_NOT_ELIGIBLE'
     AND contract_ref='flowdesk.workflow.start.v1' AND contract_version=2), 2),
('error near misses untouched',
 (SELECT COUNT(*) FROM inbox_message
   WHERE status='REJECTED' AND last_error_code='WORKFLOW_VERSION_MISMATCH'
     AND contract_ref='flowdesk.workflow.start.v1' AND contract_version=1), 2),
('source bytes and hash preserved',
 (SELECT COUNT(*) FROM inbox_message
   WHERE body_bytes=X'7B7D' AND body_sha256=UNHEX(SHA2('{}', 256))), 6),
('tenant scoped recovery pairing',
 (SELECT COUNT(*)
    FROM message_recovery r
    JOIN inbox_message i
      ON i.tenant_id=r.tenant_id AND i.inbox_id=r.source_record_id
   WHERE r.direction='INBOX' AND r.business_intent_key=i.business_intent_key), 2);

DROP TRIGGER milestone6_takeover_assertion_bi;
DROP TABLE milestone6_takeover_assertion;

CREATE TABLE milestone6_takeover_result (
    marker VARCHAR(96) NOT NULL PRIMARY KEY
) ENGINE=InnoDB;
INSERT INTO milestone6_takeover_result
VALUES ('FLOWDESK_MILESTONE_6_HISTORICAL_TAKEOVER_OK');
