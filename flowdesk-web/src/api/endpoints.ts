/** 按业务域封装 API 路径与请求类型，让页面共用分页、幂等和条件更新约定。 */
import { apiClient, newIdempotencyKey, type RequestOptions, type RequestResult } from './client'
import { FeatureUnavailableError } from './errors'
import { featureFlags } from './features'
import type {
  AccessBinding, AlertEvent, ApprovalTask, AuthSession, CurrentUser, Incident,
  IncidentTicketLink, ListResult, MessageRecovery, Notification, Permission, Role,
  ServiceCatalogItem, SlaPolicySummary, SlaStatus, SuccessEnvelope, Ticket,
  TicketComment, TimelineEntry, UserIdentity, UserSummary, WorkflowDefinition, WorkflowDraft,
  WorkflowInstance, WorkflowStartStatus, WorkflowValidation, WorkflowVersion
} from '@/types/api'

type Query = Record<string, string | number | boolean | null | undefined>

/**
 * 把通用 API 响应转换为列表结果。
 * 保留分页元数据和 ETag，供页面继续翻页及执行有版本条件的更新。
 */
function asList<T>(result: RequestResult<T[]>): ListResult<T> {
  return { items: result.data ?? [], meta: result.meta, etag: result.headers.get('ETag') ?? undefined }
}

/**
 * 读取指定路径的分页集合并转换为统一列表结构。
 * 筛选条件及游标通过查询参数传递。
 */
async function list<T>(path: string, query?: Query): Promise<ListResult<T>> {
  return asList(await apiClient.get<T[]>(path, { query }))
}

/**
 * 选择调用方提供的幂等键，缺失时生成新键。
 * 需要安全重试的页面应主动传入并复用同一次业务意图的键。
 */
function mutationKey(key?: string): string {
  return key || newIdempotencyKey()
}

// 可选能力被禁用时显式失败；仅在配置为 mock 时才切换到内存传输。
/**
 * 按可选能力的启用模式选择真实或模拟请求。
 * 能力关闭时明确抛出不可用异常，避免页面误认为返回了空数据。
 */
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

/**
 * 读取受能力开关控制的分页列表。
 * 先经过能力模式判断，再统一转换列表数据与分页元数据。
 */
async function gatedList<T>(feature: 'notification' | 'recovery', mode: string, path: string, query?: Query) {
  const result = await featureRequest<T[]>(feature, mode, path, { method: 'GET', query })
  return asList(result)
}

export const authApi = {
  /**
   * 向登录端点提交组织、用户名和密码。
   * 此请求不附带现有会话令牌，成功后由认证仓库建立新会话。
   */
  login(body: { tenantCode: string; username: string; password: string }) {
    return apiClient.post<AuthSession>('/auth/login', body, { auth: false })
  },
  /**
   * 读取当前令牌对应的用户、角色和权限。
   * 调用方用此结果确认服务端认可的身份与授权范围。
   */
  me() {
    return apiClient.get<CurrentUser>('/users/me')
  }
}

