package com.flowdesk.common.authz;

/**
 * The five frozen P0 persona roles (API-SPEC §3.2).
 */
public enum RoleCode {
    REQUESTER,
    AGENT,
    APPROVER,
    SERVICE_DESK_ADMIN,
    TENANT_ADMIN
}
