package com.flowdesk.common.authz;

/**
 * 系统支持的五种固定业务角色；角色决定可授予权限的上限。
 */
public enum RoleCode {
    REQUESTER,
    AGENT,
    APPROVER,
    SERVICE_DESK_ADMIN,
    TENANT_ADMIN
}
