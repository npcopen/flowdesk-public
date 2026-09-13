package com.flowdesk.common.authz;

import java.util.EnumSet;
import java.util.Set;

/**
 * The complete frozen P0 permission white list (API-SPEC §3.2, 36 codes).
 *
 * <p>Permission entries only gate actions inside the fixed persona role ceiling;
 * they are not a dynamic authorization language.</p>
 */
public enum PermissionCode {
    CATALOG_READ_ACTIVE(RoleCode.REQUESTER),
    TICKET_CREATE(RoleCode.REQUESTER),
    TICKET_READ_OWN(RoleCode.REQUESTER),
    TICKET_COMMENT_OWN(RoleCode.REQUESTER),
    TICKET_CLOSE_OWN(RoleCode.REQUESTER),
    TICKET_REOPEN_OWN(RoleCode.REQUESTER),
    TICKET_READ_ASSIGNED(RoleCode.AGENT),
    TICKET_ACCEPT_ASSIGNED(RoleCode.AGENT),
    TICKET_TRANSFER_ASSIGNED(RoleCode.AGENT),
    TICKET_COMMENT_ASSIGNED(RoleCode.AGENT),
    TICKET_RESOLVE_ASSIGNED(RoleCode.AGENT),
    INCIDENT_READ_TENANT(RoleCode.AGENT, RoleCode.SERVICE_DESK_ADMIN),
    APPROVAL_READ_ASSIGNED(RoleCode.APPROVER),
    APPROVAL_DECIDE_ASSIGNED(RoleCode.APPROVER),
    IAM_ELIGIBILITY_CANDIDATE_READ(RoleCode.SERVICE_DESK_ADMIN),
    CATALOG_READ_TENANT(RoleCode.SERVICE_DESK_ADMIN),
    CATALOG_UPDATE_MINIMAL(RoleCode.SERVICE_DESK_ADMIN),
    TICKET_READ_TENANT(RoleCode.SERVICE_DESK_ADMIN),
    TICKET_ASSIGN_TENANT(RoleCode.SERVICE_DESK_ADMIN),
    TICKET_TRANSFER_TENANT(RoleCode.SERVICE_DESK_ADMIN),
    WORKFLOW_READ_TENANT(RoleCode.SERVICE_DESK_ADMIN),
    WORKFLOW_DRAFT_MANAGE(RoleCode.SERVICE_DESK_ADMIN),
    WORKFLOW_PUBLISH(RoleCode.SERVICE_DESK_ADMIN),
    WORKFLOW_RUNTIME_READ_TENANT(RoleCode.SERVICE_DESK_ADMIN),
    APPROVAL_REASSIGN_TENANT(RoleCode.SERVICE_DESK_ADMIN),
    ALERT_EVENT_READ_TENANT(RoleCode.SERVICE_DESK_ADMIN),
    RECOVERY_READ_TENANT(RoleCode.SERVICE_DESK_ADMIN),
    RECOVERY_REPLAY_TENANT(RoleCode.SERVICE_DESK_ADMIN),
    IAM_USER_READ(RoleCode.TENANT_ADMIN),
    IAM_USER_MANAGE(RoleCode.TENANT_ADMIN),
    IAM_ROLE_READ(RoleCode.TENANT_ADMIN),
    IAM_ROLE_MANAGE(RoleCode.TENANT_ADMIN),
    IAM_PERMISSION_READ(RoleCode.TENANT_ADMIN),
    IAM_BINDING_READ(RoleCode.TENANT_ADMIN),
    IAM_BINDING_MANAGE(RoleCode.TENANT_ADMIN),
    NOTIFICATION_READ_SELF(RoleCode.REQUESTER, RoleCode.AGENT, RoleCode.APPROVER,
            RoleCode.SERVICE_DESK_ADMIN, RoleCode.TENANT_ADMIN),
    NOTIFICATION_WRITE_SELF(RoleCode.REQUESTER, RoleCode.AGENT, RoleCode.APPROVER,
            RoleCode.SERVICE_DESK_ADMIN, RoleCode.TENANT_ADMIN);

    private final Set<RoleCode> ceiling;

    PermissionCode(RoleCode... allowedRoles) {
        this.ceiling = Set.copyOf(EnumSet.copyOf(java.util.Arrays.asList(allowedRoles)));
    }

    public Set<RoleCode> ceiling() {
        return ceiling;
    }

    public boolean isAllowedFor(RoleCode roleCode) {
        return ceiling.contains(roleCode);
    }
}
