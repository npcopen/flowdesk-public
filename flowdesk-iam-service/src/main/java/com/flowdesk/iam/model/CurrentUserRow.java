package com.flowdesk.iam.model;

import java.util.UUID;

/**
 * 当前用户响应所需的账户与租户基础资料，不包含密码及授权关系；角色和权限另行读取。
 */
public record CurrentUserRow(UUID userId, String username, String displayName, String status, String tenantCode, String tenantDisplayName) {
}
