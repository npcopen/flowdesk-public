CREATE TABLE IF NOT EXISTS tenant (
    tenant_id BINARY(16) NOT NULL PRIMARY KEY,
    tenant_code VARCHAR(64) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    lock_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by_type VARCHAR(16) NOT NULL,
    created_by_id BINARY(16) NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_by_type VARCHAR(16) NOT NULL,
    updated_by_id BINARY(16) NULL
);

CREATE TABLE IF NOT EXISTS user_account (
    tenant_id BINARY(16) NOT NULL,
    user_id BINARY(16) NOT NULL,
    username VARCHAR(128) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    lock_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by_type VARCHAR(16) NOT NULL,
    created_by_id BINARY(16) NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_by_type VARCHAR(16) NOT NULL,
    updated_by_id BINARY(16) NULL,
    PRIMARY KEY (tenant_id, user_id),
    UNIQUE (tenant_id, username)
);

CREATE TABLE IF NOT EXISTS user_credential (
    tenant_id BINARY(16) NOT NULL,
    user_id BINARY(16) NOT NULL,
    credential_kind VARCHAR(16) NOT NULL DEFAULT 'PASSWORD',
    password_hash VARBINARY(512) NOT NULL,
    password_hash_scheme VARCHAR(64) NOT NULL,
    failed_attempt_count INT NOT NULL DEFAULT 0,
    locked_until TIMESTAMP(6) NULL,
    password_changed_at TIMESTAMP(6) NOT NULL,
    lock_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by_type VARCHAR(16) NOT NULL,
    created_by_id BINARY(16) NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_by_type VARCHAR(16) NOT NULL,
    updated_by_id BINARY(16) NULL,
    PRIMARY KEY (tenant_id, user_id)
);

CREATE TABLE IF NOT EXISTS role (
    tenant_id BINARY(16) NOT NULL,
    role_id BINARY(16) NOT NULL,
    role_code VARCHAR(64) NOT NULL,
    role_name VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    lock_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by_type VARCHAR(16) NOT NULL,
    created_by_id BINARY(16) NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_by_type VARCHAR(16) NOT NULL,
    updated_by_id BINARY(16) NULL,
    PRIMARY KEY (tenant_id, role_id),
    UNIQUE (tenant_id, role_code)
);

CREATE TABLE IF NOT EXISTS permission (
    tenant_id BINARY(16) NOT NULL,
    permission_id BINARY(16) NOT NULL,
    permission_code VARCHAR(96) NOT NULL,
    permission_name VARCHAR(128) NOT NULL,
    description VARCHAR(512) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by_type VARCHAR(16) NOT NULL,
    created_by_id BINARY(16) NULL,
    PRIMARY KEY (tenant_id, permission_id),
    UNIQUE (tenant_id, permission_code)
);

CREATE TABLE IF NOT EXISTS role_permission (
    tenant_id BINARY(16) NOT NULL,
    role_id BINARY(16) NOT NULL,
    permission_id BINARY(16) NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'GRANTED',
    granted_at TIMESTAMP(6) NOT NULL,
    granted_by_type VARCHAR(16) NOT NULL,
    granted_by_id BINARY(16) NULL,
    revoked_at TIMESTAMP(6) NULL,
    revoked_by_type VARCHAR(16) NULL,
    revoked_by_id BINARY(16) NULL,
    lock_version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id, role_id, permission_id)
);

CREATE TABLE IF NOT EXISTS user_role_assignment (
    tenant_id BINARY(16) NOT NULL,
    user_id BINARY(16) NOT NULL,
    role_id BINARY(16) NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'GRANTED',
    granted_at TIMESTAMP(6) NOT NULL,
    granted_by_type VARCHAR(16) NOT NULL,
    granted_by_id BINARY(16) NULL,
    revoked_at TIMESTAMP(6) NULL,
    revoked_by_type VARCHAR(16) NULL,
    revoked_by_id BINARY(16) NULL,
    lock_version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id, user_id, role_id)
);

CREATE TABLE IF NOT EXISTS audit_record (
    tenant_id BINARY(16) NOT NULL,
    audit_id BINARY(16) NOT NULL,
    action_code VARCHAR(96) NOT NULL,
    target_type VARCHAR(48) NOT NULL,
    target_id BINARY(16) NULL,
    outcome VARCHAR(16) NOT NULL,
    actor_type VARCHAR(16) NOT NULL,
    actor_id BINARY(16) NULL,
    reason VARCHAR(1000) NULL,
    error_code VARCHAR(96) NULL,
    correlation_id BINARY(16) NOT NULL,
    causation_id BINARY(16) NULL,
    occurred_at TIMESTAMP(6) NOT NULL,
    recorded_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (tenant_id, audit_id)
);
