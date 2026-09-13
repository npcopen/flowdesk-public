-- FlowDesk IAM schema baseline.
-- The connection's default schema must be flowdesk_iam. This migration never
-- qualifies or accesses another schema.

CREATE TABLE tenant (
    tenant_id BINARY(16) NOT NULL,
    tenant_code VARCHAR(64) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    lock_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by_type VARCHAR(16) NOT NULL,
    created_by_id BINARY(16) NULL DEFAULT NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_by_type VARCHAR(16) NOT NULL,
    updated_by_id BINARY(16) NULL DEFAULT NULL,
    CONSTRAINT pk_iam_01_tenant PRIMARY KEY (tenant_id),
    KEY idx_iam_02_tenant_code (tenant_code, tenant_id)
) ENGINE = InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;

CREATE TABLE user_account (
    tenant_id BINARY(16) NOT NULL,
    user_id BINARY(16) NOT NULL,
    username VARCHAR(128) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    lock_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by_type VARCHAR(16) NOT NULL,
    created_by_id BINARY(16) NULL DEFAULT NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_by_type VARCHAR(16) NOT NULL,
    updated_by_id BINARY(16) NULL DEFAULT NULL,
    CONSTRAINT pk_iam_03_user_account PRIMARY KEY (tenant_id, user_id),
    CONSTRAINT uk_iam_04_user_username UNIQUE (tenant_id, username),
    KEY idx_iam_05_user_status_username (tenant_id, status, username),
    CONSTRAINT fk_iam_user_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenant (tenant_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT ck_iam_user_status_actor CHECK (
        status IN ('ACTIVE', 'INACTIVE')
        AND created_by_type IN ('USER', 'SERVICE')
        AND updated_by_type IN ('USER', 'SERVICE')
        AND (created_by_type <> 'USER' OR created_by_id IS NOT NULL)
        AND (updated_by_type <> 'USER' OR updated_by_id IS NOT NULL)
    )
) ENGINE = InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;

CREATE TABLE user_credential (
    tenant_id BINARY(16) NOT NULL,
    user_id BINARY(16) NOT NULL,
    credential_kind VARCHAR(16) NOT NULL DEFAULT 'PASSWORD',
    password_hash VARBINARY(512) NOT NULL,
    password_hash_scheme VARCHAR(64) NOT NULL,
    failed_attempt_count INT UNSIGNED NOT NULL DEFAULT 0,
    locked_until TIMESTAMP(6) NULL DEFAULT NULL,
    password_changed_at TIMESTAMP(6) NOT NULL,
    lock_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by_type VARCHAR(16) NOT NULL,
    created_by_id BINARY(16) NULL DEFAULT NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_by_type VARCHAR(16) NOT NULL,
    updated_by_id BINARY(16) NULL DEFAULT NULL,
    CONSTRAINT pk_iam_06_user_credential PRIMARY KEY (tenant_id, user_id),
    KEY idx_iam_07_credential_lock (tenant_id, locked_until, user_id),
    CONSTRAINT fk_iam_credential_user
        FOREIGN KEY (tenant_id, user_id)
        REFERENCES user_account (tenant_id, user_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT ck_iam_credential_kind CHECK (
        credential_kind = 'PASSWORD'
    ),
    CONSTRAINT ck_iam_credential_lock CHECK (
        OCTET_LENGTH(password_hash) > 0
        AND CHAR_LENGTH(password_hash_scheme) > 0
        AND failed_attempt_count >= 0
        AND (locked_until IS NULL OR failed_attempt_count > 0)
        AND created_by_type IN ('USER', 'SERVICE')
        AND updated_by_type IN ('USER', 'SERVICE')
        AND (created_by_type <> 'USER' OR created_by_id IS NOT NULL)
        AND (updated_by_type <> 'USER' OR updated_by_id IS NOT NULL)
    )
) ENGINE = InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;

CREATE TABLE role (
    tenant_id BINARY(16) NOT NULL,
    role_id BINARY(16) NOT NULL,
    role_code VARCHAR(64) NOT NULL,
    role_name VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    lock_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by_type VARCHAR(16) NOT NULL,
    created_by_id BINARY(16) NULL DEFAULT NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_by_type VARCHAR(16) NOT NULL,
    updated_by_id BINARY(16) NULL DEFAULT NULL,
    CONSTRAINT pk_iam_08_role PRIMARY KEY (tenant_id, role_id),
    CONSTRAINT uk_iam_09_role_code UNIQUE (tenant_id, role_code),
    KEY idx_iam_10_role_status_name
        (tenant_id, status, role_name, role_id),
    CONSTRAINT fk_iam_role_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenant (tenant_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT ck_iam_role_status_actor CHECK (
        status IN ('ACTIVE', 'INACTIVE')
        AND created_by_type IN ('USER', 'SERVICE')
        AND updated_by_type IN ('USER', 'SERVICE')
        AND (created_by_type <> 'USER' OR created_by_id IS NOT NULL)
        AND (updated_by_type <> 'USER' OR updated_by_id IS NOT NULL)
    )
) ENGINE = InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;

CREATE TABLE permission (
    tenant_id BINARY(16) NOT NULL,
    permission_id BINARY(16) NOT NULL,
    permission_code VARCHAR(96) NOT NULL,
    permission_name VARCHAR(128) NOT NULL,
    description VARCHAR(512) NULL DEFAULT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by_type VARCHAR(16) NOT NULL,
    created_by_id BINARY(16) NULL DEFAULT NULL,
    CONSTRAINT pk_iam_11_permission PRIMARY KEY (tenant_id, permission_id),
    CONSTRAINT uk_iam_12_permission_code
        UNIQUE (tenant_id, permission_code),
    CONSTRAINT fk_iam_permission_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenant (tenant_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;

CREATE TABLE role_permission (
    tenant_id BINARY(16) NOT NULL,
    role_id BINARY(16) NOT NULL,
    permission_id BINARY(16) NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'GRANTED',
    granted_at TIMESTAMP(6) NOT NULL,
    granted_by_type VARCHAR(16) NOT NULL,
    granted_by_id BINARY(16) NULL DEFAULT NULL,
    revoked_at TIMESTAMP(6) NULL DEFAULT NULL,
    revoked_by_type VARCHAR(16) NULL DEFAULT NULL,
    revoked_by_id BINARY(16) NULL DEFAULT NULL,
    lock_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    CONSTRAINT pk_iam_13_role_permission
        PRIMARY KEY (tenant_id, role_id, permission_id),
    KEY idx_iam_14_permission_roles
        (tenant_id, permission_id, state, role_id),
    CONSTRAINT fk_iam_role_permission_role
        FOREIGN KEY (tenant_id, role_id)
        REFERENCES role (tenant_id, role_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_iam_role_permission_permission
        FOREIGN KEY (tenant_id, permission_id)
        REFERENCES permission (tenant_id, permission_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT ck_iam_role_permission_state CHECK (
        granted_by_type IN ('USER', 'SERVICE', 'INTEGRATION', 'SCHEDULER')
        AND (granted_by_type <> 'USER' OR granted_by_id IS NOT NULL)
        AND (
            (state = 'GRANTED'
                AND revoked_at IS NULL
                AND revoked_by_type IS NULL
                AND revoked_by_id IS NULL)
            OR
            (state = 'REVOKED'
                AND revoked_at IS NOT NULL
                AND revoked_at >= granted_at
                AND revoked_by_type IN
                    ('USER', 'SERVICE', 'INTEGRATION', 'SCHEDULER')
                AND (revoked_by_type <> 'USER' OR revoked_by_id IS NOT NULL))
        )
    )
) ENGINE = InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;

CREATE TABLE user_role_assignment (
    tenant_id BINARY(16) NOT NULL,
    user_id BINARY(16) NOT NULL,
    role_id BINARY(16) NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'GRANTED',
    granted_at TIMESTAMP(6) NOT NULL,
    granted_by_type VARCHAR(16) NOT NULL,
    granted_by_id BINARY(16) NULL DEFAULT NULL,
    revoked_at TIMESTAMP(6) NULL DEFAULT NULL,
    revoked_by_type VARCHAR(16) NULL DEFAULT NULL,
    revoked_by_id BINARY(16) NULL DEFAULT NULL,
    lock_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    CONSTRAINT pk_iam_15_user_role
        PRIMARY KEY (tenant_id, user_id, role_id),
    KEY idx_iam_16_role_users (tenant_id, role_id, state, user_id),
    KEY idx_iam_17_user_eligibility (tenant_id, user_id, state, role_id),
    CONSTRAINT fk_iam_user_role_user
        FOREIGN KEY (tenant_id, user_id)
        REFERENCES user_account (tenant_id, user_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_iam_user_role_role
        FOREIGN KEY (tenant_id, role_id)
        REFERENCES role (tenant_id, role_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT ck_iam_user_role_state CHECK (
        granted_by_type IN ('USER', 'SERVICE', 'INTEGRATION', 'SCHEDULER')
        AND (granted_by_type <> 'USER' OR granted_by_id IS NOT NULL)
        AND (
            (state = 'GRANTED'
                AND revoked_at IS NULL
                AND revoked_by_type IS NULL
                AND revoked_by_id IS NULL)
            OR
            (state = 'REVOKED'
                AND revoked_at IS NOT NULL
                AND revoked_at >= granted_at
                AND revoked_by_type IN
                    ('USER', 'SERVICE', 'INTEGRATION', 'SCHEDULER')
                AND (revoked_by_type <> 'USER' OR revoked_by_id IS NOT NULL))
        )
    )
) ENGINE = InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;

CREATE TABLE audit_record (
    tenant_id BINARY(16) NOT NULL,
    audit_id BINARY(16) NOT NULL,
    action_code VARCHAR(96) NOT NULL,
    target_type VARCHAR(48) NOT NULL,
    target_id BINARY(16) NULL DEFAULT NULL,
    outcome VARCHAR(16) NOT NULL,
    actor_type VARCHAR(16) NOT NULL,
    actor_id BINARY(16) NULL DEFAULT NULL,
    reason VARCHAR(1000) NULL DEFAULT NULL,
    error_code VARCHAR(96) NULL DEFAULT NULL,
    correlation_id BINARY(16) NOT NULL,
    causation_id BINARY(16) NULL DEFAULT NULL,
    occurred_at TIMESTAMP(6) NOT NULL,
    recorded_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_iam_18_audit PRIMARY KEY (tenant_id, audit_id),
    KEY idx_iam_19_audit_target
        (tenant_id, target_type, target_id, occurred_at, audit_id),
    KEY idx_iam_20_audit_actor
        (tenant_id, actor_type, actor_id, occurred_at, audit_id),
    KEY idx_iam_21_audit_correlation
        (tenant_id, correlation_id, occurred_at, audit_id),
    CONSTRAINT ck_iam_audit_outcome CHECK (
        outcome IN ('SUCCEEDED', 'REJECTED', 'FAILED')
    ),
    CONSTRAINT ck_iam_audit_actor CHECK (
        actor_type IN ('USER', 'SERVICE', 'SCHEDULER')
        AND (actor_type <> 'USER' OR actor_id IS NOT NULL)
    )
) ENGINE = InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
