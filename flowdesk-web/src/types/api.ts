/** 前后端 API 契约的 TypeScript 类型：统一响应、业务实体、分页和异步状态。 */
export type RoleCode =
  | 'REQUESTER'
  | 'AGENT'
  | 'APPROVER'
  | 'SERVICE_DESK_ADMIN'
  | 'TENANT_ADMIN'

export type PermissionCode =
  | 'CATALOG_READ_ACTIVE'
  | 'TICKET_CREATE'
  | 'TICKET_READ_OWN'
  | 'TICKET_COMMENT_OWN'
  | 'TICKET_CLOSE_OWN'
  | 'TICKET_REOPEN_OWN'
  | 'TICKET_READ_ASSIGNED'
  | 'TICKET_ACCEPT_ASSIGNED'
  | 'TICKET_TRANSFER_ASSIGNED'
  | 'TICKET_COMMENT_ASSIGNED'
  | 'TICKET_RESOLVE_ASSIGNED'
  | 'INCIDENT_READ_TENANT'
  | 'APPROVAL_READ_ASSIGNED'
  | 'APPROVAL_DECIDE_ASSIGNED'
  | 'IAM_ELIGIBILITY_CANDIDATE_READ'
  | 'CATALOG_READ_TENANT'
  | 'CATALOG_UPDATE_MINIMAL'
  | 'TICKET_READ_TENANT'
  | 'TICKET_ASSIGN_TENANT'
  | 'TICKET_TRANSFER_TENANT'
  | 'WORKFLOW_READ_TENANT'
  | 'WORKFLOW_DRAFT_MANAGE'
  | 'WORKFLOW_PUBLISH'
  | 'WORKFLOW_RUNTIME_READ_TENANT'
  | 'APPROVAL_REASSIGN_TENANT'
  | 'ALERT_EVENT_READ_TENANT'
  | 'RECOVERY_READ_TENANT'
  | 'RECOVERY_REPLAY_TENANT'
  | 'IAM_USER_READ'
  | 'IAM_USER_MANAGE'
  | 'IAM_ROLE_READ'
  | 'IAM_ROLE_MANAGE'
  | 'IAM_PERMISSION_READ'
  | 'IAM_BINDING_READ'
  | 'IAM_BINDING_MANAGE'
  | 'NOTIFICATION_READ_SELF'
  | 'NOTIFICATION_WRITE_SELF'

export type KnownStatus = string

export interface PageMeta {
  pageSize: number
  nextPageToken: string | null
  sort: string
}

export interface AsyncProgress {
  kind: string
  state: 'REQUESTED' | 'ESTABLISHED' | 'RECOVERY_REQUIRED' | 'PENDING' | string
  relatedRefId: string | null
}

export interface ApiMeta {
  correlationId: string
  page?: PageMeta
  asyncEffects?: AsyncProgress[]
}

export interface SuccessEnvelope<T> {
  data: T
  meta: ApiMeta
}

export interface FieldViolation {
  field: string
  reason: string
  message: string
}

export interface ApiErrorBody {
  code: string
  message?: string
  correlationId?: string
  retryable?: boolean
  details?: FieldViolation[]
}

export interface ErrorEnvelope {
  error: ApiErrorBody
}

export interface TenantInfo {
  tenantCode: string
  displayName: string
}

export interface CurrentUser {
  userId: string
  username: string
  displayName: string
  status: 'ACTIVE' | 'INACTIVE' | string
  tenant: TenantInfo
  roles: RoleCode[]
  permissions: PermissionCode[]
}

export interface AuthSession {
  accessToken: string
  tokenType: 'Bearer' | string
  expiresAt: string
  user: CurrentUser
}

export interface UserIdentity {
  userId: string
  username: string
  displayName: string
}

export interface UserSummary {
  userId: string
  username: string
  displayName: string
  status: 'ACTIVE' | 'INACTIVE' | string
  roleCodes: RoleCode[]
  lockVersion: number
  createdAt: string
  updatedAt: string
}

export interface Role {
  roleId: string
  roleCode: RoleCode
  roleName: string
  status: 'ACTIVE' | 'INACTIVE' | string
  lockVersion: number
  createdAt: string
  updatedAt: string
}

export interface Permission {
  allowedRoles?: RoleCode[]
  permissionId: string
  permissionCode: PermissionCode
  permissionName: string
  description: string | null
}

export interface AccessBinding {
  subjectId: string
  targetId: string
  state: 'GRANTED' | 'REVOKED' | string
  grantedAt: string
  revokedAt: string | null
  lockVersion: number
}

export interface ServiceCatalogItem {
  catalogItemId: string
  itemCode: string
  itemName: string
  description: string | null
  riskLevel: 'NORMAL' | 'HIGH' | string
  requiresApproval: boolean
  workflowRef: WorkflowRef | null
  slaPolicyId: string
  status: 'ACTIVE' | 'INACTIVE' | string
  lockVersion: number
  createdAt: string
  updatedAt: string
}

