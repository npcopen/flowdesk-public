import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { ApiClientError } from '@/api/errors'

const mocks = vi.hoisted(() => ({
  ticket: { tickets: vi.fn(), summary: vi.fn(), ticket: vi.fn(), approvalContext: vi.fn(), ticketForSlaClock: vi.fn() },
  workflow: { tasks: vi.fn(), taskSummary: vi.fn(), task: vi.fn(), instance: vi.fn(), approverChanges: vi.fn(), approve: vi.fn(), reject: vi.fn() },
  alert: { incidents: vi.fn(), summary: vi.fn() },
  notification: { list: vi.fn(), unreadCount: vi.fn() },
  identity: vi.fn(),
  push: vi.fn(),
  permissions: new Set<string>(),
}))
vi.mock('@/api/endpoints', () => ({ ticketApi: mocks.ticket, workflowApi: mocks.workflow, alertApi: mocks.alert, notificationApi: mocks.notification, iamApi: { identity: mocks.identity } }))
vi.mock('@/stores/auth', () => ({ useAuthStore: () => ({
  user: { userId: 'approver-1', displayName: '审批人' },
  can: (code: string) => mocks.permissions.has(code),
  canAny: (codes: string[]) => codes.some(code => mocks.permissions.has(code)),
  hasRole: () => false,
}) }))
vi.mock('@/api/features', () => ({ featureFlags: { notification: 'real' }, featureNotice: () => '' }))
vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { id: 'task-1' } }),
  useRouter: () => ({ push: mocks.push, back: vi.fn() }),
  RouterLink: { template: '<a><slot /></a>', props: ['to'] },
}))

import DashboardPage from './DashboardPage.vue'
import ApprovalDetailPage from './ApprovalDetailPage.vue'
import NotificationsPage from './NotificationsPage.vue'

const result = (data: unknown) => ({ data, headers: new Headers({ ETag: '"lv:0"' }), meta: {} })
const list = (items: unknown[] = []) => ({ items, meta: { page: { nextPageToken: null } } })
const task = { approvalTaskId: 'task-1', workflowInstanceId: 'instance-1', approverUserId: 'approver-1', levelNo: 1, status: 'PENDING', lockVersion: 0 }
const context = { ticketId: 'ticket-1', ticketNo: 'FD-001', subject: '生产系统权限申请', description: '需要临时只读权限，使用两小时。', status: 'AWAITING_APPROVAL', catalogName: '数据库访问', requesterUserId: 'requester-1', createdAt: '2026-09-07T08:00:00Z' }

beforeEach(() => {
  vi.resetAllMocks()
  mocks.permissions.clear()
  ;['TICKET_READ_TENANT', 'APPROVAL_READ_ASSIGNED', 'APPROVAL_DECIDE_ASSIGNED', 'INCIDENT_READ_TENANT', 'NOTIFICATION_READ_SELF'].forEach(p => mocks.permissions.add(p))
  mocks.ticket.tickets.mockResolvedValue(list())
  mocks.ticket.summary.mockResolvedValue(result({ pendingCount: 21 }))
  mocks.workflow.tasks.mockResolvedValue(list())
  mocks.workflow.taskSummary.mockResolvedValue(result({ pendingCount: 13 }))
  mocks.alert.incidents.mockResolvedValue(list())
  mocks.alert.summary.mockResolvedValue(result({ activeCount: 11 }))
  mocks.notification.list.mockResolvedValue(list())
  mocks.notification.unreadCount.mockResolvedValue(result({ unreadCount: 3 }))
  mocks.workflow.task.mockResolvedValue(result(task))
  mocks.workflow.instance.mockResolvedValue(result({ workflowInstanceId: 'instance-1', ticketId: 'ticket-1', status: 'RUNNING', currentLevel: 1, conditionResult: true, lockVersion: 0 }))
  mocks.workflow.approverChanges.mockResolvedValue(result([]))
  mocks.ticket.approvalContext.mockResolvedValue(result(context))
  mocks.identity.mockResolvedValue(result({username: "zhangsan", displayName: "张三"}))
})

