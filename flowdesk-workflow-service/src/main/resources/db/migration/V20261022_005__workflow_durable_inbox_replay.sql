-- The existing Workflow recovery CHECK permits an INBOX execution lease in
-- next_attempt_at. This index supports bounded takeover scans across tenants.
ALTER TABLE message_recovery
    ADD INDEX idx_wf_replay_resume
        (direction, state, next_attempt_at, tenant_id, recovery_id);