export const iamApi = {
  /**
   * 分页获取可接收工单的候选人员。
   * 后端按当前操作者权限和可用角色筛选，页面无需读取完整用户管理数据。
   */
  ticketAssigneeCandidates(query?: Query) { return list<UserIdentity>('/users/ticket-assignee-candidates', query) },
  /**
   * 按用户标识读取展示所需的最小身份信息。
   * 用于业务页面显示人员名称，避免依赖完整用户管理权限。
   */
  identity(userId: string) { return apiClient.get<{userId:string;username:string;displayName:string}>(`/users/${userId}/identity`) },
  /**
   * 按筛选条件分页读取用户集合。
   * 适用于用户管理以及有权限的业务人员选择场景。
   */
  users(query?: Query) { return list<UserSummary>('/users', query) },
  /**
   * 读取指定用户的详情及当前版本信息。
   * 后续修改可使用响应 ETag 防止覆盖其他人的更新。
   */
  user(userId: string) { return apiClient.get<UserSummary>(`/users/${userId}`) },
  /**
   * 创建租户用户并携带幂等键。
   * 重复提交同一业务意图时可复用传入的键，避免产生重复用户。
   */
  createUser(body: { username: string; displayName: string; initialPassword: string; status: 'ACTIVE' | 'INACTIVE' }, key?: string) {
    return apiClient.post<UserSummary>('/users', body, { idempotencyKey: mutationKey(key) })
  },
  /**
   * 更新用户的指定字段。
   * 请求同时携带当前 ETag 与幂等键，保护并发编辑和重复提交。
   */
  patchUser(userId: string, body: { displayName?: string; status?: 'ACTIVE' | 'INACTIVE' }, etag: string) {
    return apiClient.patch<UserSummary>(`/users/${userId}`, body, { ifMatch: etag })
  },
  /**
   * 分页读取指定用户的角色绑定。
   * 结果包含绑定状态及版本，供授权界面显示和修改。
   */
  userRoles(userId: string, query?: Query) { return list<AccessBinding>(`/users/${userId}/roles`, query) },
  // 首次创建绑定用 If-None-Match: *，修改已有绑定用 ETag，避免覆盖其他人的授权变更。
  /**
   * 授予或撤销用户与角色的绑定。
   * 新绑定使用不存在条件，已有绑定使用 ETag，并携带幂等键保护重试。
   */
  putUserRole(userId: string, roleId: string, body: { state: 'GRANTED' | 'REVOKED' }, options: { etag?: string; key?: string; create?: boolean } = {}) {
    return apiClient.put<AccessBinding>(`/users/${userId}/roles/${roleId}`, body, {
      ifMatch: options.create ? undefined : options.etag,
      ifNoneMatch: options.create ? '*' : undefined,
      idempotencyKey: mutationKey(options.key)
    })
  },
  /**
   * 分页读取租户角色，可传入状态等筛选条件。
   * 页面据此展示角色管理列表或可选择角色。
   */
  roles(query?: Query) { return list<Role>('/roles', query) },
  /**
   * 读取指定角色详情及版本。
   * 更新角色前可使用返回的 ETag 构造并发保护条件。
   */
  role(roleId: string) { return apiClient.get<Role>(`/roles/${roleId}`) },
  /**
   * 创建租户角色并携带幂等键。
   * 调用方可保留键值用于失败后的同意图重试。
   */
  createRole(body: { roleCode: string; roleName: string; status: 'ACTIVE' | 'INACTIVE' }, key?: string) {
    return apiClient.post<Role>('/roles', body, { idempotencyKey: mutationKey(key) })
  },
  /**
   * 局部更新角色信息。
   * 通过当前 ETag 校验资源版本，并使用幂等键避免重复执行。
   */
  patchRole(roleId: string, body: { roleName?: string; status?: 'ACTIVE' | 'INACTIVE' }, etag: string) {
    return apiClient.patch<Role>(`/roles/${roleId}`, body, { ifMatch: etag })
  },
  /**
   * 分页读取权限定义。
   * 用于权限展示和角色授权选择，具体可访问范围由服务端校验。
   */
  permissions(query?: Query) { return list<Permission>('/permissions', query) },
  /**
   * 分页读取角色的权限绑定。
   * 保留绑定的授予状态及版本，便于逐项授权或撤销。
   */
  rolePermissions(roleId: string, query?: Query) { return list<AccessBinding>(`/roles/${roleId}/permissions`, query) },
  /**
   * 授予或撤销角色与权限的绑定。
   * 根据绑定是否存在选择创建条件或版本条件，并附带幂等键。
   */
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
  /**
   * 创建服务目录项并携带幂等键。
   * 提交可申请状态、审批配置与 SLA 策略等业务配置。
   */
  create(body: CatalogWrite, key: string) { return apiClient.post<ServiceCatalogItem>('/service-catalog-items',body,{idempotencyKey:key}) },
  /**
   * 替换服务目录项的完整业务配置。
   * 携带现有 ETag 和幂等键，确保审批、SLA 等配置在一致版本上更新。
   */
  configure(id: string, body: CatalogWrite, etag: string) { return apiClient.put<ServiceCatalogItem>(`/service-catalog-items/${id}/configuration`,body,{ifMatch:etag}) },
  /**
   * 分页读取服务目录项。
   * 查询条件用于区分管理列表和申请工单时可用的目录。
   */
  items(query?: Query) { return list<ServiceCatalogItem>('/service-catalog-items', query) },
  /**
   * 读取单个服务目录项及其版本信息。
   * 页面可据此展示配置并生成后续更新的并发条件。
   */
  item(id: string) { return apiClient.get<ServiceCatalogItem>(`/service-catalog-items/${id}`) },
  /**
   * 局部修改服务目录项。
   * 使用当前 ETag 防止并发覆盖，并以幂等键保护重复提交。
   */
  patchItem(id: string, body: { itemName?: string; description?: string | null; slaPolicyId?: string; status?: 'ACTIVE' | 'INACTIVE' }, etag: string) {
    return apiClient.patch<ServiceCatalogItem>(`/service-catalog-items/${id}`, body, { ifMatch: etag })
  },
  /**
   * 分页获取可供服务目录选择的 SLA 策略。
   * 筛选条件由调用方传入，结果保持统一列表结构。
   */
  slaPolicies(query?: Query) { return list<SlaPolicySummary>('/sla-policies', query) }
}

