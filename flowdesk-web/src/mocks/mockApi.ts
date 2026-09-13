import { ApiClientError } from '@/api/errors'
import type {
  AccessBinding, AlertEvent, ApprovalTask, AuthSession, CurrentUser, Incident,
  IncidentTicketLink, MessageRecovery, Notification, Permission, Role,
  ServiceCatalogItem, SlaStatus, Ticket, TicketComment, TimelineEntry,
  UserSummary, WorkflowDefinition, WorkflowDraft, WorkflowInstance,
  WorkflowStartStatus, WorkflowValidation, WorkflowVersion
} from '@/types/api'

interface MockOptions {
  method?: string
  headers?: Record<string, string>
  body?: unknown
}

type MockResult<T> = {
  data: T
  meta: { correlationId: string; page?: { pageSize: number; nextPageToken: string | null; sort: string }; asyncEffects?: { kind: string; state: string; relatedRefId: string | null }[] }
  status: number
  headers: Headers
}

const definitionId = '0848bf3c-d603-5ece-9529-f9bba79f1498'
const normalCatalogId = '44073091-f271-4cdd-a0d4-cf1b757aa482'
const highCatalogId = 'dafb0463-774c-503d-89cb-abc3d77e48cb'
const ids = {
  requester: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
  agent: 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb',
  approver1: '33f0b408-5548-5f2b-9865-f1dd288c0493',
  approver2: 'fdecef4b-2a69-538e-9fa5-249048115d3d',
  approver3: 'c3c3c3c3-c3c3-43c3-83c3-c3c3c3c3c3c3',
  admin: 'eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee',
  tenantAdmin: 'ffffffff-ffff-ffff-ffff-ffffffffffff'
}

const now = () => new Date().toISOString()
const uuid = () => {
  if (typeof crypto !== 'undefined' && crypto.randomUUID) return crypto.randomUUID()
  return `00000000-0000-4000-8000-${Math.random().toString(16).slice(2).padEnd(12, '0').slice(0, 12)}`
}

function userFor(username: string): { id: string; roles: CurrentUser['roles']; permissions: CurrentUser['permissions']; displayName: string } {
  const key = username.toLowerCase()
    if (key.includes('admin') && key.includes('tenant')) return {
    id: ids.tenantAdmin, roles: ['TENANT_ADMIN'], displayName: '组织管理员', permissions: [
      'IAM_USER_READ', 'IAM_USER_MANAGE', 'IAM_ROLE_READ', 'IAM_ROLE_MANAGE', 'IAM_PERMISSION_READ',
      'IAM_BINDING_READ', 'IAM_BINDING_MANAGE', 'NOTIFICATION_READ_SELF', 'NOTIFICATION_WRITE_SELF'
    ]
  }
  if (key.includes('admin') || key.includes('desk')) return {
    id: ids.admin, roles: ['SERVICE_DESK_ADMIN'], displayName: '服务台管理员', permissions: [
      'CATALOG_READ_TENANT', 'CATALOG_UPDATE_MINIMAL', 'TICKET_READ_TENANT', 'TICKET_ASSIGN_TENANT',
      'TICKET_TRANSFER_TENANT', 'WORKFLOW_READ_TENANT', 'WORKFLOW_DRAFT_MANAGE', 'WORKFLOW_PUBLISH',
      'WORKFLOW_RUNTIME_READ_TENANT', 'APPROVAL_REASSIGN_TENANT', 'ALERT_EVENT_READ_TENANT', 'INCIDENT_READ_TENANT',
      'RECOVERY_READ_TENANT', 'RECOVERY_REPLAY_TENANT', 'IAM_ELIGIBILITY_CANDIDATE_READ',
      'NOTIFICATION_READ_SELF', 'NOTIFICATION_WRITE_SELF'
    ]
  }
  if (key.includes('approver') || key.includes('manager')) return {
    id: key.includes('3') ? ids.approver3 : key.includes('2') ? ids.approver2 : ids.approver1, roles: ['APPROVER'], displayName: key.includes('3') ? '备用审批人' : key.includes('2') ? '运维负责人' : '部门主管', permissions: [
      'APPROVAL_READ_ASSIGNED', 'APPROVAL_DECIDE_ASSIGNED', 'NOTIFICATION_READ_SELF', 'NOTIFICATION_WRITE_SELF'
    ]
  }
  if (key.includes('agent') || key.includes('operator')) return {
    id: ids.agent, roles: ['AGENT'], displayName: '值班工程师', permissions: [
      'TICKET_READ_ASSIGNED', 'TICKET_ACCEPT_ASSIGNED', 'TICKET_TRANSFER_ASSIGNED', 'TICKET_COMMENT_ASSIGNED',
      'TICKET_RESOLVE_ASSIGNED', 'INCIDENT_READ_TENANT', 'NOTIFICATION_READ_SELF', 'NOTIFICATION_WRITE_SELF'
    ]
  }
  return {
    id: ids.requester, roles: ['REQUESTER'], displayName: username || '申请人', permissions: [
      'CATALOG_READ_ACTIVE', 'TICKET_CREATE', 'TICKET_READ_OWN', 'TICKET_COMMENT_OWN',
      'TICKET_CLOSE_OWN', 'TICKET_REOPEN_OWN', 'NOTIFICATION_READ_SELF', 'NOTIFICATION_WRITE_SELF'
    ]
  }
}

function etag(version: number): string { return `"lv:${version}"` }

export class MockApi {
  private currentUser: CurrentUser | null = null
  private users: UserSummary[] = []
  private roles: Role[] = []
  private permissions: Permission[] = []
  private userRoleBindings = new Map<string, AccessBinding[]>()
  private rolePermissionBindings = new Map<string, AccessBinding[]>()
  private catalogs: ServiceCatalogItem[] = []
  private tickets: Ticket[] = []
  private comments: TicketComment[] = []
  private timeline: TimelineEntry[] = []
  private sla = new Map<string, SlaStatus>()
  private workflowDefinitions: WorkflowDefinition[] = []
  private drafts = new Map<string, WorkflowDraft>()
  private versions: WorkflowVersion[] = []
  private instances: WorkflowInstance[] = []
  private tasks: ApprovalTask[] = []
  private approverOverrides = new Map<string, string>()
  private approverChanges = new Map<string, Array<{ changeId: string; levelNo: number; previousApproverUserId: string; approverUserId: string; changedByUserId: string; reason: string; changedAt: string }>>()
  private notificationRecipients = new Map<string, string>()
  private workflowStarts = new Map<string, WorkflowStartStatus>()
  private events: AlertEvent[] = []
  private incidents: Incident[] = []
  private links = new Map<string, IncidentTicketLink>()
  private notifications: Notification[] = []
  private recoveries: MessageRecovery[] = []
  private idempotency = new Map<string, string>()

  constructor() { this.reset() }

  clearSession(): void {
    this.currentUser = null
  }

  /**
   * Keeps feature-scoped mocks usable when the rest of the app talks to a real
   * Gateway. Only the safe current-user projection is copied; no token or
   * credential is retained by the mock transport.
   */
  setSession(user: CurrentUser): void {
    this.currentUser = { ...user, roles: [...user.roles], permissions: [...user.permissions] }
  }

