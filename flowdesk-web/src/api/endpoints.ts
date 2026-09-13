import { apiClient, newIdempotencyKey, type RequestOptions, type RequestResult } from './client'
import { FeatureUnavailableError } from './errors'
import { featureFlags } from './features'
import type {
  AccessBinding, AlertEvent, ApprovalTask, AuthSession, CurrentUser, Incident,
  IncidentTicketLink, ListResult, MessageRecovery, Notification, Permission, Role,
  ServiceCatalogItem, SlaPolicySummary, SlaStatus, SuccessEnvelope, Ticket,
  TicketComment, TimelineEntry, UserSummary, WorkflowDefinition, WorkflowDraft,
  WorkflowInstance, WorkflowStartStatus, WorkflowValidation, WorkflowVersion
} from '@/types/api'

type Query = Record<string, string | number | boolean | null | undefined>

function asList<T>(result: RequestResult<T[]>): ListResult<T> {
  return { items: result.data ?? [], meta: result.meta, etag: result.headers.get('ETag') ?? undefined }
}

async function list<T>(path: string, query?: Query): Promise<ListResult<T>> {
  return asList(await apiClient.get<T[]>(path, { query }))
}

function mutationKey(key?: string): string {
  return key || newIdempotencyKey()
}

async function featureRequest<T>(
  feature: 'notification' | 'recovery',
  mode: string,
  path: string,
  options: RequestOptions = {}
): Promise<RequestResult<T>> {
  if (mode === 'disabled') throw new FeatureUnavailableError(feature, mode)
  if (mode === 'mock') {
    return apiClient.requestMock<T>(path, options)
  }
  return apiClient.request<T>(path, options)
}

async function gatedList<T>(feature: 'notification' | 'recovery', mode: string, path: string, query?: Query) {
  const result = await featureRequest<T[]>(feature, mode, path, { method: 'GET', query })
  return asList(result)
}

export const authApi = {
  login(body: { tenantCode: string; username: string; password: string }) {
    return apiClient.post<AuthSession>('/auth/login', body, { auth: false })
  },
  me() {
    return apiClient.get<CurrentUser>('/users/me')
  }
}

export const iamApi = {
  identity(userId: string) { return apiClient.get<{userId:string;username:string;displayName:string}>(`/users/${userId}/identity`) },
  users(query?: Query) { return list<UserSummary>('/users', query) },
  user(userId: string) { return apiClient.get<UserSummary>(`/users/${userId}`) },
  createUser(body: { username: string; displayName: string; initialPassword: string; status: 'ACTIVE' | 'INACTIVE' }, key?: string) {
    return apiClient.post<UserSummary>('/users', body, { idempotencyKey: mutationKey(key) })
  },
  patchUser(userId: string, body: { displayName?: string; status?: 'ACTIVE' | 'INACTIVE' }, etag: string) {
    return apiClient.patch<UserSummary>(`/users/${userId}`, body, { ifMatch: etag })
  },
  userRoles(userId: string, query?: Query) { return list<AccessBinding>(`/users/${userId}/roles`, query) },
  putUserRole(userId: string, roleId: string, body: { state: 'GRANTED' | 'REVOKED' }, options: { etag?: string; key?: string; create?: boolean } = {}) {
    return apiClient.put<AccessBinding>(`/users/${userId}/roles/${roleId}`, body, {
      ifMatch: options.create ? undefined : options.etag,
      ifNoneMatch: options.create ? '*' : undefined,
      idempotencyKey: mutationKey(options.key)
    })
  },
  roles(query?: Query) { return list<Role>('/roles', query) },
  role(roleId: string) { return apiClient.get<Role>(`/roles/${roleId}`) },
  createRole(body: { roleCode: string; roleName: string; status: 'ACTIVE' | 'INACTIVE' }, key?: string) {
    return apiClient.post<Role>('/roles', body, { idempotencyKey: mutationKey(key) })
  },
  patchRole(roleId: string, body: { roleName?: string; status?: 'ACTIVE' | 'INACTIVE' }, etag: string) {
    return apiClient.patch<Role>(`/roles/${roleId}`, body, { ifMatch: etag })
  },
  permissions(query?: Query) { return list<Permission>('/permissions', query) },
  rolePermissions(roleId: string, query?: Query) { return list<AccessBinding>(`/roles/${roleId}/permissions`, query) },
  putRolePermission(roleId: string, permissionId: string, body: { state: 'GRANTED' | 'REVOKED' }, options: { etag?: string; key?: string; create?: boolean } = {}) {
    return apiClient.put<AccessBinding>(`/roles/${roleId}/permissions/${permissionId}`, body, {
      ifMatch: options.create ? undefined : options.etag,
      ifNoneMatch: options.create ? '*' : undefined,
      idempotencyKey: mutationKey(options.key)
    })
  }
}

