package com.flowdesk.iam.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * IAM persistence. Every query is tenant-scoped; no cross-schema access.
 */
@Repository
public class IamRepository {

    private final JdbcTemplate jdbc;

    public IamRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ----- login / current user -------------------------------------------------

    public List<UUID> findTenantIdsByCode(String tenantCode) {
        List<byte[]> rows = jdbc.query(
                "SELECT tenant_id FROM tenant WHERE tenant_code = ? ORDER BY tenant_id",
                (rs, i) -> rs.getBytes(1), tenantCode);
        List<UUID> result = new ArrayList<>(rows.size());
        for (byte[] raw : rows) {
            result.add(Uuids.toUuid(raw));
        }
        return result;
    }

    public boolean tenantExists(UUID tenantId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tenant WHERE tenant_id = ?",
                Integer.class, Uuids.toBytes(tenantId));
        return count != null && count > 0;
    }

    public Optional<CredentialRow> findCredential(UUID tenantId, String username) {
        List<CredentialRow> rows = jdbc.query("""
                SELECT t.tenant_id, u.user_id, u.username, u.status,
                       t.tenant_code, t.display_name,
                       c.password_hash, c.failed_attempt_count, c.locked_until
                FROM user_account u
                JOIN tenant t ON t.tenant_id = u.tenant_id
                JOIN user_credential c
                  ON c.tenant_id = u.tenant_id AND c.user_id = u.user_id
                WHERE u.tenant_id = ? AND u.username = ?
                """, CREDENTIAL_MAPPER, Uuids.toBytes(tenantId), username);
        return rows.stream().findFirst();
    }

    public void updateLoginSuccess(UUID tenantId, UUID userId) {
        jdbc.update("""
                UPDATE user_credential
                SET failed_attempt_count = 0,
                    locked_until = NULL,
                    updated_at = CURRENT_TIMESTAMP(6),
                    updated_by_type = 'SERVICE',
                    updated_by_id = NULL
                WHERE tenant_id = ? AND user_id = ?
                """, Uuids.toBytes(tenantId), Uuids.toBytes(userId));
    }

    /**
     * Records one failed credential attempt. An expired temporary lock is treated as a
     * fresh attempt window (the counter restarts at one), while an active lock keeps its
     * counter. Once the failed count reaches the configured threshold,
     * {@code locked_until} is set. The exact threshold and lock duration remain
     * SECURITY-controlled configuration.
     *
     * @param now               current instant, used to detect an expired lock
     * @param maxFailedAttempts threshold after which the account becomes temporarily locked
     * @param lockUntil         instant to set when the threshold is reached
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailedAttempt(UUID tenantId, UUID userId, Instant now,
                                    int maxFailedAttempts, Instant lockUntil) {
        jdbc.update("""
                UPDATE user_credential
                SET failed_attempt_count = CASE
                        WHEN locked_until IS NOT NULL AND locked_until <= ? THEN 1
                        ELSE failed_attempt_count + 1
                    END,
                    locked_until = CASE
                        WHEN locked_until IS NOT NULL AND locked_until <= ? THEN NULL
                        ELSE locked_until
                    END,
                    updated_at = CURRENT_TIMESTAMP(6),
                    updated_by_type = 'SERVICE',
                    updated_by_id = NULL
                WHERE tenant_id = ? AND user_id = ?
                """, Timestamp.from(now), Timestamp.from(now),
                Uuids.toBytes(tenantId), Uuids.toBytes(userId));
        jdbc.update("""
                UPDATE user_credential
                SET locked_until = ?,
                    updated_at = CURRENT_TIMESTAMP(6),
                    updated_by_type = 'SERVICE',
                    updated_by_id = NULL
                WHERE tenant_id = ? AND user_id = ? AND failed_attempt_count >= ?
                """, Timestamp.from(lockUntil),
                Uuids.toBytes(tenantId), Uuids.toBytes(userId), maxFailedAttempts);
    }

    public Optional<CurrentUserRow> findCurrentUser(UUID tenantId, UUID userId) {
        List<CurrentUserRow> rows = jdbc.query("""
                SELECT u.user_id, u.username, u.display_name, u.status,
                       t.tenant_code, t.display_name AS tenant_display_name
                FROM user_account u
                JOIN tenant t ON t.tenant_id = u.tenant_id
                WHERE u.tenant_id = ? AND u.user_id = ?
                """, (rs, i) -> new CurrentUserRow(
                        Uuids.toUuid(rs.getBytes("user_id")),
                        rs.getString("username"),
                        rs.getString("display_name"),
                        rs.getString("status"),
                        rs.getString("tenant_code"),
                        rs.getString("tenant_display_name")),
                Uuids.toBytes(tenantId), Uuids.toBytes(userId));
        return rows.stream().findFirst();
    }

    public List<String> findGrantedRoleCodes(UUID tenantId, UUID userId) {
        return jdbc.query("""
                SELECT r.role_code
                FROM user_role_assignment ura
                JOIN role r ON r.tenant_id = ura.tenant_id AND r.role_id = ura.role_id
                WHERE ura.tenant_id = ? AND ura.user_id = ?
                  AND ura.state = 'GRANTED' AND r.status = 'ACTIVE'
                ORDER BY r.role_code
                """, (rs, i) -> rs.getString(1), Uuids.toBytes(tenantId), Uuids.toBytes(userId));
    }

    /**
     * Batch role-code lookup for a list of users (avoids the N+1 in list/summary paths).
     *
     * @return userId → sorted granted ACTIVE role codes
     */
    public Map<UUID, List<String>> findGrantedRoleCodesForUsers(UUID tenantId, Collection<UUID> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        List<byte[]> ids = userIds.stream().map(Uuids::toBytes).toList();
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(Uuids.toBytes(tenantId));
        args.addAll(ids);
        Map<UUID, List<String>> result = new HashMap<>();
        jdbc.query("""
                SELECT ura.user_id, r.role_code
                FROM user_role_assignment ura
                JOIN role r ON r.tenant_id = ura.tenant_id AND r.role_id = ura.role_id
                WHERE ura.tenant_id = ? AND ura.user_id IN (""" + placeholders + """
                )
                  AND ura.state = 'GRANTED' AND r.status = 'ACTIVE'
                ORDER BY r.role_code
                """, (rs, i) -> {
            UUID userId = Uuids.toUuid(rs.getBytes("user_id"));
            result.computeIfAbsent(userId, k -> new ArrayList<>())
                    .add(rs.getString("role_code"));
            return null;
        }, args.toArray());
        return result;
    }

    /**
     * Batch permission-code lookup (avoids the N+1 when listing role-permission bindings).
     */
    public Map<UUID, String> findPermissionCodes(UUID tenantId, Collection<UUID> permissionIds) {
        if (permissionIds.isEmpty()) {
            return Map.of();
        }
        List<byte[]> ids = permissionIds.stream().map(Uuids::toBytes).toList();
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(Uuids.toBytes(tenantId));
        args.addAll(ids);
        Map<UUID, String> result = new HashMap<>();
        jdbc.query("""
                SELECT permission_id, permission_code
                FROM permission
                WHERE tenant_id = ? AND permission_id IN (""" + placeholders + """
                )
                """, (rs, i) -> {
            result.put(Uuids.toUuid(rs.getBytes("permission_id")), rs.getString("permission_code"));
            return null;
        }, args.toArray());
        return result;
    }

    /**
     * Batch role-code lookup (avoids the N+1 when listing user-role bindings).
     */
    public Map<UUID, String> findRoleCodes(UUID tenantId, Collection<UUID> roleIds) {
        if (roleIds.isEmpty()) {
            return Map.of();
        }
        List<byte[]> ids = roleIds.stream().map(Uuids::toBytes).toList();
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(Uuids.toBytes(tenantId));
        args.addAll(ids);
        Map<UUID, String> result = new HashMap<>();
        jdbc.query("""
                SELECT role_id, role_code
                FROM role
                WHERE tenant_id = ? AND role_id IN (""" + placeholders + """
                )
                """, (rs, i) -> {
            result.put(Uuids.toUuid(rs.getBytes("role_id")), rs.getString("role_code"));
            return null;
        }, args.toArray());
        return result;
    }

    public List<String> findGrantedPermissionCodes(UUID tenantId, UUID userId) {
        return jdbc.query("""
                SELECT DISTINCT p.permission_code
                FROM user_role_assignment ura
                JOIN role r ON r.tenant_id = ura.tenant_id AND r.role_id = ura.role_id
                JOIN role_permission rp
                  ON rp.tenant_id = r.tenant_id AND rp.role_id = r.role_id
                JOIN permission p
                  ON p.tenant_id = rp.tenant_id AND p.permission_id = rp.permission_id
                WHERE ura.tenant_id = ? AND ura.user_id = ?
                  AND ura.state = 'GRANTED' AND r.status = 'ACTIVE'
                  AND rp.state = 'GRANTED'
                ORDER BY p.permission_code
                """, (rs, i) -> rs.getString(1), Uuids.toBytes(tenantId), Uuids.toBytes(userId));
    }

    // ----- users ---------------------------------------------------------------

    public Optional<UserRow> findUser(UUID tenantId, UUID userId) {
        return jdbc.query("""
                SELECT user_id, username, display_name, status, lock_version, created_at, updated_at
                FROM user_account WHERE tenant_id = ? AND user_id = ?
                """, USER_MAPPER, Uuids.toBytes(tenantId), Uuids.toBytes(userId)).stream().findFirst();
    }

    /** Current read used by idempotent replay paths (sees rows committed by a competing winner). */
    public Optional<UserRow> findUserForUpdate(UUID tenantId, UUID userId) {
        return jdbc.query("""
                SELECT user_id, username, display_name, status, lock_version, created_at, updated_at
                FROM user_account WHERE tenant_id = ? AND user_id = ? FOR UPDATE
                """, USER_MAPPER, Uuids.toBytes(tenantId), Uuids.toBytes(userId)).stream().findFirst();
    }

    public boolean usernameExists(UUID tenantId, String username) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_account WHERE tenant_id = ? AND username = ?",
                Integer.class, Uuids.toBytes(tenantId), username);
        return count != null && count > 0;
    }

    public void insertUser(UUID tenantId, UUID userId, String username, String displayName,
                           String status, UUID actorId, Instant now) {
        jdbc.update("""
                INSERT INTO user_account
                  (tenant_id, user_id, username, display_name, status, lock_version,
                   created_at, created_by_type, created_by_id,
                   updated_at, updated_by_type, updated_by_id)
                VALUES (?, ?, ?, ?, ?, 0, ?, 'USER', ?, ?, 'USER', ?)
                """, Uuids.toBytes(tenantId), Uuids.toBytes(userId), username, displayName,
                status, Timestamp.from(now), Uuids.toBytes(actorId), Timestamp.from(now), Uuids.toBytes(actorId));
    }

    public void insertCredential(UUID tenantId, UUID userId, byte[] passwordHash, String scheme,
                                 UUID actorId, Instant now) {
        jdbc.update("""
                INSERT INTO user_credential
                  (tenant_id, user_id, credential_kind, password_hash, password_hash_scheme,
                   failed_attempt_count, locked_until, password_changed_at, lock_version,
                   created_at, created_by_type, created_by_id,
                   updated_at, updated_by_type, updated_by_id)
                VALUES (?, ?, 'PASSWORD', ?, ?, 0, NULL, ?, 0, ?, 'USER', ?, ?, 'USER', ?)
                """, Uuids.toBytes(tenantId), Uuids.toBytes(userId), passwordHash, scheme,
                Timestamp.from(now), Timestamp.from(now), Uuids.toBytes(actorId),
                Timestamp.from(now), Uuids.toBytes(actorId));
    }

    public boolean updateUser(UUID tenantId, UUID userId, long expectedLockVersion,
                              String displayName, String status, UUID actorId, Instant now) {
        int updated = jdbc.update("""
                UPDATE user_account
                SET display_name = COALESCE(?, display_name),
                    status = COALESCE(?, status),
                    lock_version = lock_version + 1,
                    updated_at = ?,
                    updated_by_type = 'USER',
                    updated_by_id = ?
                WHERE tenant_id = ? AND user_id = ? AND lock_version = ?
                """, displayName, status, Timestamp.from(now), Uuids.toBytes(actorId),
                Uuids.toBytes(tenantId), Uuids.toBytes(userId), expectedLockVersion);
        return updated == 1;
    }

    public List<UserRow> findUsers(UUID tenantId, String status, String roleCode,
                                   String sortColumn, boolean ascending,
                                   String lastName, UUID lastId, int pageSize) {
        StringBuilder sql = new StringBuilder("""
                SELECT DISTINCT u.user_id, u.username, u.display_name, u.status,
                       u.lock_version, u.created_at, u.updated_at
                FROM user_account u
                """);
        List<Object> args = new ArrayList<>();
        if (roleCode != null) {
            sql.append("""
                    JOIN user_role_assignment ura
                      ON ura.tenant_id = u.tenant_id AND ura.user_id = u.user_id
                    JOIN role r ON r.tenant_id = ura.tenant_id AND r.role_id = ura.role_id
                    """);
        }
        sql.append(" WHERE u.tenant_id = ?");
        args.add(Uuids.toBytes(tenantId));
        if (status != null) {
            sql.append(" AND u.status = ?");
            args.add(status);
        }
        if (roleCode != null) {
            sql.append(" AND ura.state = 'GRANTED' AND r.status = 'ACTIVE' AND r.role_code = ?");
            args.add(roleCode);
        }
        if (lastName != null && lastId != null) {
            String cmp = ascending ? ">" : "<";
            String cursorColumn = userSortColumn(sortColumn);
            sql.append(" AND (").append(cursorColumn).append(" ").append(cmp).append(" ? OR (")
                    .append(cursorColumn).append(" = ? AND u.user_id ").append(cmp).append(" ?))");
            args.add(lastName);
            args.add(lastName);
            args.add(Uuids.toBytes(lastId));
        }
        sql.append(" ORDER BY ").append(userSortColumn(sortColumn))
                .append(ascending ? " ASC" : " DESC")
                .append(", u.user_id ").append(ascending ? "ASC" : "DESC")
                .append(" LIMIT ").append(pageSize + 1);
        return jdbc.query(sql.toString(), USER_MAPPER, args.toArray());
    }

    // ----- roles ----------------------------------------------------------------

    public Optional<RoleRow> findRole(UUID tenantId, UUID roleId) {
        return jdbc.query("""
                SELECT role_id, role_code, role_name, status, lock_version, created_at, updated_at
                FROM role WHERE tenant_id = ? AND role_id = ?
                """, ROLE_MAPPER, Uuids.toBytes(tenantId), Uuids.toBytes(roleId)).stream().findFirst();
    }

    /** Current read used by idempotent replay paths (sees rows committed by a competing winner). */
    public Optional<RoleRow> findRoleForUpdate(UUID tenantId, UUID roleId) {
        return jdbc.query("""
                SELECT role_id, role_code, role_name, status, lock_version, created_at, updated_at
                FROM role WHERE tenant_id = ? AND role_id = ? FOR UPDATE
                """, ROLE_MAPPER, Uuids.toBytes(tenantId), Uuids.toBytes(roleId)).stream().findFirst();
    }

    public Optional<RoleRow> findRoleByCode(UUID tenantId, String roleCode) {
        return jdbc.query("""
                SELECT role_id, role_code, role_name, status, lock_version, created_at, updated_at
                FROM role WHERE tenant_id = ? AND role_code = ?
                """, ROLE_MAPPER, Uuids.toBytes(tenantId), roleCode).stream().findFirst();
    }

    public void insertRole(UUID tenantId, UUID roleId, String roleCode, String roleName,
                           String status, UUID actorId, Instant now) {
        jdbc.update("""
                INSERT INTO role
                  (tenant_id, role_id, role_code, role_name, status, lock_version,
                   created_at, created_by_type, created_by_id,
                   updated_at, updated_by_type, updated_by_id)
                VALUES (?, ?, ?, ?, ?, 0, ?, 'USER', ?, ?, 'USER', ?)
                """, Uuids.toBytes(tenantId), Uuids.toBytes(roleId), roleCode, roleName, status,
                Timestamp.from(now), Uuids.toBytes(actorId), Timestamp.from(now), Uuids.toBytes(actorId));
    }

    public boolean updateRole(UUID tenantId, UUID roleId, long expectedLockVersion,
                              String roleName, String status, UUID actorId, Instant now) {
        int updated = jdbc.update("""
                UPDATE role
                SET role_name = COALESCE(?, role_name),
                    status = COALESCE(?, status),
                    lock_version = lock_version + 1,
                    updated_at = ?,
                    updated_by_type = 'USER',
                    updated_by_id = ?
                WHERE tenant_id = ? AND role_id = ? AND lock_version = ?
                """, roleName, status, Timestamp.from(now), Uuids.toBytes(actorId),
                Uuids.toBytes(tenantId), Uuids.toBytes(roleId), expectedLockVersion);
        return updated == 1;
    }

    public List<RoleRow> findRoles(UUID tenantId, String status, String sortColumn,
                                   boolean ascending, String lastName, UUID lastId, int pageSize) {
        StringBuilder sql = new StringBuilder("""
                SELECT role_id, role_code, role_name, status, lock_version, created_at, updated_at
                FROM role WHERE tenant_id = ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(Uuids.toBytes(tenantId));
        if (status != null) {
            sql.append(" AND status = ?");
            args.add(status);
        }
        if (lastName != null && lastId != null) {
            String cmp = ascending ? ">" : "<";
            String cursorColumn = roleSortColumn(sortColumn);
            sql.append(" AND (").append(cursorColumn).append(" ").append(cmp).append(" ? OR (")
                    .append(cursorColumn).append(" = ? AND role_id ").append(cmp).append(" ?))");
            args.add(lastName);
            args.add(lastName);
            args.add(Uuids.toBytes(lastId));
        }
        sql.append(" ORDER BY ").append(roleSortColumn(sortColumn))
                .append(ascending ? " ASC" : " DESC")
                .append(", role_id ").append(ascending ? "ASC" : "DESC")
                .append(" LIMIT ").append(pageSize + 1);
        return jdbc.query(sql.toString(), ROLE_MAPPER, args.toArray());
    }

    // ----- permissions and bindings ---------------------------------------------

    public Optional<PermissionRow> findPermission(UUID tenantId, UUID permissionId) {
        return jdbc.query("""
                SELECT permission_id, permission_code, permission_name, description
                FROM permission WHERE tenant_id = ? AND permission_id = ?
                """, PERMISSION_MAPPER, Uuids.toBytes(tenantId), Uuids.toBytes(permissionId))
                .stream().findFirst();
    }

    public List<PermissionRow> findPermissions(UUID tenantId, String sortColumn,
                                               boolean ascending, String lastName, UUID lastId,
                                               int pageSize) {
        StringBuilder sql = new StringBuilder("""
                SELECT permission_id, permission_code, permission_name, description
                FROM permission WHERE tenant_id = ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(Uuids.toBytes(tenantId));
        if (lastName != null && lastId != null) {
            String cmp = ascending ? ">" : "<";
            String cursorColumn = permissionSortColumn(sortColumn);
            sql.append(" AND (").append(cursorColumn).append(" ").append(cmp).append(" ? OR (")
                    .append(cursorColumn).append(" = ? AND permission_id ").append(cmp).append(" ?))");
            args.add(lastName);
            args.add(lastName);
            args.add(Uuids.toBytes(lastId));
        }
        sql.append(" ORDER BY ").append(permissionSortColumn(sortColumn))
                .append(ascending ? " ASC" : " DESC")
                .append(", permission_id ").append(ascending ? "ASC" : "DESC")
                .append(" LIMIT ").append(pageSize + 1);
        return jdbc.query(sql.toString(), PERMISSION_MAPPER, args.toArray());
    }

    public Optional<BindingRow> findRolePermission(UUID tenantId, UUID roleId, UUID permissionId) {
        return jdbc.query("""
                SELECT role_id, permission_id, state, granted_at, revoked_at, lock_version
                FROM role_permission
                WHERE tenant_id = ? AND role_id = ? AND permission_id = ?
                """, ROLE_PERMISSION_MAPPER, Uuids.toBytes(tenantId),
                Uuids.toBytes(roleId), Uuids.toBytes(permissionId)).stream().findFirst();
    }

    /** Current read used by idempotent replay paths (sees rows committed by a competing winner). */
    public Optional<BindingRow> findRolePermissionForUpdate(UUID tenantId, UUID roleId, UUID permissionId) {
        return jdbc.query("""
                SELECT role_id, permission_id, state, granted_at, revoked_at, lock_version
                FROM role_permission
                WHERE tenant_id = ? AND role_id = ? AND permission_id = ?
                FOR UPDATE
                """, ROLE_PERMISSION_MAPPER, Uuids.toBytes(tenantId),
                Uuids.toBytes(roleId), Uuids.toBytes(permissionId)).stream().findFirst();
    }

    public Optional<BindingRow> findUserRole(UUID tenantId, UUID userId, UUID roleId) {
        return jdbc.query("""
                SELECT user_id, role_id, state, granted_at, revoked_at, lock_version
                FROM user_role_assignment
                WHERE tenant_id = ? AND user_id = ? AND role_id = ?
                """, USER_ROLE_MAPPER, Uuids.toBytes(tenantId),
                Uuids.toBytes(userId), Uuids.toBytes(roleId)).stream().findFirst();
    }

    /** Current read used by idempotent replay paths (sees rows committed by a competing winner). */
    public Optional<BindingRow> findUserRoleForUpdate(UUID tenantId, UUID userId, UUID roleId) {
        return jdbc.query("""
                SELECT user_id, role_id, state, granted_at, revoked_at, lock_version
                FROM user_role_assignment
                WHERE tenant_id = ? AND user_id = ? AND role_id = ?
                FOR UPDATE
                """, USER_ROLE_MAPPER, Uuids.toBytes(tenantId),
                Uuids.toBytes(userId), Uuids.toBytes(roleId)).stream().findFirst();
    }

    /**
     * Inserts a new role-permission binding. New bindings are always created in the
     * GRANTED state; re-granting an existing REVOKED row is performed by
     * {@link #updateRolePermissionState} so the audit trail is preserved.
     */
    public void insertRolePermission(UUID tenantId, UUID roleId, UUID permissionId,
                                     Instant now, UUID actorId) {
        jdbc.update("""
                INSERT INTO role_permission
                  (tenant_id, role_id, permission_id, state, granted_at, granted_by_type,
                   granted_by_id, revoked_at, revoked_by_type, revoked_by_id, lock_version)
                VALUES (?, ?, ?, 'GRANTED', ?, 'USER', ?, NULL, NULL, NULL, 0)
                """, Uuids.toBytes(tenantId), Uuids.toBytes(roleId), Uuids.toBytes(permissionId),
                Timestamp.from(now), Uuids.toBytes(actorId));
    }

    public void insertUserRole(UUID tenantId, UUID userId, UUID roleId, Instant now, UUID actorId) {
        jdbc.update("""
                INSERT INTO user_role_assignment
                  (tenant_id, user_id, role_id, state, granted_at, granted_by_type,
                   granted_by_id, revoked_at, revoked_by_type, revoked_by_id, lock_version)
                VALUES (?, ?, ?, 'GRANTED', ?, 'USER', ?, NULL, NULL, NULL, 0)
                """, Uuids.toBytes(tenantId), Uuids.toBytes(userId), Uuids.toBytes(roleId),
                Timestamp.from(now), Uuids.toBytes(actorId));
    }

    public boolean updateRolePermissionState(UUID tenantId, UUID roleId, UUID permissionId,
                                             long expectedLockVersion, String state, Instant now,
                                             UUID actorId) {
        if ("GRANTED".equals(state)) {
            return jdbc.update("""
                    UPDATE role_permission
                    SET state = 'GRANTED', granted_at = ?, granted_by_type = 'USER', granted_by_id = ?,
                        revoked_at = NULL, revoked_by_type = NULL, revoked_by_id = NULL,
                        lock_version = lock_version + 1
                    WHERE tenant_id = ? AND role_id = ? AND permission_id = ? AND lock_version = ?
                    """, Timestamp.from(now), Uuids.toBytes(actorId), Uuids.toBytes(tenantId),
                    Uuids.toBytes(roleId), Uuids.toBytes(permissionId), expectedLockVersion) == 1;
        }
        return jdbc.update("""
                UPDATE role_permission
                SET state = 'REVOKED', revoked_at = ?, revoked_by_type = 'USER', revoked_by_id = ?,
                    lock_version = lock_version + 1
                WHERE tenant_id = ? AND role_id = ? AND permission_id = ? AND lock_version = ?
                """, Timestamp.from(now), Uuids.toBytes(actorId), Uuids.toBytes(tenantId),
                Uuids.toBytes(roleId), Uuids.toBytes(permissionId), expectedLockVersion) == 1;
    }

    public boolean updateUserRoleState(UUID tenantId, UUID userId, UUID roleId,
                                       long expectedLockVersion, String state, Instant now,
                                       UUID actorId) {
        if ("GRANTED".equals(state)) {
            return jdbc.update("""
                    UPDATE user_role_assignment
                    SET state = 'GRANTED', granted_at = ?, granted_by_type = 'USER', granted_by_id = ?,
                        revoked_at = NULL, revoked_by_type = NULL, revoked_by_id = NULL,
                        lock_version = lock_version + 1
                    WHERE tenant_id = ? AND user_id = ? AND role_id = ? AND lock_version = ?
                    """, Timestamp.from(now), Uuids.toBytes(actorId), Uuids.toBytes(tenantId),
                    Uuids.toBytes(userId), Uuids.toBytes(roleId), expectedLockVersion) == 1;
        }
        return jdbc.update("""
                UPDATE user_role_assignment
                SET state = 'REVOKED', revoked_at = ?, revoked_by_type = 'USER', revoked_by_id = ?,
                    lock_version = lock_version + 1
                WHERE tenant_id = ? AND user_id = ? AND role_id = ? AND lock_version = ?
                """, Timestamp.from(now), Uuids.toBytes(actorId), Uuids.toBytes(tenantId),
                Uuids.toBytes(userId), Uuids.toBytes(roleId), expectedLockVersion) == 1;
    }

    public List<BindingRow> findRolePermissions(UUID tenantId, UUID roleId, String state) {
        StringBuilder sql = new StringBuilder("""
                SELECT role_id, permission_id, state, granted_at, revoked_at, lock_version
                FROM role_permission WHERE tenant_id = ? AND role_id = ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(Uuids.toBytes(tenantId));
        args.add(Uuids.toBytes(roleId));
        if (state != null) {
            sql.append(" AND state = ?");
            args.add(state);
        }
        sql.append(" ORDER BY permission_id");
        return jdbc.query(sql.toString(), ROLE_PERMISSION_MAPPER, args.toArray());
    }

    public List<BindingRow> findUserRoles(UUID tenantId, UUID userId, String state) {
        StringBuilder sql = new StringBuilder("""
                SELECT user_id, role_id, state, granted_at, revoked_at, lock_version
                FROM user_role_assignment WHERE tenant_id = ? AND user_id = ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(Uuids.toBytes(tenantId));
        args.add(Uuids.toBytes(userId));
        if (state != null) {
            sql.append(" AND state = ?");
            args.add(state);
        }
        sql.append(" ORDER BY role_id");
        return jdbc.query(sql.toString(), USER_ROLE_MAPPER, args.toArray());
    }

    // ----- eligibility ------------------------------------------------------------

    public Optional<String> findUserStatus(UUID tenantId, UUID userId) {
        return jdbc.query("SELECT status FROM user_account WHERE tenant_id = ? AND user_id = ?",
                (rs, i) -> rs.getString(1), Uuids.toBytes(tenantId), Uuids.toBytes(userId))
                .stream().findFirst();
    }

    public Optional<String> findGrantedRoleStatus(UUID tenantId, UUID userId, String roleCode) {
        return jdbc.query("""
                SELECT r.status
                FROM user_role_assignment ura
                JOIN role r ON r.tenant_id = ura.tenant_id AND r.role_id = ura.role_id
                WHERE ura.tenant_id = ? AND ura.user_id = ? AND ura.state = 'GRANTED'
                  AND r.role_code = ?
                """, (rs, i) -> rs.getString(1), Uuids.toBytes(tenantId), Uuids.toBytes(userId), roleCode)
                .stream().findFirst();
    }

    /**
     * One statement for every fact that determines an API-016 response. The
     * component-version sum advances whenever the subject, required role or
     * its assignment changes; MAX(lock_version) would miss a later mutation
     * on a component whose version remained numerically lower than another.
     */
    public Optional<EligibilityFacts> findEligibilityFacts(UUID tenantId, UUID userId,
                                                            String roleCode) {
        return jdbc.query("""
                SELECT u.status AS user_status,
                       r.status AS role_status,
                       ura.state AS assignment_state,
                       1 + COALESCE(u.lock_version,0)
                         + CASE WHEN r.role_id IS NULL THEN 0 ELSE 1 END
                         + COALESCE(r.lock_version,0)
                         + CASE WHEN ura.role_id IS NULL THEN 0 ELSE 1 END
                         + COALESCE(ura.lock_version,0) AS qualification_version
                FROM user_account u
                LEFT JOIN role r
                  ON r.tenant_id=u.tenant_id AND r.role_code=?
                LEFT JOIN user_role_assignment ura
                  ON ura.tenant_id=u.tenant_id AND ura.user_id=u.user_id
                 AND ura.role_id=r.role_id
                WHERE u.tenant_id=? AND u.user_id=?
                """, (rs, i) -> new EligibilityFacts(rs.getString("user_status"),
                rs.getString("role_status"), rs.getString("assignment_state"),
                Math.max(1L, rs.getLong("qualification_version"))),
                roleCode, Uuids.toBytes(tenantId), Uuids.toBytes(userId)).stream().findFirst();
    }

    public boolean hasGrantedRoleCode(UUID tenantId, UUID userId, String roleCode) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM user_role_assignment ura
                JOIN role r ON r.tenant_id = ura.tenant_id AND r.role_id = ura.role_id
                WHERE ura.tenant_id = ? AND ura.user_id = ? AND ura.state = 'GRANTED'
                  AND r.status = 'ACTIVE' AND r.role_code = ?
                """, Integer.class, Uuids.toBytes(tenantId), Uuids.toBytes(userId), roleCode);
        return count != null && count > 0;
    }

    public record EligibilityFacts(String userStatus, String roleStatus,
                                   String assignmentState, long qualificationVersion) {
    }

    // ----- audit -------------------------------------------------------------------

    @Transactional
    public void insertAudit(UUID tenantId, UUID auditId, String actionCode, String targetType,
                            UUID targetId, String outcome, String actorType, UUID actorId,
                            String reason, String errorCode, UUID correlationId, Instant occurredAt) {
        jdbc.update("""
                INSERT INTO audit_record
                  (tenant_id, audit_id, action_code, target_type, target_id, outcome,
                   actor_type, actor_id, reason, error_code, correlation_id, causation_id,
                   occurred_at, recorded_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, CURRENT_TIMESTAMP(6))
                """, Uuids.toBytes(tenantId), Uuids.toBytes(auditId), actionCode, targetType,
                Uuids.toBytes(targetId), outcome, actorType, Uuids.toBytes(actorId), reason,
                errorCode, Uuids.toBytes(correlationId), Timestamp.from(occurredAt));
    }

    public Optional<String> findAuditReason(UUID tenantId, UUID auditId) {
        return jdbc.query("SELECT reason FROM audit_record WHERE tenant_id = ? AND audit_id = ?",
                (rs, i) -> rs.getString(1), Uuids.toBytes(tenantId), Uuids.toBytes(auditId))
                .stream().findFirst();
    }

    /** Current append-only read after a competing duplicate insert has completed. */
    public Optional<String> findAuditReasonAfterDuplicate(UUID tenantId, UUID auditId) {
        return jdbc.query("""
                SELECT reason FROM audit_record
                WHERE tenant_id = ? AND audit_id = ?
                """, (rs, i) -> rs.getString(1), Uuids.toBytes(tenantId), Uuids.toBytes(auditId))
                .stream().findFirst();
    }

    // ----- rows ---------------------------------------------------------------------

    public record CredentialRow(UUID tenantId, UUID userId, String username, String userStatus,
                                String tenantCode, String tenantDisplayName, byte[] passwordHash,
                                int failedAttemptCount, Instant lockedUntil) {
    }

    public record CurrentUserRow(UUID userId, String username, String displayName, String status,
                                 String tenantCode, String tenantDisplayName) {
    }

    public record UserRow(UUID userId, String username, String displayName, String status,
                          long lockVersion, Instant createdAt, Instant updatedAt) {
    }

    public record RoleRow(UUID roleId, String roleCode, String roleName, String status,
                          long lockVersion, Instant createdAt, Instant updatedAt) {
    }

    public record PermissionRow(UUID permissionId, String permissionCode, String permissionName,
                                String description) {
    }

    public record BindingRow(UUID subjectId, UUID targetId, String state, Instant grantedAt,
                             Instant revokedAt, long lockVersion) {
    }

    private static final RowMapper<CredentialRow> CREDENTIAL_MAPPER = (rs, i) -> new CredentialRow(
            Uuids.toUuid(rs.getBytes("tenant_id")),
            Uuids.toUuid(rs.getBytes("user_id")),
            rs.getString("username"),
            rs.getString("status"),
            rs.getString("tenant_code"),
            rs.getString("display_name"),
            rs.getBytes("password_hash"),
            rs.getInt("failed_attempt_count"),
            nullableInstant(rs.getTimestamp("locked_until")));

    private static final RowMapper<UserRow> USER_MAPPER = (rs, i) -> new UserRow(
            Uuids.toUuid(rs.getBytes("user_id")),
            rs.getString("username"),
            rs.getString("display_name"),
            rs.getString("status"),
            rs.getLong("lock_version"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant());

    private static final RowMapper<RoleRow> ROLE_MAPPER = (rs, i) -> new RoleRow(
            Uuids.toUuid(rs.getBytes("role_id")),
            rs.getString("role_code"),
            rs.getString("role_name"),
            rs.getString("status"),
            rs.getLong("lock_version"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant());

    private static final RowMapper<PermissionRow> PERMISSION_MAPPER = (rs, i) -> new PermissionRow(
            Uuids.toUuid(rs.getBytes("permission_id")),
            rs.getString("permission_code"),
            rs.getString("permission_name"),
            rs.getString("description"));

    private static final RowMapper<BindingRow> ROLE_PERMISSION_MAPPER = (rs, i) -> new BindingRow(
            Uuids.toUuid(rs.getBytes("role_id")),
            Uuids.toUuid(rs.getBytes("permission_id")),
            rs.getString("state"),
            nullableInstant(rs.getTimestamp("granted_at")),
            nullableInstant(rs.getTimestamp("revoked_at")),
            rs.getLong("lock_version"));

    private static final RowMapper<BindingRow> USER_ROLE_MAPPER = (rs, i) -> new BindingRow(
            Uuids.toUuid(rs.getBytes("user_id")),
            Uuids.toUuid(rs.getBytes("role_id")),
            rs.getString("state"),
            nullableInstant(rs.getTimestamp("granted_at")),
            nullableInstant(rs.getTimestamp("revoked_at")),
            rs.getLong("lock_version"));

    private static String userSortColumn(String sortColumn) {
        return switch (sortColumn) {
            case "username" -> "u.username";
            case "displayName" -> "u.display_name";
            default -> throw new IllegalArgumentException("Unsupported user sort column: " + sortColumn);
        };
    }

    private static String roleSortColumn(String sortColumn) {
        return switch (sortColumn) {
            case "roleCode" -> "role_code";
            case "roleName" -> "role_name";
            default -> throw new IllegalArgumentException("Unsupported role sort column: " + sortColumn);
        };
    }

    private static String permissionSortColumn(String sortColumn) {
        return switch (sortColumn) {
            case "permissionCode" -> "permission_code";
            case "permissionName" -> "permission_name";
            default -> throw new IllegalArgumentException("Unsupported permission sort column: " + sortColumn);
        };
    }

    private static Instant nullableInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    public static final class Uuids {
        private Uuids() {
        }

        public static byte[] toBytes(UUID uuid) {
            if (uuid == null) {
                return null;
            }
            long most = uuid.getMostSignificantBits();
            long least = uuid.getLeastSignificantBits();
            return new byte[]{
                    (byte) (most >>> 56), (byte) (most >>> 48), (byte) (most >>> 40), (byte) (most >>> 32),
                    (byte) (most >>> 24), (byte) (most >>> 16), (byte) (most >>> 8), (byte) most,
                    (byte) (least >>> 56), (byte) (least >>> 48), (byte) (least >>> 40), (byte) (least >>> 32),
                    (byte) (least >>> 24), (byte) (least >>> 16), (byte) (least >>> 8), (byte) least};
        }

        public static UUID toUuid(byte[] bytes) {
            if (bytes == null) {
                return null;
            }
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
            return new UUID(buffer.getLong(), buffer.getLong());
        }
    }
}