describe('business scenario regressions', () => {
  it('shows complete scoped counts even when the recent page contains eight closed tickets', async () => {
    mocks.ticket.tickets.mockResolvedValue(list(Array.from({ length: 8 }, (_, i) => ({ ...context, ticketId: `closed-${i}`, status: 'CLOSED' }))))
    const wrapper = mount(DashboardPage)
    await flushPromises()
    expect(wrapper.findAll('.metric-tile strong').map(n => n.text())).toEqual(['21', '13', '11', '3'])
    expect(mocks.ticket.tickets).toHaveBeenCalledWith({ pageSize: 8, sort: '-updatedAt' })
    wrapper.unmount()
  })

  it('does not show a false zero when a summary is unavailable', async () => {
    mocks.ticket.summary.mockRejectedValue(new ApiClientError({ status: 503, code: 'SERVICE_UNAVAILABLE' }))
    const wrapper = mount(DashboardPage)
    await flushPromises()
    expect(wrapper.findAll('.metric-tile strong').map(n => n.text())).toEqual(['—', '13', '11', '3'])
    wrapper.unmount()
  })

  it('loads the assigned application through the dedicated context before enabling decisions', async () => {
    const wrapper = mount(ApprovalDetailPage)
    await flushPromises()
    expect(mocks.ticket.approvalContext).toHaveBeenCalledWith('ticket-1', 'task-1')
    expect(mocks.ticket.ticket).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain(context.description)
    expect(wrapper.text()).toContain("张三（zhangsan）")
    expect(wrapper.findAll('.decision-buttons button').map(n => n.text())).toEqual(['同意', '拒绝'])
    wrapper.unmount()
  })

  it.each([404, 503])('prevents blind approval when application loading returns %s', async status => {
    mocks.ticket.approvalContext.mockRejectedValue(new ApiClientError({ status, code: status === 404 ? 'RESOURCE_NOT_FOUND' : 'SERVICE_UNAVAILABLE' }))
    const wrapper = mount(ApprovalDetailPage)
    await flushPromises()
    expect(wrapper.find('.decision-buttons').exists()).toBe(false)
    expect(wrapper.text()).toContain('暂时不能提交决定')
    expect(mocks.workflow.approve).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it.each([
    ['TICKET', 'TICKET', 'ticket-1', '查看工单', '/tickets/ticket-1'],
    ['TICKET', 'SLA_CLOCK', 'clock-1', '查看工单', '/tickets/ticket-1'],
    ['WORKFLOW', 'APPROVAL_TASK', 'task-1', '查看审批', '/approvals/task-1'],
  ])('opens the related work for %s/%s notifications', async (sourceDomain, sourceRefType, sourceRefId, buttonText, destination) => {
    mocks.notification.list.mockResolvedValue(list([{ notificationId: 'notice-1', title: '待处理事项', body: 'FD-001 · 生产系统权限申请', sourceDomain, sourceRefType, sourceRefId, notificationKind: 'TICKET_UPDATE', state: 'UNREAD', lockVersion: 0 }]))
    mocks.ticket.ticketForSlaClock.mockResolvedValue(result({ ticketId: 'ticket-1' }))
    const wrapper = mount(NotificationsPage)
    await flushPromises()
    await wrapper.get('[aria-label="查看通知详情"]').trigger('click')
    await wrapper.findAll('[role="dialog"] button').find(b => b.text() === buttonText)!.trigger('click')
    await flushPromises()
    expect(mocks.push).toHaveBeenCalledWith(destination)
    if (sourceRefType === 'SLA_CLOCK') expect(mocks.ticket.ticketForSlaClock).toHaveBeenCalledWith('clock-1')
    wrapper.unmount()
  })
})