  reset(): void {
    this.currentUser = null
    this.userRoleBindings.clear(); this.rolePermissionBindings.clear()
    this.tickets = []; this.comments = []; this.timeline = []; this.sla.clear()
    this.approverOverrides.clear(); this.approverChanges.clear(); this.notificationRecipients.clear()
    this.instances = []; this.tasks = []; this.workflowStarts.clear(); this.events = []; this.incidents = []; this.links.clear()
    this.idempotency.clear()
    const timestamp = now()
    const mkUser = (id: string, username: string, displayName: string, roleCodes: UserSummary['roleCodes']): UserSummary => ({ userId: id, username, displayName, status: 'ACTIVE', roleCodes, lockVersion: 0, createdAt: timestamp, updatedAt: timestamp })
    this.users = [
      mkUser(ids.requester, 'requester', '申请人', ['REQUESTER']),
      mkUser(ids.agent, 'agent', '值班工程师', ['AGENT']),
      mkUser(ids.approver1, 'approver1', '部门主管', ['APPROVER']),
      mkUser(ids.approver2, 'approver2', '运维负责人', ['APPROVER']),
      mkUser(ids.approver3, 'approver3', '备用审批人', ['APPROVER']),
      mkUser(ids.admin, 'admin', '服务台管理员', ['SERVICE_DESK_ADMIN']),
      mkUser(ids.tenantAdmin, 'tenant-admin', '组织管理员', ['TENANT_ADMIN'])
    ]
    this.roles = ['REQUESTER', 'AGENT', 'APPROVER', 'SERVICE_DESK_ADMIN', 'TENANT_ADMIN'].map((code, index) => ({ roleId: `10000000-0000-4000-8000-${String(index + 1).padStart(12, '0')}`, roleCode: code as Role['roleCode'], roleName: code, status: 'ACTIVE', lockVersion: 0, createdAt: timestamp, updatedAt: timestamp }))
    const permissionCodes: Permission['permissionCode'][] = [
      'CATALOG_READ_ACTIVE', 'TICKET_CREATE', 'TICKET_READ_OWN', 'TICKET_COMMENT_OWN', 'TICKET_CLOSE_OWN', 'TICKET_REOPEN_OWN',
      'TICKET_READ_ASSIGNED', 'TICKET_ACCEPT_ASSIGNED', 'TICKET_TRANSFER_ASSIGNED', 'TICKET_COMMENT_ASSIGNED', 'TICKET_RESOLVE_ASSIGNED',
      'INCIDENT_READ_TENANT', 'APPROVAL_READ_ASSIGNED', 'APPROVAL_DECIDE_ASSIGNED', 'IAM_ELIGIBILITY_CANDIDATE_READ', 'CATALOG_READ_TENANT',
      'CATALOG_UPDATE_MINIMAL', 'TICKET_READ_TENANT', 'TICKET_ASSIGN_TENANT', 'TICKET_TRANSFER_TENANT', 'WORKFLOW_READ_TENANT', 'WORKFLOW_DRAFT_MANAGE',
      'WORKFLOW_PUBLISH', 'WORKFLOW_RUNTIME_READ_TENANT', 'APPROVAL_REASSIGN_TENANT', 'ALERT_EVENT_READ_TENANT', 'RECOVERY_READ_TENANT', 'RECOVERY_REPLAY_TENANT',
      'IAM_USER_READ', 'IAM_USER_MANAGE', 'IAM_ROLE_READ', 'IAM_ROLE_MANAGE', 'IAM_PERMISSION_READ', 'IAM_BINDING_READ', 'IAM_BINDING_MANAGE',
      'NOTIFICATION_READ_SELF', 'NOTIFICATION_WRITE_SELF'
    ]
    this.permissions = permissionCodes.map((code, index) => ({ permissionId: `20000000-0000-4000-8000-${String(index + 1).padStart(12, '0')}`, permissionCode: code, permissionName: code, description: null }))
    const roleByCode = new Map(this.roles.map(role => [role.roleCode, role]))
    for (const user of this.users) {
      this.userRoleBindings.set(user.userId, user.roleCodes.map(code => ({ subjectId: user.userId, targetId: roleByCode.get(code)?.roleId || '', state: 'GRANTED', grantedAt: timestamp, revokedAt: null, lockVersion: 0 })))
    }
    for (const role of this.roles) {
      const personas = { REQUESTER: 'requester', AGENT: 'agent', APPROVER: 'approver1', SERVICE_DESK_ADMIN: 'admin', TENANT_ADMIN: 'tenant-admin' }
      const allowed = permissionCodes.filter(code => userFor(personas[role.roleCode]).permissions.includes(code))
      for(const code of allowed){const permission=this.permissions.find(p=>p.permissionCode===code)!;permission.allowedRoles=[...(permission.allowedRoles||[]),role.roleCode]}
      this.rolePermissionBindings.set(role.roleId, allowed.map(code => ({ subjectId: role.roleId, targetId: this.permissions.find(permission => permission.permissionCode === code)?.permissionId || '', state: 'GRANTED', grantedAt: timestamp, revokedAt: null, lockVersion: 0 })))
    }
    const policyId = '30000000-0000-4000-8000-000000000001'
    this.catalogs = [
      { catalogItemId: normalCatalogId, itemCode: 'STANDARD_ACCESS', itemName: '标准 IT 服务请求', description: '普通人工服务请求', riskLevel: 'NORMAL', requiresApproval: false, workflowRef: null, slaPolicyId: policyId, status: 'ACTIVE', lockVersion: 0, createdAt: timestamp, updatedAt: timestamp },
      { catalogItemId: highCatalogId, itemCode: 'PRODUCTION_DATABASE_ACCESS', itemName: '生产数据库权限', description: '需要两级条件审批的高风险请求', riskLevel: 'HIGH', requiresApproval: true, workflowRef: { definitionId, workflowVersionId: '40000000-0000-4000-8000-000000000001', versionNo: 1 }, slaPolicyId: policyId, status: 'ACTIVE', lockVersion: 0, createdAt: timestamp, updatedAt: timestamp }
    ]
    this.workflowDefinitions = [{ definitionId, definitionCode: 'HIGH_RISK_ACCESS', definitionName: '高风险访问审批', status: 'ACTIVE', lockVersion: 0, createdAt: timestamp, updatedAt: timestamp }]
    this.versions = [{ workflowVersionId: '40000000-0000-4000-8000-000000000001', definitionId, versionNo: 1, status: 'PUBLISHED', condition: { fieldCode: 'SERVICE_RISK_LEVEL', operator: 'EQUALS', valueType: 'STRING', value: 'HIGH' }, approvalLevelCount: 2, level1ApproverUserId: ids.approver1, level2ApproverUserId: ids.approver2, snapshotSha256: 'a'.repeat(64), publishedAt: timestamp, publishedByUserId: ids.admin }]
    this.notifications = [this.notification('系统欢迎', '欢迎使用 FlowDesk 服务台。', 'TICKET', ids.requester, 'UNREAD'), this.notification('审批提示', '高风险请求需要两级审批。', 'WORKFLOW', ids.approver1, 'UNREAD')]
    this.recoveries = []
  }

  private notification(title: string, body: string, sourceDomain: 'TICKET' | 'WORKFLOW', recipient: string, state: 'READ' | 'UNREAD', sourceRefType = 'USER', sourceRefId = recipient, notificationKind = 'WORKFLOW_UPDATE'): Notification {
    const timestamp = now()
    const notification: Notification = { notificationId: uuid(), notificationKind, sourceDomain, sourceRefType, sourceRefId, title, body, state, readAt: state === 'READ' ? timestamp : null, createdAt: timestamp, updatedAt: timestamp, lockVersion: 0 }
    this.notificationRecipients.set(notification.notificationId, recipient)
    return notification
  }

  async request<T>(path: string, options: MockOptions = {}): Promise<MockResult<T>> {
    await new Promise(resolve => setTimeout(resolve, 4))
    const url = new URL(path, 'http://flowdesk.mock')
    const pathname = url.pathname.replace(/^\/api\/v1/, '') || '/'
    const method = (options.method ?? 'GET').toUpperCase()
    const headers = new Headers({ 'X-Correlation-Id': options.headers?.['X-Correlation-Id'] || uuid() })
    try {
      const result = await this.dispatch<T>(pathname, method, url.searchParams, options, headers)
      return result
    } catch (error) {
      if (error instanceof ApiClientError) throw error
      throw new ApiClientError({ status: 500, code: 'INTERNAL_ERROR', correlationId: headers.get('X-Correlation-Id'), headers })
    }
  }

  private ok<T>(data: T, status: number, headers: Headers, version?: number, asyncEffects?: MockResult<T>['meta']['asyncEffects']): MockResult<T> {
    if (version !== undefined) headers.set('ETag', etag(version))
    return { data, status, headers, meta: { correlationId: headers.get('X-Correlation-Id') || '', ...(asyncEffects ? { asyncEffects } : {}) } }
  }

