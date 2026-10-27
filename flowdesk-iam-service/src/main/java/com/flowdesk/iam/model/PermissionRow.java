package com.flowdesk.iam.model;

import java.util.UUID;

/**
 * 租户权限目录的查询投影，携带权限标识、编码、名称和说明；权限上限由对应领域枚举提供。
 */
public record PermissionRow(UUID permissionId, String permissionCode, String permissionName, String description) {
}