export interface SlaPolicySummary {
  slaPolicyId: string
  policyCode: string
  policyName: string
  firstResponseSeconds: number
  resolutionSeconds: number
  firstResponseReminderBeforeSeconds: number | null
  resolutionReminderBeforeSeconds: number | null
  status: 'ACTIVE' | 'INACTIVE' | string
  lockVersion: number
}

export type TicketStatus =
  | 'AWAITING_APPROVAL'
  | 'APPROVAL_REJECTED'
  | 'PENDING_ASSIGNMENT'
  | 'ASSIGNED'
  | 'IN_PROGRESS'
  | 'RESOLVED'
  | 'CLOSED'
  | string

export interface Ticket {
  ticketId: string
  ticketNo: string
  originType: 'MANUAL' | 'INCIDENT' | string
  catalogItemId: string | null
  requesterUserId: string | null
  previousAttemptTicketId: string | null
  subject: string
  description: string
  status: TicketStatus
  assigneeUserId: string | null
  workflowRef: WorkflowRef | null
  firstProcessableAt: string | null
  resolvedAt: string | null
  closedAt: string | null
  lastReopenedAt: string | null
  reopenCount: number
  lockVersion: number
  createdAt: string
  updatedAt: string
}

export interface WorkflowRef {
  definitionId: string
  workflowVersionId: string
  versionNo: number
}

export interface TicketComment {
  commentId: string
  ticketId: string
  body: string
  authorUserId: string
  createdAt: string
  correlationId: string
}

export interface TimelineEntry {
  timelineId: string
  ticketId: string
  eventType: string
  summary: string
  relatedRefType: string | null
  relatedRefId: string | null
  actorType: string
  actorId: string | null
  occurredAt: string
  correlationId: string
}

export interface SlaTrigger {
  notificationStatus?: 'PENDING_DELIVERY' | 'QUEUED'
  stage: 'REMINDER' | 'BREACH' | string
  dueAt: string
  triggeredAt: string | null
}

export interface SlaClock {
  cycleNo?: number
  slaClockId: string
  slaPolicyId: string
  clockType: 'FIRST_RESPONSE' | 'RESOLUTION' | string
  status: 'RUNNING' | 'BREACHED' | 'SATISFIED_ON_TIME' | 'SATISFIED_LATE' | string
  startedAt: string
  deadlineAt: string
  reminderDueAt: string | null
  policyDurationSeconds: number
  policyReminderBeforeSeconds: number | null
  milestoneAt: string | null
  breachedAt: string | null
  satisfiedAt: string | null
  triggers: SlaTrigger[]
  lockVersion: number
}

export interface SlaStatus {
  ticketId: string
  clocks: SlaClock[]
  historyClocks?: SlaClock[]
}

export interface WorkflowStartStatus {
  ticketId: string
  status: 'REQUESTED' | 'ESTABLISHED' | 'RECOVERY_REQUIRED' | string
  workflowInstanceId: string | null
  requestedAt: string
  establishedAt: string | null
  recoveryRequiredAt: string | null
  recoveryReasonCode: string | null
  retryCount: number
  lastAttemptAt: string | null
  lockVersion: number
  createdAt: string
  updatedAt: string
}

export interface WorkflowDefinition {
  definitionId: string
  definitionCode: string
  definitionName: string
  status: string
  lockVersion: number
  createdAt: string
  updatedAt: string
}

export interface WorkflowCondition {
  fieldCode: string
  operator: string
  valueType: string
  value: string
}

export interface WorkflowDraft {
  draftId: string
  definitionId: string
  status: 'EDITABLE' | 'CONSUMED' | string
  condition: WorkflowCondition
  approvalLevelCount: number
  level1ApproverUserId: string
  level2ApproverUserId: string
  validationState: 'UNVALIDATED' | 'VALID' | string
  validationDigest: string | null
  validatedAt: string | null
  consumedAt: string | null
  lockVersion: number
  createdAt: string
  updatedAt: string
}

export interface WorkflowValidation {
  draftId: string
  valid: boolean
  validationDigest: string | null
  violations: FieldViolation[]
  validatedAt: string | null
  lockVersion: number
}

export interface WorkflowVersion {
  workflowVersionId: string
  definitionId: string
  versionNo: number
  status: string
  condition: WorkflowCondition
  approvalLevelCount: number
  level1ApproverUserId: string
  level2ApproverUserId: string
  snapshotSha256: string
  publishedAt: string
  publishedByUserId: string
}