export type CatalogWrite = { itemCode: string; itemName: string; description: string; riskLevel: 'NORMAL'|'HIGH'; slaPolicyId: string; workflowRef: ServiceCatalogItem['workflowRef']; status: 'ACTIVE'|'INACTIVE' }
export const catalogApi = {
  create(body: CatalogWrite, key: string) { return apiClient.post<ServiceCatalogItem>('/service-catalog-items',body,{idempotencyKey:key}) },
  configure(id: string, body: CatalogWrite, etag: string) { return apiClient.put<ServiceCatalogItem>(`/service-catalog-items/${id}/configuration`,body,{ifMatch:etag}) },
  items(query?: Query) { return list<ServiceCatalogItem>('/service-catalog-items', query) },
  item(id: string) { return apiClient.get<ServiceCatalogItem>(`/service-catalog-items/${id}`) },
  patchItem(id: string, body: { itemName?: string; description?: string | null; slaPolicyId?: string; status?: 'ACTIVE' | 'INACTIVE' }, etag: string) {
    return apiClient.patch<ServiceCatalogItem>(`/service-catalog-items/${id}`, body, { ifMatch: etag })
  },
  slaPolicies(query?: Query) { return list<SlaPolicySummary>('/sla-policies', query) }
}

export const ticketApi = {
  summary() { return apiClient.get<{ pendingCount: number }>('/tickets/summary') },
  ticketForSlaClock(id: string) { return apiClient.get<{ ticketId: string }>(`/tickets/by-sla-clock/${id}`) },
  approvalContext(ticketId: string, taskId: string) {
    return apiClient.get<Pick<Ticket, 'ticketId' | 'ticketNo' | 'requesterUserId' | 'subject' | 'description' | 'status' | 'createdAt'> & { catalogName: string | null }>(`/tickets/${ticketId}/approval-context/${taskId}`)
  },
  tickets(query?: Query) { return list<Ticket>('/tickets', query) },
  ticket(id: string) { return apiClient.get<Ticket>(`/tickets/${id}`) },
  create(body: { catalogItemId: string; subject: string; description: string; previousAttemptTicketId?: string | null }, key?: string) {
    return apiClient.post<Ticket>('/tickets', body, { idempotencyKey: mutationKey(key) })
  },
  assign(id: string, assigneeUserId: string, etag: string, key?: string) {
    return apiClient.post<Ticket>(`/tickets/${id}/assign`, { assigneeUserId }, { ifMatch: etag, idempotencyKey: mutationKey(key) })
  },
  accept(id: string, etag: string, key?: string) {
    return apiClient.post<Ticket>(`/tickets/${id}/accept`, undefined, { ifMatch: etag, idempotencyKey: mutationKey(key) })
  },
  transfer(id: string, body: { newAssigneeUserId: string; reason: string }, etag: string, key?: string) {
    return apiClient.post<Ticket>(`/tickets/${id}/transfer`, body, { ifMatch: etag, idempotencyKey: mutationKey(key) })
  },
  comments(id: string, query?: Query) { return list<TicketComment>(`/tickets/${id}/comments`, query) },
  addComment(id: string, body: { body: string }, key?: string) {
    return apiClient.post<TicketComment>(`/tickets/${id}/comments`, body, { idempotencyKey: mutationKey(key) })
  },
  resolve(id: string, resolutionSummary: string, etag: string, key?: string) {
    return apiClient.post<Ticket>(`/tickets/${id}/resolve`, { resolutionSummary }, { ifMatch: etag, idempotencyKey: mutationKey(key) })
  },
  close(id: string, reason: string | null, etag: string, key?: string) {
    return apiClient.post<Ticket>(`/tickets/${id}/close`, { reason }, { ifMatch: etag, idempotencyKey: mutationKey(key) })
  },
  reopen(id: string, reason: string, etag: string, key?: string) {
    return apiClient.post<Ticket>(`/tickets/${id}/reopen`, { reason }, { ifMatch: etag, idempotencyKey: mutationKey(key) })
  },
  timeline(id: string, query?: Query) { return list<TimelineEntry>(`/tickets/${id}/timeline`, query) },
  async slaStatus(id: string) {
    if (featureFlags.sla === 'disabled') throw new FeatureUnavailableError('sla', featureFlags.sla)
    if (featureFlags.sla === 'mock') return apiClient.requestMock<SlaStatus>(`/tickets/${id}/sla-status`, { method: 'GET' })
    return apiClient.get<SlaStatus>(`/tickets/${id}/sla-status`)
  },
  workflowStart(id: string) { return apiClient.get<WorkflowStartStatus>(`/tickets/${id}/workflow-start`) }
}

