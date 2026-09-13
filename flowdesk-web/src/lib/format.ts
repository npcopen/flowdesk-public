import type { TicketStatus, RoleCode, PermissionCode } from "@/types/api";

export const statusLabels: Record<string, string> = {
  AWAITING_APPROVAL: "等待审批",
  APPROVAL_REJECTED: "审批未通过",
  PENDING_ASSIGNMENT: "待分配",
  ASSIGNED: "已分配",
  IN_PROGRESS: "处理中",
  CLOSED: "已关闭",
  REQUESTED: "正在处理",
  ESTABLISHED: "已创建",
  RECOVERY_REQUIRED: "等待恢复",
  RUNNING: "处理中",
  COMPLETED_PASSED: "已完成",
  COMPLETED_REJECTED: "未通过",
  PENDING: "待处理",
  APPROVED: "已同意",
  REJECTED: "已拒绝",
  ACTIVE: "进行中",
  RECOVERED: "已恢复",
  FIRING: "已触发",
  RESOLVED: "已解决",
  UNREAD: "未读",
  READ: "已读",
  OPEN: "待处理",
  REPLAYING: "处理中",
  RESOLVED_RECOVERY: "已恢复",
  SATISFIED_ON_TIME: "按时完成",
  NOT_REQUIRED: "已恢复，无需建单",
  SATISFIED_LATE: "延迟完成",
  BREACHED: "已超时",
  VALID: "已校验",
  UNVALIDATED: "待校验",
  EDITABLE: "可编辑",
  CONSUMED: "已发布",
  APPLIED: "已处理",
  LATE_OLD_GENERATION: "已忽略",
  REVIEW_REQUIRED: "待复核",
  FAILED: "处理失败",
  SLA_NOT_STARTED: "尚未开始计时",
  ACTIVE_STATUS: "启用",
  INACTIVE: "已停用",
  GRANTED: "已启用",
  REVOKED: "已停用",
};

const originLabels: Record<string, string> = {
  MANUAL: "人工提交",
  INCIDENT: "系统告警",
};

const asyncEffectLabels: Record<string, string> = {
  WORKFLOW_START: "审批处理",
  INCIDENT_TICKET_LINK: "关联工单",
  NOTIFICATION_DELIVERY: "通知发送",
  SLA_SCAN: "处理时效检查",
};

const eventTypeLabels: Record<string, string> = {
  FIRING: "告警触发",
  RESOLVED: "告警恢复",
};

const dispositionLabels: Record<string, string> = {
  APPLIED: "已处理",
  LATE_OLD_GENERATION: "已忽略",
  REVIEW_REQUIRED: "待复核",
};

const sourceDomainLabels: Record<string, string> = {
  TICKET: "工单",
  WORKFLOW: "审批",
  ALERT: "告警",
  SYSTEM: "系统",
};

const notificationKindLabels: Record<string, string> = {
  WORKFLOW_UPDATE: "审批进度",
  TICKET_UPDATE: "工单进展",
  ALERT_UPDATE: "告警更新",
  TICKET_ASSIGNED: "工单已分派",
  TICKET_RESOLVED: "工单已解决",
  TICKET_REOPENED: "工单已重开",
  WORKFLOW_APPROVAL_REQUESTED: "待处理审批",
  APPROVAL_TASK_CREATED: "待处理审批",
  APPROVAL_TASK_REASSIGNED: "审批交接",
  WORKFLOW_APPROVAL_FINALIZED: "审批结果",
  SLA_REMINDER: "时效提醒",
  SLA_BREACH: "时效超时",
  SYSTEM: "系统消息",
};

const recoveryOwnerLabels: Record<string, string> = {
  TICKET: "工单更新",
  WORKFLOW: "审批更新",
  ALERT: "告警更新",
  NOTIFICATION: "通知更新",
};

const recoveryDirectionLabels: Record<string, string> = {
  OUTBOX: "发出处理",
  INBOX: "接收处理",
};

const timelineLabels: Record<string, string> = {
  CREATED: "已创建",
  APPROVAL_REQUESTED: "已提交审批",
  ASSIGNED: "已分配处理人",
  ACCEPTED: "已接单",
  TRANSFERRED: "已转派",
  COMMENT_ADDED: "已添加评论",
  RESOLVED: "已解决",
  CLOSED: "已关闭",
  REOPENED: "已重新打开",
  APPROVAL_PASSED: "审批已通过",
  APPROVAL_REJECTED: "审批未通过",
  INCIDENT_CREATED: "告警已建立工单",
  INCIDENT_RECOVERED: "关联告警已恢复",
};

export const roleLabels: Record<RoleCode, string> = {
  REQUESTER: "申请人",
  AGENT: "处理人",
  APPROVER: "审批人",
  SERVICE_DESK_ADMIN: "服务台管理员",
  TENANT_ADMIN: "组织管理员",
};