// 写操作携带幂等键；工单状态变更同时传入读取时的 ETag，交由服务端检查并发冲突。
export const ticketApi = {
  /**
   * 读取当前权限范围内的工单汇总。
   * 用于仪表盘展示各状态统计和待处理数量。
   */
  summary() { return apiClient.get<{ pendingCount: number }>('/tickets/summary') },
  /**
   * 通过 SLA 时钟标识查询所属工单。
   * 通知跳转可先解析工单，再导航到实际业务详情。
   */
  ticketForSlaClock(id: string) { return apiClient.get<{ ticketId: string }>(`/tickets/by-sla-clock/${id}`) },
  /**
   * 读取审批任务所需的工单上下文。
   * 使用专门的审批视图端点，避免要求审批人具备普通工单详情权限。
   */
  approvalContext(ticketId: string, taskId: string) {
    return apiClient.get<Pick<Ticket, 'ticketId' | 'ticketNo' | 'requesterUserId' | 'subject' | 'description' | 'status' | 'createdAt'> & { catalogName: string | null }>(`/tickets/${ticketId}/approval-context/${taskId}`)
  },
  /**
   * 按状态、范围和游标分页读取工单。
   * 返回的分页元数据用于保持筛选条件下的连续翻页。
   */
  tickets(query?: Query) { return list<Ticket>('/tickets', query) },
  /**
   * 读取指定工单及其当前 ETag。
   * 详情页可直接使用响应版本提交后续状态操作。
   */
  ticket(id: string) { return apiClient.get<Ticket>(`/tickets/${id}`) },
  /**
   * 创建工单并携带幂等键。
   * 提交目录、主题和描述等申请信息，审批启动等异步结果随响应元数据返回。
   */
  create(body: { catalogItemId: string; subject: string; description: string; previousAttemptTicketId?: string | null }, key?: string) {
    return apiClient.post<Ticket>('/tickets', body, { idempotencyKey: mutationKey(key) })
  },
  /**
   * 将工单分派给指定工程师。
   * 附带工单当前 ETag 和幂等键，保护并发状态变更及重试。
   */
  assign(id: string, assigneeUserId: string, etag: string, key?: string) {
    return apiClient.post<Ticket>(`/tickets/${id}/assign`, { assigneeUserId }, { ifMatch: etag, idempotencyKey: mutationKey(key) })
  },
  /**
   * 确认接单并进入处理流程。
   * 提交当前工单版本及幂等键，由服务端校验可接单状态和操作者。
   */
  accept(id: string, etag: string, key?: string) {
    return apiClient.post<Ticket>(`/tickets/${id}/accept`, undefined, { ifMatch: etag, idempotencyKey: mutationKey(key) })
  },
  /**
   * 将工单转派给新工程师并提交原因。
   * 使用当前 ETag 和幂等键，避免旧页面覆盖最新分派结果。
   */
  transfer(id: string, body: { newAssigneeUserId: string; reason: string }, etag: string, key?: string) {
    return apiClient.post<Ticket>(`/tickets/${id}/transfer`, body, { ifMatch: etag, idempotencyKey: mutationKey(key) })
  },
  /**
   * 分页读取指定工单的评论。
   * 调用方可继续按游标加载完整讨论记录。
   */
  comments(id: string, query?: Query) { return list<TicketComment>(`/tickets/${id}/comments`, query) },
  /**
   * 为工单新增评论并携带幂等键。
   * 相同内容的同意图重试可以复用键值，避免重复评论。
   */
  addComment(id: string, body: { body: string }, key?: string) {
    return apiClient.post<TicketComment>(`/tickets/${id}/comments`, body, { idempotencyKey: mutationKey(key) })
  },
  /**
   * 提交工单解决说明并标记已解决。
   * 携带当前版本及幂等键，由服务端执行业务状态校验。
   */
  resolve(id: string, resolutionSummary: string, etag: string, key?: string) {
    return apiClient.post<Ticket>(`/tickets/${id}/resolve`, { resolutionSummary }, { ifMatch: etag, idempotencyKey: mutationKey(key) })
  },
  /**
   * 提交关闭原因并关闭工单。
   * 使用当前 ETag 与幂等键保护状态转换，结果包含最新版本。
   */
  close(id: string, reason: string | null, etag: string, key?: string) {
    return apiClient.post<Ticket>(`/tickets/${id}/close`, { reason }, { ifMatch: etag, idempotencyKey: mutationKey(key) })
  },
  /**
   * 提交原因并重新打开符合条件的工单。
   * 附带当前版本和幂等键，具体权限及可重开状态由服务端判断。
   */
  reopen(id: string, reason: string, etag: string, key?: string) {
    return apiClient.post<Ticket>(`/tickets/${id}/reopen`, { reason }, { ifMatch: etag, idempotencyKey: mutationKey(key) })
  },
  /**
   * 分页读取工单时间线。
   * 记录用于展示状态变化、来源关系以及操作者等审计信息。
   */
  timeline(id: string, query?: Query) { return list<TimelineEntry>(`/tickets/${id}/timeline`, query) },
  /**
   * 按 SLA 能力开关读取工单计时状态。
   * 能力关闭或尚未开始计时时保留明确错误，供页面单独降级显示。
   */
  async slaStatus(id: string) {
    if (featureFlags.sla === 'disabled') throw new FeatureUnavailableError('sla', featureFlags.sla)
    if (featureFlags.sla === 'mock') return apiClient.requestMock<SlaStatus>(`/tickets/${id}/sla-status`, { method: 'GET' })
    return apiClient.get<SlaStatus>(`/tickets/${id}/sla-status`)
  },
  /**
   * 读取工单关联工作流的异步建立状态。
   * 区分已请求、已建立和需要恢复的启动结果。
   */
  workflowStart(id: string) { return apiClient.get<WorkflowStartStatus>(`/tickets/${id}/workflow-start`) }
}