  private list<T>(items: T[], params: URLSearchParams, headers: Headers, sort = '-updatedAt'): MockResult<T[]> {
    const pageSize = Math.min(100, Math.max(1, Number(params.get('pageSize') || 20)))
    const start = params.get('pageToken') ? Number(params.get('pageToken')) || 0 : 0
    const pageItems = items.slice(start, start + pageSize)
    const next = start + pageSize < items.length ? String(start + pageSize) : null
    return { ...this.ok(pageItems, 200, headers), meta: { correlationId: headers.get('X-Correlation-Id') || '', page: { pageSize: pageItems.length, nextPageToken: next, sort } } }
  }

  private error(status: number, code: string, headers: Headers, retryable = false): never {
    throw new ApiClientError({ status, code, correlationId: headers.get('X-Correlation-Id'), retryable, headers })
  }

  private requireUser(headers: Headers): CurrentUser {
    if (!this.currentUser) this.error(401, 'AUTHENTICATION_REQUIRED', headers)
    return this.currentUser
  }

  private body(options: MockOptions): Record<string, any> { return (options.body && typeof options.body === 'object') ? options.body as Record<string, any> : {} }
  private requireDistinctApprovers(rule: { level1ApproverUserId?: string; level2ApproverUserId?: string }, headers: Headers): void {
    if (!rule.level1ApproverUserId || !rule.level2ApproverUserId || rule.level1ApproverUserId === rule.level2ApproverUserId) this.error(422, 'APPROVERS_MUST_DIFFER', headers)
  }

  private checkEtag(item: { lockVersion: number }, options: MockOptions, headers: Headers): void {
    const given = options.headers?.['If-Match']
    if (given && given !== etag(item.lockVersion)) this.error(412, 'RESOURCE_VERSION_CONFLICT', headers)
    if (!given && (options.method || '').toUpperCase() !== 'GET') this.error(428, 'PRECONDITION_REQUIRED', headers)
  }

