-- REPLAYING Inbox rows use next_attempt_at as a recoverable execution lease.
ALTER TABLE message_recovery DROP CHECK ck_not_recovery_inbox_state_times,
    ADD CONSTRAINT ck_not_recovery_inbox_state_times CHECK (
        direction = 'INBOX'
        AND state IN ('OPEN', 'REPLAYING', 'RESOLVED')
        AND CHAR_LENGTH(last_error_code) > 0
        AND (dead_lettered_at IS NULL OR dead_lettered_at <= recovery_required_at)
        AND (next_attempt_at IS NULL OR next_attempt_at >= recovery_required_at)
        AND (state IN ('OPEN', 'REPLAYING') OR next_attempt_at IS NULL)
        AND (
            (replay_count = 0
                AND last_replayed_at IS NULL
                AND last_replayed_by_type IS NULL
                AND last_replayed_by_id IS NULL
                AND last_replay_reason IS NULL)
            OR (replay_count > 0
                AND last_replayed_at IS NOT NULL
                AND last_replayed_at >= recovery_required_at
                AND last_replayed_by_type IN ('USER', 'SERVICE', 'SCHEDULER')
                AND (last_replayed_by_type <> 'USER' OR last_replayed_by_id IS NOT NULL)
                AND last_replay_reason IS NOT NULL
                AND CHAR_LENGTH(TRIM(last_replay_reason)) > 0)
        )
        AND (
            (state IN ('OPEN', 'REPLAYING') AND resolved_at IS NULL)
            OR (state = 'RESOLVED'
                AND resolved_at IS NOT NULL
                AND next_attempt_at IS NULL
                AND resolved_at >= recovery_required_at)
        )
        AND (state <> 'REPLAYING' OR replay_count > 0)
        AND updated_by_type IN ('USER', 'SERVICE', 'SCHEDULER')
        AND (updated_by_type <> 'USER' OR updated_by_id IS NOT NULL)
    ),
    ADD INDEX idx_not_replay_lease
        (direction, state, next_attempt_at, tenant_id, recovery_id);