export const workflowApi = {
  /**
   * 读取当前用户可见的审批任务汇总。
   * 用于仪表盘等汇总视图，统计范围由后端授权决定。
   */
  taskSummary() { return apiClient.get<{ pendingCount: number }>('/approval-tasks/summary') },
  /**
   * 分页读取工作流定义集合。
   * 调用方可筛选并选择需要维护草稿或查看发布版本的定义。
   */
  definitions(query?: Query) { return list<WorkflowDefinition>('/workflow-definitions', query) },
  /**
   * 读取工作流定义详情和版本。
   * 供流程管理页面展示定义信息及并发编辑条件。
   */
  definition(id: string) { return apiClient.get<WorkflowDefinition>(`/workflow-definitions/${id}`) },
  /**
   * 为工作流定义创建草稿。
   * 请求携带幂等键，提交条件和两级审批人等草稿配置。
   */
  createDraft(definitionId: string, body: { condition: { fieldCode: string; operator: string; valueType: string; value: string }; level1ApproverUserId: string; level2ApproverUserId: string }, key?: string) {
    return apiClient.post<WorkflowDraft>(`/workflow-definitions/${definitionId}/drafts`, body, { idempotencyKey: mutationKey(key) })
  },
  /**
   * 读取工作流定义当前可编辑草稿。
   * 草稿不存在时保留资源不存在错误，由页面决定创建入口。
   */
  currentDraft(definitionId: string) { return apiClient.get<WorkflowDraft>(`/workflow-definitions/${definitionId}/drafts/current`) },
  /**
   * 按草稿标识读取具体草稿及版本。
   * 返回 ETag 可供修改、校验和发布操作使用。
   */
  draft(id: string) { return apiClient.get<WorkflowDraft>(`/workflow-drafts/${id}`) },
  /**
   * 局部修改已保存草稿。
   * 携带当前 ETag 和幂等键，修改后需按最新版本重新校验。
   */
  patchDraft(id: string, body: { condition: { fieldCode: string; operator: string; valueType: string; value: string }; level1ApproverUserId: string; level2ApproverUserId: string }, etag: string) {
    return apiClient.patch<WorkflowDraft>(`/workflow-drafts/${id}`, body, { ifMatch: etag })
  },
  /**
   * 校验已保存的工作流草稿。
   * 提交当前 ETag，获取违规项、校验摘要及最新版本供发布使用。
   */
  validateDraft(id: string, etag: string) { return apiClient.post<WorkflowValidation>(`/workflow-drafts/${id}/validate`, undefined, { ifMatch: etag }) },
  /**
   * 发布已经校验的草稿。
   * 提交当前版本、校验摘要和幂等键，生成不可变工作流发布版本。
   */
  publishDraft(id: string, etag: string, key?: string) { return apiClient.post<WorkflowVersion>(`/workflow-drafts/${id}/publish`, undefined, { ifMatch: etag, idempotencyKey: mutationKey(key) }) },
  /**
   * 分页读取指定定义的已发布版本。
   * 目录配置可引用固定版本，避免后续草稿修改影响既有规则。
   */
  versions(definitionId: string, query?: Query) { return list<WorkflowVersion>(`/workflow-definitions/${definitionId}/versions`, query) },
  /**
   * 读取指定工作流发布版本。
   * 用于展示已发布条件及审批人配置。
   */
  version(id: string) { return apiClient.get<WorkflowVersion>(`/workflow-versions/${id}`) },
  /**
   * 分页读取当前权限范围内的工作流实例。
   * 可按工单等条件筛选实例运行状态。
   */
  instances(query?: Query) { return list<WorkflowInstance>('/workflow-instances', query) },
  /**
   * 读取单个工作流实例及其 ETag。
   * 实例版本用于审批人交接等需要并发保护的操作。
   */
  instance(id: string) { return apiClient.get<WorkflowInstance>(`/workflow-instances/${id}`) },
  /**
   * 分页读取工作流实例的审批人变更记录。
   * 用于展示交接原因、原审批人与新审批人的审计历史。
   */
  approverChanges(id: string) {
    return apiClient.get<Array<{ changeId: string; levelNo: number; previousApproverUserId: string; approverUserId: string; changedByUserId: string; reason: string; changedAt: string }>>(`/workflow-instances/${id}/approver-changes`)
  },
  /**
   * 变更指定流程级别的审批人。
   * 携带实例当前 ETag 与幂等键，服务端校验交接范围和候选人资格。
   */
  changeApprover(id: string, body: { levelNo: number; approverUserId: string; reason: string }, etag: string, key?: string) {
    return apiClient.post<WorkflowInstance>(`/workflow-instances/${id}/approver-changes`, body, { ifMatch: etag, idempotencyKey: mutationKey(key) })
  },
  /**
   * 按状态、级别和流程等条件分页读取审批任务。
   * 集合范围由当前身份的后端授权决定。
   */
  tasks(query?: Query) { return list<ApprovalTask>('/approval-tasks', query) },
  /**
   * 读取审批任务详情及其当前 ETag。
   * 详情页使用此版本提交同意或拒绝决定。
   */
  task(id: string) { return apiClient.get<ApprovalTask>(`/approval-tasks/${id}`) },
  /**
   * 同意指定审批任务。
   * 提交任务当前 ETag 与幂等键，后续流程推进结果通过响应及异步状态体现。
   */
  approve(id: string, reason: string | null, etag: string, key?: string) { return apiClient.post<ApprovalTask>(`/approval-tasks/${id}/approve`, { reason }, { ifMatch: etag, idempotencyKey: mutationKey(key) }) },
  /**
   * 拒绝指定审批任务并提交原因。
   * 携带当前版本与幂等键，防止重复决定或覆盖已处理任务。
   */
  reject(id: string, reason: string, etag: string, key?: string) { return apiClient.post<ApprovalTask>(`/approval-tasks/${id}/reject`, { reason }, { ifMatch: etag, idempotencyKey: mutationKey(key) }) }
}