  private async dispatch<T>(path: string, method: string, params: URLSearchParams, options: MockOptions, headers: Headers): Promise<MockResult<T>> {
    if (path === '/auth/login' && method === 'POST') {
      const input = this.body(options)
      if (!input.tenantCode || !input.username || !input.password) this.error(401, 'LOGIN_FAILED', headers)
      const actor = userFor(String(input.username))
      const member = this.users.find(u => u.username === input.username)
      if (member?.status === 'INACTIVE') this.error(403, 'ACCOUNT_INACTIVE', headers)
      const roles = member ? this.roles.filter(r => r.status === 'ACTIVE' && member.roleCodes.includes(r.roleCode)) : []
      const permissions = member ? this.permissions.filter(p => roles.some(r => p.allowedRoles?.includes(r.roleCode)
        && this.rolePermissionBindings.get(r.roleId)?.some(b => b.targetId === p.permissionId && b.state === 'GRANTED'))).map(p => p.permissionCode) : actor.permissions
      this.currentUser = { userId: member?.userId ?? actor.id, username: String(input.username), displayName: member?.displayName ?? actor.displayName, status: 'ACTIVE', tenant: { tenantCode: String(input.tenantCode), displayName: '山城科技服务台' }, roles: member ? roles.map(r => r.roleCode) : actor.roles, permissions }
      const session: AuthSession = { accessToken: `mock-token-${actor.id}`, tokenType: 'Bearer', expiresAt: new Date(Date.now() + 900_000).toISOString(), user: this.currentUser }
      headers.set('Cache-Control', 'no-store')
      return this.ok(session, 200, headers)
    }
    if (path === '/users/me' && method === 'GET') return this.ok(this.requireUser(headers), 200, headers)
    if (path === '/users' && method === 'GET') return this.list(this.users.filter(u => (!params.get('status') || u.status === params.get('status')) && (!params.get('roleCode') || u.roleCodes.includes(params.get('roleCode') as Role['roleCode']))), params, headers, params.get('sort') || 'displayName')
    if (path === '/users' && method === 'POST') {
      this.requireUser(headers); const input = this.body(options); const existing = this.users.find(u => u.username === input.username)
      if (existing) this.error(409, 'USERNAME_ALREADY_EXISTS', headers)
      const created: UserSummary = { userId: uuid(), username: input.username, displayName: input.displayName, status: input.status || 'ACTIVE', roleCodes: [], lockVersion: 0, createdAt: now(), updatedAt: now() }
      this.users.push(created); this.userRoleBindings.set(created.userId, []); return this.ok(created, 201, headers, created.lockVersion)
    }
    const identityMatch=path.match(/^\/users\/([^/]+)\/identity$/)
    if(identityMatch&&method==='GET'){const user=this.users.find(u=>u.userId===identityMatch[1]);if(!user)this.error(404,'RESOURCE_NOT_FOUND',headers);return this.ok({userId:user.userId,username:user.username,displayName:user.displayName},200,headers)}
    const userMatch = path.match(/^\/users\/([^/]+)$/)
    if (userMatch && method === 'GET') { const item = this.users.find(u => u.userId === userMatch[1]); if (!item) this.error(404, 'RESOURCE_NOT_FOUND', headers); return this.ok(item, 200, headers, item.lockVersion) }
    if (userMatch && method === 'PATCH') { const item = this.users.find(u => u.userId === userMatch[1]); if (!item) this.error(404, 'RESOURCE_NOT_FOUND', headers); this.checkEtag(item, options, headers); Object.assign(item, this.body(options), { lockVersion: item.lockVersion + 1, updatedAt: now() }); return this.ok(item, 200, headers, item.lockVersion) }
    const userRoleMatch = path.match(/^\/users\/([^/]+)\/roles(?:\/([^/]+))?$/)
    if (userRoleMatch && method === 'GET') { const item = this.users.find(user => user.userId === userRoleMatch[1]); if (!item) this.error(404, 'RESOURCE_NOT_FOUND', headers); return this.list(this.userRoleBindings.get(userRoleMatch[1]) || [], params, headers, params.get('sort') || 'roleCode') }
    if (userRoleMatch && userRoleMatch[2] && method === 'PUT') return this.updateBinding('user', userRoleMatch[1], userRoleMatch[2], options, headers)
    if (path === '/roles' && method === 'GET') return this.list(this.roles, params, headers, params.get('sort') || 'roleName')
    if (path === '/roles' && method === 'POST') {
      this.requireUser(headers); const input = this.body(options); if (this.roles.some(role => role.roleCode === input.roleCode)) this.error(409, 'ROLE_CODE_ALREADY_EXISTS', headers)
      const role: Role = { roleId: uuid(), roleCode: input.roleCode, roleName: input.roleName, status: input.status || 'ACTIVE', lockVersion: 0, createdAt: now(), updatedAt: now() }
      this.roles.push(role); this.rolePermissionBindings.set(role.roleId, []); return this.ok(role, 201, headers, role.lockVersion)
    }
    if (path === '/permissions' && method === 'GET') return this.list(this.permissions, params, headers, params.get('sort') || 'permissionCode')
    const roleMatch = path.match(/^\/roles\/([^/]+)$/)
    if (roleMatch && method === 'GET') { const role = this.roles.find(r => r.roleId === roleMatch[1]); if (!role) this.error(404, 'RESOURCE_NOT_FOUND', headers); return this.ok(role, 200, headers, role.lockVersion) }
    if (roleMatch && method === 'PATCH') { const role = this.roles.find(r => r.roleId === roleMatch[1]); if (!role) this.error(404, 'RESOURCE_NOT_FOUND', headers); this.checkEtag(role, options, headers); Object.assign(role, this.body(options), { lockVersion: role.lockVersion + 1, updatedAt: now() }); return this.ok(role, 200, headers, role.lockVersion) }
    const rolePermissionMatch = path.match(/^\/roles\/([^/]+)\/permissions(?:\/([^/]+))?$/)
    if (rolePermissionMatch && method === 'GET') { const role = this.roles.find(item => item.roleId === rolePermissionMatch[1]); if (!role) this.error(404, 'RESOURCE_NOT_FOUND', headers); return this.list(this.rolePermissionBindings.get(rolePermissionMatch[1]) || [], params, headers, params.get('sort') || 'permissionCode') }
    if (rolePermissionMatch && rolePermissionMatch[2] && method === 'PUT') return this.updateBinding('role', rolePermissionMatch[1], rolePermissionMatch[2], options, headers)
    if (path === '/service-catalog-items' && method === 'GET') return this.list(this.catalogs.filter(c => c.status === 'ACTIVE' || this.currentUser?.roles.includes('SERVICE_DESK_ADMIN')), params, headers, params.get('sort') || 'itemName')
    if(path==='/service-catalog-items'&&method==='POST'){
      const input=this.body(options);if(this.catalogs.some(c=>c.itemCode===input.itemCode))this.error(409,'CATALOG_CODE_EXISTS',headers)
      const item={...input,catalogItemId:uuid(),requiresApproval:input.riskLevel==='HIGH',lockVersion:0,createdAt:now(),updatedAt:now()} as ServiceCatalogItem
      this.catalogs.push(item);return this.ok(item,201,headers,0)
    }
    const configMatch=path.match(/^\/service-catalog-items\/([^/]+)\/configuration$/)
    if(configMatch&&method==='PUT'){const item=this.catalogs.find(c=>c.catalogItemId===configMatch[1]);if(!item)this.error(404,'RESOURCE_NOT_FOUND',headers);this.checkEtag(item,options,headers);const input=this.body(options);Object.assign(item,input,{requiresApproval:input.riskLevel==='HIGH',lockVersion:item.lockVersion+1,updatedAt:now()});return this.ok(item,200,headers,item.lockVersion)}
    const catalogMatch = path.match(/^\/service-catalog-items\/([^/]+)$/)
    if (catalogMatch && method === 'GET') { const item = this.catalogs.find(c => c.catalogItemId === catalogMatch[1]); if (!item || (item.status !== 'ACTIVE' && !this.currentUser?.roles.includes('SERVICE_DESK_ADMIN'))) this.error(404, 'RESOURCE_NOT_FOUND', headers); return this.ok(item, 200, headers, item.lockVersion) }
    if (path === '/sla-policies' && method === 'GET') return this.list([{ slaPolicyId: '30000000-0000-4000-8000-000000000001', policyCode: 'STANDARD_24X7', policyName: '标准 24x7', firstResponseSeconds: 3600, resolutionSeconds: 28800, firstResponseReminderBeforeSeconds: 900, resolutionReminderBeforeSeconds: 1800, status: 'ACTIVE', lockVersion: 0 }], params, headers, params.get('sort') || 'policyName')
    if (path === '/tickets/summary' && method === 'GET') return this.ok({ pendingCount: this.visibleTickets().filter(t => ['AWAITING_APPROVAL', 'PENDING_ASSIGNMENT', 'ASSIGNED', 'IN_PROGRESS'].includes(t.status)).length }, 200, headers)
    if (path === '/approval-tasks/summary' && method === 'GET') return this.ok({ pendingCount: this.visibleTasks().filter(t => t.status === 'PENDING').length }, 200, headers)
    if (path === '/incidents/summary' && method === 'GET') return this.ok({ activeCount: this.incidents.filter(i => i.status === 'ACTIVE').length }, 200, headers)
    if (path === '/tickets' && method === 'GET') return this.list(this.visibleTickets(params.get('scope')), params, headers, params.get('sort') || '-updatedAt')
    if (path === '/tickets' && method === 'POST') return this.createTicket<T>(options, headers)
    const clockSource = path.match(/^\/tickets\/by-sla-clock\/([^/]+)$/)
    if (clockSource && method === 'GET') {
      const ticket = this.visibleTickets().find(t => [...(this.sla.get(t.ticketId)?.clocks ?? []), ...(this.sla.get(t.ticketId)?.historyClocks ?? [])].some(c => c.slaClockId === clockSource[1]))
      if (!ticket) this.error(404, 'RESOURCE_NOT_FOUND', headers)
      return this.ok({ ticketId: ticket.ticketId }, 200, headers)
    }
    const ticketMatch = path.match(/^\/tickets\/([^/]+)(?:\/(.*))?$/)
    if (ticketMatch) return this.ticketDispatch<T>(ticketMatch[1], ticketMatch[2] || '', method, params, options, headers)
    if (path === '/workflow-definitions' && method === 'GET') return this.list(this.workflowDefinitions, params, headers, params.get('sort') || 'definitionName')
    const definitionMatch = path.match(/^\/workflow-definitions\/([^/]+)(?:\/(.*))?$/)
    if (definitionMatch) return this.workflowDispatch<T>(definitionMatch[1], definitionMatch[2] || '', method, params, options, headers)
    const draftMatch = path.match(/^\/workflow-drafts\/([^/]+)(?:\/(validate|publish))?$/)
    if (draftMatch) return this.draftDispatch<T>(draftMatch[1], draftMatch[2] || '', method, options, headers)
    const versionMatch = path.match(/^\/workflow-versions\/([^/]+)$/)
    if (versionMatch && method === 'GET') { const v = this.versions.find(x => x.workflowVersionId === versionMatch[1]); if (!v) this.error(404, 'RESOURCE_NOT_FOUND', headers); return this.ok(v, 200, headers) }
    if (path === '/workflow-instances' && method === 'GET') return this.list(this.instances, params, headers, params.get('sort') || '-startedAt')
    const handoffMatch = path.match(/^\/workflow-instances\/([^/]+)\/approver-changes$/)
    if (handoffMatch) return this.handoffDispatch<T>(handoffMatch[1], method, options, headers)
    const instanceMatch = path.match(/^\/workflow-instances\/([^/]+)$/)
    if (instanceMatch && method === 'GET') { const v = this.instances.find(x => x.workflowInstanceId === instanceMatch[1]); if (!v) this.error(404, 'RESOURCE_NOT_FOUND', headers); return this.ok(v, 200, headers, v.lockVersion) }
    if (path === '/approval-tasks' && method === 'GET') return this.list(this.visibleTasks(), params, headers, params.get('sort') || '-createdAt')
    const taskMatch = path.match(/^\/approval-tasks\/([^/]+)(?:\/(approve|reject))?$/)
    if (taskMatch) return this.taskDispatch<T>(taskMatch[1], taskMatch[2] || '', method, options, headers)
    if (path === '/alert-events' && method === 'GET') return this.list(this.events, params, headers, params.get('sort') || '-receivedAt')
    const eventMatch = path.match(/^\/alert-events\/([^/]+)$/)
    if (eventMatch && method === 'GET') { const e = this.events.find(x => x.alertEventId === eventMatch[1]); if (!e) this.error(404, 'RESOURCE_NOT_FOUND', headers); return this.ok(e, 200, headers) }
    if (path === '/incidents' && method === 'GET') return this.list(this.incidents, params, headers, params.get('sort') || '-lastEventAt')
    const incidentMatch = path.match(/^\/incidents\/([^/]+)(?:\/ticket-link)?$/)
    if (incidentMatch) { const incident = this.incidents.find(x => x.incidentId === incidentMatch[1]); if (!incident) this.error(404, 'RESOURCE_NOT_FOUND', headers); if (path.endsWith('/ticket-link')) { const link = this.links.get(incident.incidentId); if (!link) this.error(404, 'RESOURCE_NOT_FOUND', headers); return this.ok(link, 200, headers, link.lockVersion) } return this.ok(incident, 200, headers, incident.lockVersion) }
    if (path === '/notifications' && method === 'GET') return this.list(this.notificationsForUser(), params, headers, params.get('sort') || '-createdAt')
    if (path === '/notifications/unread-count' && method === 'GET') return this.ok({ unreadCount: this.notificationsForUser().filter(n => n.state === 'UNREAD').length, asOf: now() }, 200, headers)
    const notificationMatch = path.match(/^\/notifications\/([^/]+)\/read-state$/)
    if (notificationMatch && method === 'PUT') { const item = this.notificationsForUser().find(n => n.notificationId === notificationMatch[1]); if (!item) this.error(404, 'NOTIFICATION_RECIPIENT_ONLY', headers); this.checkEtag(item, options, headers); const body = this.body(options); item.state = body.state; item.readAt = body.state === 'READ' ? now() : null; item.lockVersion += 1; item.updatedAt = now(); return this.ok(item, 200, headers, item.lockVersion) }
    if (path.endsWith('message-recoveries') && method === 'GET') return this.list(this.recoveries, params, headers, params.get('sort') || '-recoveryRequiredAt')
    const recoveryMatch = path.match(/^\/(ticket|workflow|alert|notification)-message-recoveries\/([^/]+)(?:\/replays)?$/)
    if (recoveryMatch) { const recovery = this.recoveries.find(r => r.recoveryId === recoveryMatch[2]); if (!recovery) this.error(404, 'RECOVERY_ROUTE_MISMATCH', headers); if (path.endsWith('/replays') && method === 'POST') { this.checkEtag(recovery, options, headers); recovery.state = 'REPLAYING'; recovery.replayCount += 1; recovery.lockVersion += 1; return this.ok(recovery, 202, headers, recovery.lockVersion) } return this.ok(recovery, 200, headers, recovery.lockVersion) }
    this.error(404, 'RESOURCE_NOT_FOUND', headers)
  }