export interface WorkflowInstance {
  workflowInstanceId: string
  ticketId: string
  workflowVersionRef: WorkflowRef
  inputSnapshot: { schemaVersion: string; serviceRiskLevel: string }
  inputSnapshotSha256: string
  conditionResult: boolean
  status: 'RUNNING' | 'COMPLETED_PASSED' | 'COMPLETED_REJECTED' | string
  currentLevel: number | null
  startedAt: string
  completedAt: string | null
  lockVersion: number
}

export interface ApprovalTask {
  approvalTaskId: string
  workflowInstanceId: string
  levelNo: 1 | 2 | number
  approverUserId: string
  status: 'PENDING' | 'APPROVED' | 'REJECTED' | string
  decisionType: 'APPROVE' | 'REJECT' | null | string
  decisionReason: string | null
  decidedAt: string | null
  lockVersion: number
  createdAt: string
  updatedAt: string
}

export interface AlertEvent {
  alertEventId: string
  integrationRef: { integrationId: string; integrationCode: string; integrationName: string }
  externalEventId: string
  eventType: 'FIRING' | 'RESOLVED' | string
  fingerprint: string
  cycleRef: string
  occurredAt: string
  receivedAt: string
  disposition: 'APPLIED' | 'LATE_OLD_GENERATION' | 'REVIEW_REQUIRED' | string
  incidentId: string | null
  rawBodySha256: string
  summary: string | null
  correlationId: string
}

export interface Incident {
  incidentId: string
  fingerprint: string
  generationNo: number
  status: 'ACTIVE' | 'RECOVERED' | string
  eventCount: number
  openedAt: string
  lastEventAt: string
  recoveredAt: string | null
  summary: string | null
  lockVersion: number
  createdAt: string
  updatedAt: string
}

export interface IncidentTicketLink {
  incidentId: string
  status: 'REQUESTED' | 'ESTABLISHED' | 'RECOVERY_REQUIRED' | string
  ticketId: string | null
  requestedAt: string | null
  establishedAt: string | null
  recoveryRequiredAt: string | null
  recoveryReasonCode: string | null
  retryCount: number
  lastAttemptAt: string | null
  lockVersion: number
  createdAt: string | null
  updatedAt: string | null
}

export interface Notification {
  notificationId: string
  notificationKind: NotificationKind | string
  sourceDomain: 'TICKET' | 'WORKFLOW' | 'ALERT' | 'SYSTEM' | string
  sourceRefType: string
  sourceRefId: string
  title: string
  body: string
  state: 'UNREAD' | 'READ' | string
  readAt: string | null
  createdAt: string
  updatedAt: string
  lockVersion: number
}

/** Notification kinds currently emitted by the M8 contracts. */
export type NotificationKind =
  | 'TICKET_ASSIGNED'
  | 'TICKET_RESOLVED'
  | 'WORKFLOW_APPROVAL_REQUESTED'
  | 'WORKFLOW_APPROVAL_FINALIZED'
  | 'SLA_REMINDER'
  | 'SLA_BREACH'
  | 'WORKFLOW_UPDATE'
  | 'TICKET_UPDATE'
  | 'ALERT_UPDATE'
  | 'SYSTEM'

export type RecoveryOwner = 'TICKET' | 'WORKFLOW' | 'ALERT' | 'NOTIFICATION'
export type RecoveryDirection = 'OUTBOX' | 'INBOX'

export interface MessageRecovery {
  recoveryId: string
  owner: RecoveryOwner | string
  direction: RecoveryDirection | string
  sourceRecordId: string
  businessIntentDigest: string
  state: 'OPEN' | 'REPLAYING' | 'RESOLVED' | string
  attemptCount: number
  nextAttemptAt: string | null
  lastErrorCode: string | null
  lastErrorSummary: string | null
  deadLetteredAt: string | null
  recoveryRequiredAt: string
  replayCount: number
  lastReplayedAt: string | null
  lastReplayedBy: { actorType: 'USER'; actorId: string } | null
  lastReplayReason: string | null
  lastReplayCorrelationId?: string | null
  resolvedAt: string | null
  lockVersion: number
  createdAt: string
  updatedAt: string
}

export interface ListResult<T> {
  items: T[]
  meta: ApiMeta
  etag?: string
}

export type FeatureMode = 'disabled' | 'mock' | 'api'

/**
 * 将未知输入收窄为受支持的角色码。
 * 仅接受约定角色白名单，非法类型或未知值返回空值。
 */
export function asRoleCode(value: unknown): RoleCode | null {
  const allowed: RoleCode[] = ['REQUESTER', 'AGENT', 'APPROVER', 'SERVICE_DESK_ADMIN', 'TENANT_ADMIN']
  return typeof value === 'string' && allowed.includes(value as RoleCode) ? value as RoleCode : null
}

/**
 * 将未知枚举值转换为可显示字符串。
 * 有效非空字符串原样返回，其他输入统一使用未知占位。
 */
export function displayEnum(value: unknown): string {
  return typeof value === 'string' && value.length > 0 ? value : 'UNKNOWN'
}