export const alertApi = {
  /**
   * 读取活跃告警等汇总统计。
   * 用于仪表盘展示当前告警运行情况。
   */
  summary() { return apiClient.get<{ activeCount: number }>('/incidents/summary') },
  /**
   * 分页读取告警接入事件。
   * 事件包含接收时间、处置结果及关联事件信息，供有权限人员追踪接入过程。
   */
  events(query?: Query) { return list<AlertEvent>('/alert-events', query) },
  /**
   * 读取单个告警接入事件。
   * 用于查看来源、指纹、处置状态和关联事件等诊断信息。
   */
  event(id: string) { return apiClient.get<AlertEvent>(`/alert-events/${id}`) },
  /**
   * 按状态和游标分页读取告警事件聚合。
   * 用于展示活跃告警及其恢复情况。
   */
  incidents(query?: Query) { return list<Incident>('/incidents', query) },
  /**
   * 读取告警事件聚合详情及当前版本。
   * 详情页据此展示事件次数、时间和关联状态。
   */
  incident(id: string) { return apiClient.get<Incident>(`/incidents/${id}`) },
  /**
   * 读取告警事件与工单之间的异步关联状态。
   * 区分等待建立、已建立和需要恢复的关联结果。
   */
  ticketLink(id: string) { return apiClient.get<IncidentTicketLink>(`/incidents/${id}/ticket-link`) }
}