  private visibleTickets(scope: string | null = null): Ticket[] {
    const user = this.currentUser
    if (!user) return []
    const all = user.permissions.includes('TICKET_READ_TENANT')
    const own = user.permissions.includes('TICKET_READ_OWN')
    const assigned = user.permissions.includes('TICKET_READ_ASSIGNED')
    return this.tickets.filter(t => {
      if (scope === 'mine') return (all || own) && t.requesterUserId === user.userId
      if (scope === 'assigned') return (all || assigned) && t.assigneeUserId === user.userId
      return all || (own && t.requesterUserId === user.userId) || (assigned && t.assigneeUserId === user.userId)
    })
  }

  private notificationsForUser(): Notification[] {
    const id = this.currentUser?.userId
    return id ? this.notifications.filter(n => (this.notificationRecipients.get(n.notificationId) ?? n.sourceRefId) === id) : []
  }

  private updateBinding(kind: 'user' | 'role', subjectId: string, targetId: string, options: MockOptions, headers: Headers): MockResult<AccessBinding> {
    this.requireUser(headers)
    const store = kind === 'user' ? this.userRoleBindings : this.rolePermissionBindings
    const items = store.get(subjectId) || []
    const existing = items.find(item => item.targetId === targetId)
    const input = this.body(options)
    if (existing) {
      this.checkEtag(existing, options, headers)
      existing.state = input.state
      existing.revokedAt = input.state === 'REVOKED' ? now() : null
      existing.lockVersion += 1
      if (kind === 'user') {
        const user = this.users.find(item => item.userId === subjectId); const role = this.roles.find(item => item.roleId === targetId)
        if (user && role) {
          if (existing.state === 'GRANTED' && !user.roleCodes.includes(role.roleCode)) user.roleCodes.push(role.roleCode)
          if (existing.state === 'REVOKED') user.roleCodes = user.roleCodes.filter(code => code !== role.roleCode)
        }
      }
      return this.ok(existing, 200, headers, existing.lockVersion)
    }
    if (options.headers?.['If-None-Match'] !== '*') this.error(428, 'PRECONDITION_REQUIRED', headers)
    const binding: AccessBinding = { subjectId, targetId, state: input.state || 'GRANTED', grantedAt: now(), revokedAt: input.state === 'REVOKED' ? now() : null, lockVersion: 0 }
    items.push(binding); store.set(subjectId, items)
    if (kind === 'user') {
      const user = this.users.find(item => item.userId === subjectId); const role = this.roles.find(item => item.roleId === targetId)
      if (user && role && binding.state === 'GRANTED' && !user.roleCodes.includes(role.roleCode)) user.roleCodes.push(role.roleCode)
    }
    return this.ok(binding, 201, headers, binding.lockVersion)
  }

  private createTicket<T>(options: MockOptions, headers: Headers): MockResult<T> {
    const actor = this.requireUser(headers); const body = this.body(options); const key = options.headers?.['Idempotency-Key'] || ''
    if (key && this.idempotency.has(key)) { const existing = this.tickets.find(t => t.ticketId === this.idempotency.get(key)); if (existing) { headers.set('Idempotency-Replayed', 'true'); return this.ok(existing as T, 200, headers, existing.lockVersion) } }
    const catalog = this.catalogs.find(c => c.catalogItemId === body.catalogItemId); if (!catalog || catalog.status !== 'ACTIVE') this.error(409, 'CATALOG_ITEM_NOT_AVAILABLE', headers)
    const timestamp = now(); const ticket: Ticket = { ticketId: uuid(), ticketNo: `FD-${String(this.tickets.length + 1).padStart(6, '0')}`, originType: 'MANUAL', catalogItemId: catalog.catalogItemId, requesterUserId: actor.userId, previousAttemptTicketId: body.previousAttemptTicketId ?? null, subject: body.subject, description: body.description, status: catalog.requiresApproval ? 'AWAITING_APPROVAL' : 'PENDING_ASSIGNMENT', assigneeUserId: null, workflowRef: catalog.workflowRef, firstProcessableAt: catalog.requiresApproval ? null : timestamp, resolvedAt: null, closedAt: null, lastReopenedAt: null, reopenCount: 0, lockVersion: 0, createdAt: timestamp, updatedAt: timestamp }
    this.tickets.push(ticket); if (key) this.idempotency.set(key, ticket.ticketId)
    this.addTimeline(ticket, catalog.requiresApproval ? 'APPROVAL_REQUESTED' : 'CREATED', catalog.requiresApproval ? '已提交两级审批' : '工单已创建', 'USER', actor.userId)
    const effects = catalog.requiresApproval ? [{ kind: 'WORKFLOW_START', state: 'REQUESTED', relatedRefId: null }] : undefined
    if (!catalog.requiresApproval) this.createSla(ticket)
    if (catalog.requiresApproval) this.createWorkflow(ticket)
    return this.ok(ticket as T, 201, headers, ticket.lockVersion, effects)
  }

  private addTimeline(ticket: Ticket, eventType: string, summary: string, actorType: string, actorId: string | null, relatedRefType: string | null = null, relatedRefId: string | null = null): void {
    this.timeline.push({ timelineId: uuid(), ticketId: ticket.ticketId, eventType, summary, relatedRefType, relatedRefId, actorType, actorId, occurredAt: now(), correlationId: uuid() })
  }

  private createSla(ticket: Ticket): void {
    const started = new Date(ticket.createdAt); const policyId = this.catalogs.find(c => c.catalogItemId === ticket.catalogItemId)?.slaPolicyId || '30000000-0000-4000-8000-000000000001'
    const clock = (type: 'FIRST_RESPONSE' | 'RESOLUTION', seconds: number) => ({ slaClockId: uuid(), slaPolicyId: policyId, clockType: type, status: 'RUNNING', startedAt: ticket.createdAt, deadlineAt: new Date(started.getTime() + seconds * 1000).toISOString(), reminderDueAt: null, policyDurationSeconds: seconds, policyReminderBeforeSeconds: null, milestoneAt: null, breachedAt: null, satisfiedAt: null, triggers: [], lockVersion: 0 })
    this.sla.set(ticket.ticketId, { ticketId: ticket.ticketId, clocks: [clock('FIRST_RESPONSE', 3600), clock('RESOLUTION', 28800)] })
  }