export const permissionLabels: Record<PermissionCode, string> = {
  CATALOG_READ_ACTIVE: "查看可用服务",
  TICKET_CREATE: "创建工单",
  APPROVAL_REASSIGN_TENANT: "交接审批人员",
  TICKET_READ_OWN: "查看我的工单",
  TICKET_COMMENT_OWN: "评论我的工单",
  TICKET_CLOSE_OWN: "关闭我的工单",
  TICKET_REOPEN_OWN: "重新打开我的工单",
  TICKET_READ_ASSIGNED: "查看分配给我的工单",
  TICKET_ACCEPT_ASSIGNED: "接受工单",
  TICKET_TRANSFER_ASSIGNED: "转派负责工单",
  TICKET_COMMENT_ASSIGNED: "评论负责工单",
  TICKET_RESOLVE_ASSIGNED: "解决负责工单",
  INCIDENT_READ_TENANT: "查看组织告警",
  APPROVAL_READ_ASSIGNED: "查看待我审批",
  TICKET_ASSIGN_TENANT: "分配工单",
  APPROVAL_DECIDE_ASSIGNED: "处理审批决定",
  IAM_ELIGIBILITY_CANDIDATE_READ: "查看可选处理人",
  CATALOG_READ_TENANT: "查看全部服务项目",
  CATALOG_UPDATE_MINIMAL: "编辑服务项目",
  TICKET_READ_TENANT: "查看全部工单",
  TICKET_TRANSFER_TENANT: "转派工单",
  WORKFLOW_READ_TENANT: "查看审批规则",
  WORKFLOW_DRAFT_MANAGE: "编辑审批规则",
  WORKFLOW_PUBLISH: "发布审批规则",
  WORKFLOW_RUNTIME_READ_TENANT: "查看审批进度",
  ALERT_EVENT_READ_TENANT: "查看告警记录",
  RECOVERY_READ_TENANT: "查看待处理更新",
  RECOVERY_REPLAY_TENANT: "重新处理失败更新",
  IAM_USER_READ: "查看成员",
  IAM_USER_MANAGE: "管理用户",
  IAM_ROLE_READ: "查看角色",
  IAM_ROLE_MANAGE: "管理角色",
  IAM_PERMISSION_READ: "查看操作权限",
  IAM_BINDING_READ: "查看成员授权",
  IAM_BINDING_MANAGE: "管理成员授权",
  NOTIFICATION_READ_SELF: "查看我的通知",
  NOTIFICATION_WRITE_SELF: "更新我的通知",
};

export function label(value: string | null | undefined): string {
  if (!value) return "未提供";
  return statusLabels[value] ?? "状态待确认";
}

export function ticketStatusLabel(value: TicketStatus): string {
  return label(value);
}

export function formatDate(value: string | null | undefined): string {
  if (!value) return "未发生";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "时间待确认";
  return new Intl.DateTimeFormat("zh-CN", {
    dateStyle: "medium",
    timeStyle: "short",
  }).format(date);
}

export function shortId(value: string | null | undefined): string {
  if (!value) return "—";
  return value.length > 12 ? `${value.slice(0, 8)}…${value.slice(-4)}` : value;
}

export function initials(value: string | null | undefined): string {
  const source = (value || "?").trim();
  return source.slice(0, 1).toUpperCase();
}

export function originLabel(value: string | null | undefined): string {
  if (!value) return "未说明来源";
  return originLabels[value] ?? "其他来源";
}

export function asyncEffectLabel(value: string | null | undefined): string {
  if (!value) return "后续更新";
  return asyncEffectLabels[value] ?? "后续更新";
}

export function eventTypeLabel(value: string | null | undefined): string {
  if (!value) return "告警事件";
  return eventTypeLabels[value] ?? "告警事件";
}

export function dispositionLabel(value: string | null | undefined): string {
  if (!value) return "待处理";
  return dispositionLabels[value] ?? "待处理";
}

export function sourceDomainLabel(value: string | null | undefined): string {
  if (!value) return "系统";
  return sourceDomainLabels[value] ?? "系统";
}

export function notificationKindLabel(
  value: string | null | undefined,
): string {
  if (!value) return "消息";
  return notificationKindLabels[value] ?? "消息";
}

export function recoveryOwnerLabel(value: string | null | undefined): string {
  if (!value) return "相关服务";
  return recoveryOwnerLabels[value] ?? "相关服务";
}

export function recoveryDirectionLabel(
  value: string | null | undefined,
): string {
  if (!value) return "后续处理";
  return recoveryDirectionLabels[value] ?? "后续处理";
}

export function timelineLabel(value: string | null | undefined): string {
  if (!value) return "状态更新";
  return timelineLabels[value] ?? "状态更新";
}

export function permissionDisplayLabel(
  code: string | null | undefined,
  name?: string | null,
): string {
  if (!code) return name || "权限项";
  return permissionLabels[code as PermissionCode] || "权限项";
}