export const notificationApi = {
  /**
   * 按通知能力开关分页读取当前用户的通知。
   * 保留筛选条件及游标，并显式区分能力不可用与空列表。
   */
  list(query?: Query) {
    return gatedList<Notification>('notification', featureFlags.notification, '/notifications', query)
  },
  /**
   * 按通知能力开关读取当前用户未读总数。
   * 用于全局徽标，统计不依赖当前列表页内容。
   */
  unreadCount() {
    return featureRequest<{ unreadCount: number; asOf: string }>('notification', featureFlags.notification, '/notifications/unread-count', { method: 'GET' })
  },
  /**
   * 修改通知的已读或未读状态。
   * 提交当前 ETag 与幂等键，更新后页面可重新读取权威未读总数。
   */
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
  /**
   * 按所属服务分页读取需要恢复的消息记录。
   * 经过恢复能力开关检查，返回该服务独立的分页游标。
   */
  list(owner: keyof typeof recoveryPaths, query?: Query) {
    return gatedList<MessageRecovery>('recovery', featureFlags.recovery, recoveryPaths[owner], query)
  },
  /**
   * 按所属服务读取单条消息恢复记录。
   * 经过恢复能力开关检查，并保留响应版本供人工重放使用。
   */
  get(owner: keyof typeof recoveryPaths, id: string) {
    return featureRequest<MessageRecovery>('recovery', featureFlags.recovery, `${recoveryPaths[owner]}/${id}`, { method: 'GET' })
  },
  /**
   * 向所属服务提交消息恢复重放请求。
   * 携带操作原因、记录当前 ETag 和幂等键，接受请求不代表异步处理已经完成。
   */
  replay(owner: keyof typeof recoveryPaths, id: string, reason: string, etag: string, key?: string) {
    return featureRequest<MessageRecovery>('recovery', featureFlags.recovery, `${recoveryPaths[owner]}/${id}/replays`, {
      method: 'POST', body: { reason }, ifMatch: etag, idempotencyKey: mutationKey(key)
    })
  }
}

export type AnyApiResult<T> = RequestResult<T> | SuccessEnvelope<T>