  private createWorkflow(ticket: Ticket): void {
    const requestedAt = now()
    this.workflowStarts.set(ticket.ticketId, { ticketId: ticket.ticketId, status: 'REQUESTED', workflowInstanceId: null, requestedAt, establishedAt: null, recoveryRequiredAt: null, recoveryReasonCode: null, retryCount: 0, lastAttemptAt: requestedAt, lockVersion: 0, createdAt: requestedAt, updatedAt: requestedAt })
    // The mock keeps the same observable boundary as the real outbox flow: creation
    // returns REQUESTED, then a short asynchronous hop establishes the instance/task.
    setTimeout(() => {
      const current = this.tickets.find(item => item.ticketId === ticket.ticketId)
      if (!current || current.status !== 'AWAITING_APPROVAL') return
      const timestamp = now(); const base = this.versions.find(v => v.workflowVersionId === ticket.workflowRef?.workflowVersionId)
      if (!base) return
      const instance: WorkflowInstance = { workflowInstanceId: uuid(), ticketId: ticket.ticketId, workflowVersionRef: ticket.workflowRef || { definitionId, workflowVersionId: base.workflowVersionId, versionNo: 1 }, inputSnapshot: { schemaVersion: '1', serviceRiskLevel: 'HIGH' }, inputSnapshotSha256: 'b'.repeat(64), conditionResult: true, status: 'RUNNING', currentLevel: 1, startedAt: timestamp, completedAt: null, lockVersion: 0 }
      this.instances.push(instance)
      const task: ApprovalTask = { approvalTaskId: uuid(), workflowInstanceId: instance.workflowInstanceId, levelNo: 1, approverUserId: base.level1ApproverUserId, status: 'PENDING', decisionType: null, decisionReason: null, decidedAt: null, lockVersion: 0, createdAt: timestamp, updatedAt: timestamp }
      this.tasks.push(task)
      this.notifications.push(this.notification('有新的审批待办', `${ticket.ticketNo} · ${ticket.subject}，请查看申请内容后办理。`, 'WORKFLOW', task.approverUserId, 'UNREAD', 'APPROVAL_TASK', task.approvalTaskId, 'APPROVAL_TASK_CREATED'))
      const start = this.workflowStarts.get(ticket.ticketId)
      if (start) { start.status = 'ESTABLISHED'; start.workflowInstanceId = instance.workflowInstanceId; start.establishedAt = timestamp; start.lastAttemptAt = timestamp; start.lockVersion += 1; start.updatedAt = timestamp }
    }, 35)
  }

  private async ticketDispatch<T>(id: string, action: string, method: string, params: URLSearchParams, options: MockOptions, headers: Headers): Promise<MockResult<T>> {
    const ticket = this.tickets.find(t => t.ticketId === id); if (!ticket) this.error(404, 'RESOURCE_NOT_FOUND', headers)
    const actor = this.requireUser(headers)
    if (action.startsWith('approval-context/') && method === 'GET') {
      const taskId = action.slice('approval-context/'.length)
      const task = this.tasks.find(t => t.approvalTaskId === taskId && t.approverUserId === actor.userId)
      const instance = task && this.instances.find(i => i.workflowInstanceId === task.workflowInstanceId && i.ticketId === id)
      if (!actor.permissions.includes('APPROVAL_READ_ASSIGNED') || !instance) this.error(404, 'RESOURCE_NOT_FOUND', headers)
      return this.ok({ ticketId: ticket.ticketId, ticketNo: ticket.ticketNo, subject: ticket.subject, description: ticket.description,
        requesterUserId: ticket.requesterUserId, status: ticket.status, createdAt: ticket.createdAt,
        catalogName: this.catalogs.find(c => c.catalogItemId === ticket.catalogItemId)?.itemName ?? null } as T, 200, headers)
    }
    if (!action && method === 'GET') {
      if (!this.visibleTickets().includes(ticket)) this.error(404, 'RESOURCE_NOT_FOUND', headers)
      return this.ok(ticket as T, 200, headers, ticket.lockVersion)
    }
    if (action === 'comments' && method === 'GET') return this.list(this.comments.filter(c => c.ticketId === id), params, headers, params.get('sort') || 'createdAt') as MockResult<T>
    if (action === 'timeline' && method === 'GET') return this.list(this.timeline.filter(t => t.ticketId === id), params, headers, params.get('sort') || 'occurredAt') as MockResult<T>
    if (action === 'sla-status' && method === 'GET') { const value = this.sla.get(id); if (!value) this.error(409, 'SLA_NOT_STARTED', headers); return this.ok(value as T, 200, headers) }
    if (action === 'workflow-start' && method === 'GET') { const value = this.workflowStarts.get(id); if (!value) this.error(404, 'RESOURCE_NOT_FOUND', headers); return this.ok(value as T, 200, headers, value.lockVersion) }
    if (action === 'comments' && method === 'POST') { if (ticket.status === 'CLOSED' || ticket.status === 'APPROVAL_REJECTED') this.error(409, 'TICKET_TERMINAL', headers); const body = this.body(options); const comment: TicketComment = { commentId: uuid(), ticketId: id, body: body.body, authorUserId: actor.userId, createdAt: now(), correlationId: headers.get('X-Correlation-Id') || uuid() }; this.comments.push(comment); this.addTimeline(ticket, 'COMMENT_ADDED', '新增评论', 'USER', actor.userId); return this.ok(comment as T, 201, headers) }
    this.checkEtag(ticket, options, headers)
    if (action === 'assign' && method === 'POST') { const body = this.body(options); ticket.assigneeUserId = body.assigneeUserId; ticket.status = 'ASSIGNED'; ticket.lockVersion += 1; ticket.updatedAt = now(); this.notifications.push(this.notification('工单已分配', ticket.ticketNo + ' · ' + ticket.subject, 'TICKET', body.assigneeUserId, 'UNREAD', 'TICKET', ticket.ticketId, 'TICKET_ASSIGNED')); this.addTimeline(ticket, 'ASSIGNED', '已分配处理人', 'USER', actor.userId); return this.ok(ticket as T, 200, headers, ticket.lockVersion) }
    if (action === 'accept' && method === 'POST') { ticket.status = 'IN_PROGRESS'; ticket.lockVersion += 1; ticket.updatedAt = now(); const status = this.sla.get(id); const first = status?.clocks.find(c => c.clockType === 'FIRST_RESPONSE'); if (first) { first.status = 'SATISFIED_ON_TIME'; first.milestoneAt = now(); first.satisfiedAt = first.milestoneAt; first.lockVersion += 1 } this.addTimeline(ticket, 'ACCEPTED', '已接单并开始处理', 'USER', actor.userId); return this.ok(ticket as T, 200, headers, ticket.lockVersion) }
    if (action === 'transfer' && method === 'POST') { const body = this.body(options); ticket.assigneeUserId = body.newAssigneeUserId; ticket.status = 'ASSIGNED'; ticket.lockVersion += 1; ticket.updatedAt = now(); this.addTimeline(ticket, 'TRANSFERRED', body.reason, 'USER', actor.userId); return this.ok(ticket as T, 200, headers, ticket.lockVersion) }
    if (action === 'resolve' && method === 'POST') { ticket.status = 'RESOLVED'; ticket.resolvedAt ||= now(); ticket.lockVersion += 1; ticket.updatedAt = now(); const status = this.sla.get(id); const resolution = status?.clocks.find(c => c.clockType === 'RESOLUTION'); if (resolution) { resolution.status = 'SATISFIED_ON_TIME'; resolution.milestoneAt = now(); resolution.satisfiedAt = resolution.milestoneAt; resolution.lockVersion += 1 } this.addTimeline(ticket, 'RESOLVED', this.body(options).resolutionSummary, 'USER', actor.userId); return this.ok(ticket as T, 200, headers, ticket.lockVersion) }
    if (action === 'close' && method === 'POST') { ticket.status = 'CLOSED'; ticket.closedAt = now(); ticket.lockVersion += 1; ticket.updatedAt = now(); this.addTimeline(ticket, 'CLOSED', this.body(options).reason || '申请人确认关闭', 'USER', actor.userId); return this.ok(ticket as T, 200, headers, ticket.lockVersion) }
    if (action === 'reopen' && method === 'POST') {
      if (ticket.status !== 'RESOLVED' || ticket.requesterUserId !== actor.userId) this.error(409, 'STATE_CONFLICT', headers)
      ticket.status = 'IN_PROGRESS'; ticket.lastReopenedAt = now(); ticket.reopenCount += 1; ticket.lockVersion += 1; ticket.updatedAt = now()
      const status = this.sla.get(id), previous = status?.clocks.find(c => c.clockType === 'RESOLUTION')
      if (status && previous) {
        const deadline = new Date(Date.parse(ticket.lastReopenedAt) + previous.policyDurationSeconds * 1000).toISOString()
        status.historyClocks = [...(status.historyClocks ?? []), ...status.clocks]
        status.clocks = [{ ...previous, slaClockId: uuid(), cycleNo: ticket.reopenCount, status: 'RUNNING',
          startedAt: ticket.lastReopenedAt, deadlineAt: deadline, milestoneAt: null, breachedAt: null, satisfiedAt: null, triggers: [], lockVersion: 0 }]
      }
      if (ticket.assigneeUserId) this.notifications.push(this.notification('工单已重新打开', `${ticket.ticketNo} · ${ticket.subject}，请继续处理。`, 'TICKET', ticket.assigneeUserId, 'UNREAD', 'TICKET', ticket.ticketId, 'TICKET_REOPENED'))
      this.addTimeline(ticket, 'REOPENED', this.body(options).reason, 'USER', actor.userId)
      return this.ok(ticket as T, 200, headers, ticket.lockVersion)
    }
    this.error(409, 'STATE_CONFLICT', headers)
  }

