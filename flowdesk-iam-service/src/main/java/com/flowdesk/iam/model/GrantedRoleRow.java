package com.flowdesk.iam.model;

import java.util.UUID;

/**
 * 批量角色查询的一条用户与有效角色编码投影，供服务按用户分组，避免逐用户重复查询。
 */
public record GrantedRoleRow(UUID userId, String roleCode) {
}
