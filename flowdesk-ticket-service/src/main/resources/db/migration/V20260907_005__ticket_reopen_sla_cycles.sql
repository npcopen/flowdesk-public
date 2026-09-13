-- A reopened ticket starts a new resolution cycle; earlier facts stay queryable.
ALTER TABLE sla_clock
    ADD COLUMN cycle_no INT UNSIGNED NOT NULL DEFAULT 0,
    DROP INDEX uk_tkt_27,
    ADD CONSTRAINT uk_tkt_sla_cycle UNIQUE (tenant_id, ticket_id, clock_type, cycle_no);

-- Restore tracking for tickets reopened before this upgrade. Use the recorded
-- reopening time and the original policy snapshot, including already overdue work.
INSERT INTO sla_clock
    (tenant_id, sla_clock_id, ticket_id, sla_policy_id, clock_type, status,
     started_at, deadline_at, reminder_due_at, policy_duration_seconds,
     policy_reminder_before_seconds, cycle_no, created_by_type, updated_by_type)
SELECT c.tenant_id,
       UNHEX(SUBSTRING(SHA2(CONCAT('FD-SLA-REOPEN-BACKFILL:', HEX(c.tenant_id), ':',
                                 HEX(c.ticket_id), ':', t.reopen_count), 256), 1, 32)),
       c.ticket_id, c.sla_policy_id, 'RESOLUTION', 'RUNNING', t.last_reopened_at,
       TIMESTAMPADD(SECOND, c.policy_duration_seconds, t.last_reopened_at),
       CASE WHEN c.policy_reminder_before_seconds IS NULL THEN NULL
            ELSE TIMESTAMPADD(SECOND, c.policy_duration_seconds - c.policy_reminder_before_seconds,
                              t.last_reopened_at) END,
       c.policy_duration_seconds, c.policy_reminder_before_seconds, t.reopen_count, 'SERVICE', 'SERVICE'
FROM sla_clock c JOIN ticket t ON t.tenant_id=c.tenant_id AND t.ticket_id=c.ticket_id
WHERE c.clock_type='RESOLUTION' AND c.cycle_no=0
  AND t.reopen_count > 0 AND t.last_reopened_at IS NOT NULL
  AND t.status IN ('ASSIGNED', 'IN_PROGRESS');

DELIMITER $$
CREATE TRIGGER trg_tkt_sla_cycle_bu_guard
BEFORE UPDATE ON sla_clock FOR EACH ROW
BEGIN
    IF NEW.cycle_no <> OLD.cycle_no THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'SLA cycle number is immutable';
    END IF;
END$$
DELIMITER ;