  private async workflowDispatch<T>(id: string, action: string, method: string, params: URLSearchParams, options: MockOptions, headers: Headers): Promise<MockResult<T>> {
    const definition = this.workflowDefinitions.find(d => d.definitionId === id); if (!definition) this.error(404, 'RESOURCE_NOT_FOUND', headers)
    if (!action && method === 'GET') return this.ok(definition as T, 200, headers, definition.lockVersion)
    if (action === 'versions' && method === 'GET') return this.list(this.versions.filter(v => v.definitionId === id), params, headers, params.get('sort') || '-versionNo') as MockResult<T>
    if (action === 'drafts/current' && method === 'GET') { const draft = this.drafts.get(id); if (!draft) this.error(404, 'RESOURCE_NOT_FOUND', headers); return this.ok(draft as T, 200, headers, draft.lockVersion) }
    if (action === 'drafts' && method === 'POST') { const body = this.body(options); this.requireDistinctApprovers(body, headers); const draft: WorkflowDraft = { draftId: uuid(), definitionId: id, status: 'EDITABLE', condition: body.condition, approvalLevelCount: 2, level1ApproverUserId: body.level1ApproverUserId, level2ApproverUserId: body.level2ApproverUserId, validationState: 'UNVALIDATED', validationDigest: null, validatedAt: null, consumedAt: null, lockVersion: 0, createdAt: now(), updatedAt: now() }; this.drafts.set(id, draft); return this.ok(draft as T, 201, headers, draft.lockVersion) }
    this.error(404, 'RESOURCE_NOT_FOUND', headers)
  }

  private async draftDispatch<T>(id: string, action: string, method: string, options: MockOptions, headers: Headers): Promise<MockResult<T>> {
    const draft = [...this.drafts.values()].find(d => d.draftId === id); if (!draft) this.error(404, 'RESOURCE_NOT_FOUND', headers)
    if (method === 'GET' && !action) return this.ok(draft as T, 200, headers, draft.lockVersion)
    this.checkEtag(draft, options, headers)
    if (method === 'PATCH' && !action) { this.requireDistinctApprovers({ ...draft, ...this.body(options) }, headers); Object.assign(draft, this.body(options), { validationState: 'UNVALIDATED', validationDigest: null, lockVersion: draft.lockVersion + 1, updatedAt: now() }); return this.ok(draft as T, 200, headers, draft.lockVersion) }
    if (method === 'POST' && action === 'validate') { const validation: WorkflowValidation = { draftId: draft.draftId, valid: draft.level1ApproverUserId !== draft.level2ApproverUserId && draft.condition?.fieldCode === 'SERVICE_RISK_LEVEL' && ['NORMAL', 'HIGH'].includes(draft.condition?.value), validationDigest: 'c'.repeat(64), violations: [], validatedAt: now(), lockVersion: draft.lockVersion + 1 }; draft.validationState = validation.valid ? 'VALID' : 'UNVALIDATED'; draft.validationDigest = validation.valid ? validation.validationDigest : null; draft.validatedAt = validation.validatedAt; draft.lockVersion += 1; return this.ok(validation as T, 200, headers, draft.lockVersion) }
    if (method === 'POST' && action === 'publish') { this.requireDistinctApprovers(draft, headers); if (draft.validationState !== 'VALID') this.error(409, 'WORKFLOW_DRAFT_INVALID', headers); const base = this.versions[0]; if (!base) this.error(500, 'INTERNAL_ERROR', headers); const version: WorkflowVersion = { ...base, workflowVersionId: uuid(), versionNo: this.versions.length + 1, condition: draft.condition, level1ApproverUserId: draft.level1ApproverUserId, level2ApproverUserId: draft.level2ApproverUserId, publishedAt: now() }; this.versions.push(version); draft.status = 'CONSUMED'; draft.consumedAt = now(); draft.lockVersion += 1; return this.ok(version as T, 201, headers, draft.lockVersion) }
    this.error(409, 'STATE_CONFLICT', headers)
  }

  private async handoffDispatch<T>(id: string, method: string, options: MockOptions, headers: Headers): Promise<MockResult<T>> {
    const actor = this.requireUser(headers), instance = this.instances.find(i => i.workflowInstanceId === id)
    if (!instance || (!actor.roles.includes('SERVICE_DESK_ADMIN') && !this.visibleTasks().some(t => t.workflowInstanceId === id))) this.error(404, 'RESOURCE_NOT_FOUND', headers)
    const history = this.approverChanges.get(id) ?? []
    if (method === 'GET') return this.ok([...history].reverse() as T, 200, headers)
    if (!actor.permissions.includes('APPROVAL_REASSIGN_TENANT')) this.error(403, 'PERMISSION_DENIED', headers)
    const body = this.body(options)
    const idempotencyKey = options.headers?.['Idempotency-Key']; if (!idempotencyKey) this.error(400, 'IDEMPOTENCY_KEY_REQUIRED', headers)
    const key = `${actor.userId}:${id}:${idempotencyKey}`
    const previousRequest = history.find(c => c.changeId === this.idempotency.get(key))
    if (previousRequest) {
      if (previousRequest.levelNo !== body.levelNo || previousRequest.approverUserId !== body.approverUserId || previousRequest.reason !== body.reason) this.error(409, 'IDEMPOTENCY_KEY_REUSED', headers)
      headers.set('Idempotency-Replayed', 'true'); return this.ok(instance as T, 200, headers, instance.lockVersion)
    }
    this.checkEtag(instance, options, headers)
    if (instance.status !== 'RUNNING' || !instance.currentLevel || body.levelNo < instance.currentLevel || ![1, 2].includes(body.levelNo)) this.error(409, 'APPROVAL_HANDOFF_NOT_ALLOWED', headers)
    const candidate = this.users.find(u => u.userId === body.approverUserId && u.status === 'ACTIVE' && u.roleCodes.includes('APPROVER'))
    if (!candidate || !String(body.reason ?? '').trim()) this.error(422, 'WORKFLOW_APPROVER_NOT_ELIGIBLE', headers)
    const version = this.versions.find(v => v.workflowVersionId === instance.workflowVersionRef.workflowVersionId)
    const assigned = (level: number) => this.tasks.find(t => t.workflowInstanceId === id && t.levelNo === level)?.approverUserId
      ?? this.approverOverrides.get(`${id}:${level}`) ?? (level === 1 ? version?.level1ApproverUserId : version?.level2ApproverUserId)
    const previous = assigned(body.levelNo)
    if (candidate.userId === assigned(body.levelNo === 1 ? 2 : 1)) this.error(422, 'APPROVERS_MUST_DIFFER', headers)
    if (candidate.userId === previous) this.error(422, 'APPROVER_UNCHANGED', headers)
    const task = this.tasks.find(t => t.workflowInstanceId === id && t.levelNo === body.levelNo && t.status === 'PENDING')
    this.approverOverrides.set(`${id}:${body.levelNo}`, candidate.userId)
    const changeId = uuid()
    history.push({ changeId, levelNo: body.levelNo, previousApproverUserId: previous ?? '', approverUserId: candidate.userId, changedByUserId: actor.userId, reason: body.reason, changedAt: now() })
    this.approverChanges.set(id, history); this.idempotency.set(key, changeId)
    if (task) {
      task.approverUserId = candidate.userId; task.lockVersion++; task.updatedAt = now()
      this.notifications.push(this.notification('审批交接待办', '一项审批任务已交接给你，请查看申请内容后办理。', 'WORKFLOW', candidate.userId, 'UNREAD', 'APPROVAL_TASK', task.approvalTaskId, 'APPROVAL_TASK_REASSIGNED'))
    }
    instance.lockVersion++
    return this.ok(instance as T, 200, headers, instance.lockVersion)
  }

