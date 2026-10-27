package com.flowdesk.common.authz;

import java.util.EnumSet;
import java.util.Set;

/**
 * 固定权限代码白名单；权限只能在角色允许的范围内生效，不能作为任意扩展的授权语言。
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

    /**
     * 将允许角色复制为不可变集合，形成该权限的固定角色上限。
     */
    PermissionCode(RoleCode... allowedRoles) {
        this.ceiling = Set.copyOf(EnumSet.copyOf(java.util.Arrays.asList(allowedRoles)));
    }

    /**
     * 返回允许授予此权限的固定角色集合，供令牌签发和资源授权共同限制权限上限。
     */
    public Set<RoleCode> ceiling() {
        return ceiling;
    }

    /**
     * 判断角色是否在此权限的白名单内，令牌中的权限声明不能绕过此限制。
     */
    public boolean isAllowedFor(RoleCode roleCode) {
        return ceiling.contains(roleCode);
    }
}