export const workflowApi = {
  taskSummary() { return apiClient.get<{ pendingCount: number }>('/approval-tasks/summary') },
  definitions(query?: Query) { return list<WorkflowDefinition>('/workflow-definitions', query) },
  definition(id: string) { return apiClient.get<WorkflowDefinition>(`/workflow-definitions/${id}`) },
  createDraft(definitionId: string, body: { condition: { fieldCode: string; operator: string; valueType: string; value: string }; level1ApproverUserId: string; level2ApproverUserId: string }, key?: string) {
    return apiClient.post<WorkflowDraft>(`/workflow-definitions/${definitionId}/drafts`, body, { idempotencyKey: mutationKey(key) })
  },
  currentDraft(definitionId: string) { return apiClient.get<WorkflowDraft>(`/workflow-definitions/${definitionId}/drafts/current`) },
  draft(id: string) { return apiClient.get<WorkflowDraft>(`/workflow-drafts/${id}`) },
  patchDraft(id: string, body: { condition: { fieldCode: string; operator: string; valueType: string; value: string }; level1ApproverUserId: string; level2ApproverUserId: string }, etag: string) {
    return apiClient.patch<WorkflowDraft>(`/workflow-drafts/${id}`, body, { ifMatch: etag })
  },
  validateDraft(id: string, etag: string) { return apiClient.post<WorkflowValidation>(`/workflow-drafts/${id}/validate`, undefined, { ifMatch: etag }) },
  publishDraft(id: string, etag: string, key?: string) { return apiClient.post<WorkflowVersion>(`/workflow-drafts/${id}/publish`, undefined, { ifMatch: etag, idempotencyKey: mutationKey(key) }) },
  versions(definitionId: string, query?: Query) { return list<WorkflowVersion>(`/workflow-definitions/${definitionId}/versions`, query) },
  version(id: string) { return apiClient.get<WorkflowVersion>(`/workflow-versions/${id}`) },
  instances(query?: Query) { return list<WorkflowInstance>('/workflow-instances', query) },
  instance(id: string) { return apiClient.get<WorkflowInstance>(`/workflow-instances/${id}`) },
  approverChanges(id: string) {
    return apiClient.get<Array<{ changeId: string; levelNo: number; previousApproverUserId: string; approverUserId: string; changedByUserId: string; reason: string; changedAt: string }>>(`/workflow-instances/${id}/approver-changes`)
  },
  changeApprover(id: string, body: { levelNo: number; approverUserId: string; reason: string }, etag: string, key?: string) {
    return apiClient.post<WorkflowInstance>(`/workflow-instances/${id}/approver-changes`, body, { ifMatch: etag, idempotencyKey: mutationKey(key) })
  },
  tasks(query?: Query) { return list<ApprovalTask>('/approval-tasks', query) },
  task(id: string) { return apiClient.get<ApprovalTask>(`/approval-tasks/${id}`) },
  approve(id: string, reason: string | null, etag: string, key?: string) { return apiClient.post<ApprovalTask>(`/approval-tasks/${id}/approve`, { reason }, { ifMatch: etag, idempotencyKey: mutationKey(key) }) },
  reject(id: string, reason: string, etag: string, key?: string) { return apiClient.post<ApprovalTask>(`/approval-tasks/${id}/reject`, { reason }, { ifMatch: etag, idempotencyKey: mutationKey(key) }) }
}

export const alertApi = {
  summary() { return apiClient.get<{ activeCount: number }>('/incidents/summary') },
  events(query?: Query) { return list<AlertEvent>('/alert-events', query) },
  event(id: string) { return apiClient.get<AlertEvent>(`/alert-events/${id}`) },
  incidents(query?: Query) { return list<Incident>('/incidents', query) },
  incident(id: string) { return apiClient.get<Incident>(`/incidents/${id}`) },
  ticketLink(id: string) { return apiClient.get<IncidentTicketLink>(`/incidents/${id}/ticket-link`) }
}

export const notificationApi = {
  list(query?: Query) {
    return gatedList<Notification>('notification', featureFlags.notification, '/notifications', query)
  },
  unreadCount() {
    return featureRequest<{ unreadCount: number; asOf: string }>('notification', featureFlags.notification, '/notifications/unread-count', { method: 'GET' })
  },
  setReadState(id: string, state: 'READ' | 'UNREAD', etag: string, key?: string) {
    return featureRequest<Notification>('notification', featureFlags.notification, `/notifications/${id}/read-state`, {
      method: 'PUT', body: { state }, ifMatch: etag, idempotencyKey: mutationKey(key)
    })
  }
}

const recoveryPaths = {
  TICKET: '/ticket-message-recoveries',
  WORKFLOW: '/workflow-message-recoveries',
  ALERT: '/alert-message-recoveries',
  NOTIFICATION: '/notification-message-recoveries'
} as const

export const recoveryApi = {
  list(owner: keyof typeof recoveryPaths, query?: Query) {
    return gatedList<MessageRecovery>('recovery', featureFlags.recovery, recoveryPaths[owner], query)
  },
  get(owner: keyof typeof recoveryPaths, id: string) {
    return featureRequest<MessageRecovery>('recovery', featureFlags.recovery, `${recoveryPaths[owner]}/${id}`, { method: 'GET' })
  },
  replay(owner: keyof typeof recoveryPaths, id: string, reason: string, etag: string, key?: string) {
    return featureRequest<MessageRecovery>('recovery', featureFlags.recovery, `${recoveryPaths[owner]}/${id}/replays`, {
      method: 'POST', body: { reason }, ifMatch: etag, idempotencyKey: mutationKey(key)
    })
  }
}

export type AnyApiResult<T> = RequestResult<T> | SuccessEnvelope<T>
