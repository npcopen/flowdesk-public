-- Run as the bootstrap DBA after the IAM Flyway baseline has succeeded.
-- The runtime user is created by bootstrap; this script contains no secrets.

-- Read-only migration metadata is required by the service readiness probe. Keep
-- this grant scoped to the service-owned schema; runtime code never needs Flyway
-- write or cross-schema privileges.
GRANT SELECT ON flowdesk_iam.flyway_schema_history
    TO 'fd_iam_runtime'@'%';

GRANT SELECT, INSERT ON flowdesk_iam.tenant
    TO 'fd_iam_runtime'@'%';
GRANT UPDATE (
    display_name,
    lock_version,
    updated_at,
    updated_by_type,
    updated_by_id
) ON flowdesk_iam.tenant TO 'fd_iam_runtime'@'%';

GRANT SELECT, INSERT ON flowdesk_iam.user_account
    TO 'fd_iam_runtime'@'%';
GRANT UPDATE (
    display_name,
    status,
    lock_version,
    updated_at,
    updated_by_type,
    updated_by_id
) ON flowdesk_iam.user_account TO 'fd_iam_runtime'@'%';

GRANT SELECT, INSERT ON flowdesk_iam.user_credential
    TO 'fd_iam_runtime'@'%';
GRANT UPDATE (
    password_hash,
    password_hash_scheme,
    failed_attempt_count,
    locked_until,
    password_changed_at,
    lock_version,
    updated_at,
    updated_by_type,
    updated_by_id
) ON flowdesk_iam.user_credential TO 'fd_iam_runtime'@'%';

GRANT SELECT, INSERT ON flowdesk_iam.role
    TO 'fd_iam_runtime'@'%';
GRANT UPDATE (
    role_name,
    status,
    lock_version,
    updated_at,
    updated_by_type,
    updated_by_id
) ON flowdesk_iam.role TO 'fd_iam_runtime'@'%';

GRANT SELECT, INSERT ON flowdesk_iam.permission
    TO 'fd_iam_runtime'@'%';

GRANT SELECT, INSERT ON flowdesk_iam.role_permission
    TO 'fd_iam_runtime'@'%';
GRANT UPDATE (
    state,
    granted_at,
    granted_by_type,
    granted_by_id,
    revoked_at,
    revoked_by_type,
    revoked_by_id,
    lock_version
) ON flowdesk_iam.role_permission TO 'fd_iam_runtime'@'%';

GRANT SELECT, INSERT ON flowdesk_iam.user_role_assignment
    TO 'fd_iam_runtime'@'%';
GRANT UPDATE (
    state,
    granted_at,
    granted_by_type,
    granted_by_id,
    revoked_at,
    revoked_by_type,
    revoked_by_id,
    lock_version
) ON flowdesk_iam.user_role_assignment TO 'fd_iam_runtime'@'%';

GRANT SELECT, INSERT ON flowdesk_iam.audit_record
    TO 'fd_iam_runtime'@'%';