  private visibleTasks(): ApprovalTask[] { return this.currentUser?.roles.includes('SERVICE_DESK_ADMIN') ? this.tasks : this.tasks.filter(t => t.approverUserId === this.currentUser?.userId) }

  private async taskDispatch<T>(id: string, action: string, method: string, options: MockOptions, headers: Headers): Promise<MockResult<T>> {
    const task = this.tasks.find(t => t.approvalTaskId === id); if (!task || (!this.currentUser?.roles.includes('SERVICE_DESK_ADMIN') && task.approverUserId !== this.currentUser?.userId)) this.error(404, 'APPROVAL_TASK_NOT_ASSIGNED', headers)
    if (!action && method === 'GET') return this.ok(task as T, 200, headers, task.lockVersion)
    if (method !== 'POST') this.error(409, 'STATE_CONFLICT', headers)
    this.checkEtag(task, options, headers); if (task.status !== 'PENDING') this.error(409, 'APPROVAL_TASK_NOT_PENDING', headers)
    const body = this.body(options); task.status = action === 'approve' ? 'APPROVED' : 'REJECTED'; task.decisionType = action === 'approve' ? 'APPROVE' : 'REJECT'; task.decisionReason = body.reason ?? null; task.decidedAt = now(); task.lockVersion += 1; task.updatedAt = now()
    const instance = this.instances.find(i => i.workflowInstanceId === task.workflowInstanceId); if (instance) {
      const ticket = this.tickets.find(t => t.ticketId === instance.ticketId)
      if (action === 'reject') { instance.status = 'COMPLETED_REJECTED'; instance.currentLevel = null; instance.completedAt = now(); instance.lockVersion += 1; if (ticket) { ticket.status = 'APPROVAL_REJECTED'; ticket.lockVersion += 1; ticket.updatedAt = now(); this.addTimeline(ticket, 'APPROVAL_REJECTED', body.reason || '审批被拒绝', 'USER', this.currentUser?.userId || null) } }
      else if (task.levelNo === 1) { instance.currentLevel = 2; instance.lockVersion += 1; const next: ApprovalTask = { approvalTaskId: uuid(), workflowInstanceId: instance.workflowInstanceId, levelNo: 2, approverUserId: this.approverOverrides.get(instance.workflowInstanceId + ':2') ?? this.versions.find(v => v.workflowVersionId === instance.workflowVersionRef.workflowVersionId)!.level2ApproverUserId, status: 'PENDING', decisionType: null, decisionReason: null, decidedAt: null, lockVersion: 0, createdAt: now(), updatedAt: now() }; this.tasks.push(next); this.notifications.push(this.notification('有新的审批待办', `${ticket?.ticketNo ?? ''} · ${ticket?.subject ?? '申请'}，请查看申请内容后办理。`, 'WORKFLOW', next.approverUserId, 'UNREAD', 'APPROVAL_TASK', next.approvalTaskId, 'APPROVAL_TASK_CREATED')) }
      else { instance.status = 'COMPLETED_PASSED'; instance.currentLevel = null; instance.completedAt = now(); instance.lockVersion += 1; if (ticket) { ticket.status = 'PENDING_ASSIGNMENT'; ticket.firstProcessableAt = now(); ticket.lockVersion += 1; ticket.updatedAt = now(); this.createSla(ticket); this.addTimeline(ticket, 'APPROVAL_PASSED', '两级审批已通过，进入待分配', 'USER', this.currentUser?.userId || null) } }
    }
    return this.ok(task as T, 200, headers, task.lockVersion)
  }

  /** Test-only bridge used by the mock Playwright profile; never rendered in the UI. */
  triggerAlert(eventType: 'FIRING' | 'RESOLVED' = 'FIRING'): void {
    const timestamp = now()
    const fingerprint = 'flowdesk-payment-api'
    const cycleRef = 'payment-api-cycle-1'
    const incident = this.incidents.find(i => i.fingerprint === fingerprint && i.status === 'ACTIVE')
    const event: AlertEvent = {
      alertEventId: uuid(),
      integrationRef: { integrationId: '50000000-0000-4000-8000-000000000001', integrationCode: 'PRODUCTION_MONITORING', integrationName: '生产监控平台' },
      externalEventId: uuid(), eventType, fingerprint, cycleRef,
      occurredAt: timestamp, receivedAt: timestamp, disposition: 'APPLIED',
      incidentId: incident?.incidentId || null, rawBodySha256: 'd'.repeat(64),
      summary: eventType === 'FIRING' ? '支付接口响应异常' : '支付接口已恢复', correlationId: uuid()
    }
    if (eventType === 'FIRING' && !incident) {
      const newIncident: Incident = {
        incidentId: uuid(), fingerprint, generationNo: 1, status: 'ACTIVE',
        eventCount: 1, openedAt: timestamp, lastEventAt: timestamp,
        recoveredAt: null, summary: event.summary, lockVersion: 0,
        createdAt: timestamp, updatedAt: timestamp
      }
      this.incidents.push(newIncident)
      event.incidentId = newIncident.incidentId
      const ticket: Ticket = {
        ticketId: uuid(), ticketNo: `FD-${String(this.tickets.length + 1).padStart(6, '0')}`,
        originType: 'INCIDENT', catalogItemId: null, requesterUserId: null,
        previousAttemptTicketId: null, subject: '监控告警：支付接口异常',
        description: '由生产监控平台触发的告警工单', status: 'PENDING_ASSIGNMENT',
        assigneeUserId: null, workflowRef: null, firstProcessableAt: timestamp,
        resolvedAt: null, closedAt: null, lastReopenedAt: null, reopenCount: 0,
        lockVersion: 0, createdAt: timestamp, updatedAt: timestamp
      }
      this.tickets.push(ticket)
      this.createSla(ticket)
      this.links.set(newIncident.incidentId, {
        incidentId: newIncident.incidentId, status: 'ESTABLISHED',
        ticketId: ticket.ticketId, requestedAt: timestamp, establishedAt: timestamp,
        recoveryRequiredAt: null, recoveryReasonCode: null, retryCount: 0,
        lastAttemptAt: timestamp, lockVersion: 0, createdAt: timestamp, updatedAt: timestamp
      })
      this.addTimeline(ticket, 'INCIDENT_CREATED', '告警工单已建立', 'SERVICE', null, 'INCIDENT', newIncident.incidentId)
    } else if (incident && eventType === 'RESOLVED') {
      incident.status = 'RECOVERED'
      incident.recoveredAt = timestamp
      incident.lastEventAt = timestamp
      incident.eventCount += 1
      incident.lockVersion += 1
      event.incidentId = incident.incidentId
      const link = this.links.get(incident.incidentId)
      if (link) {
        link.lockVersion += 1
        link.updatedAt = timestamp
        if (link.ticketId) {
          const ticket = this.tickets.find(t => t.ticketId === link.ticketId)
          if (ticket) this.addTimeline(ticket, 'INCIDENT_RECOVERED', '关联告警已恢复', 'SERVICE', null, 'INCIDENT', incident.incidentId)
        }
      }
    }
    this.events.push(event)
  }
}

export const mockApi = new MockApi()
