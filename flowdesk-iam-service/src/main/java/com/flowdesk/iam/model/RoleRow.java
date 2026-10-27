package com.flowdesk.iam.model;

import java.util.UUID;
import java.time.Instant;

/**
 * 租户角色的不可变数据库投影，包含固定角色编码、显示名称、启停状态和并发更新版本。
 */
public record RoleRow(UUID roleId, String roleCode, String roleName, String status, long lockVersion, Instant createdAt, Instant updatedAt) {
}
