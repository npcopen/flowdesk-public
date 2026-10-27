import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { ApiClientError } from '@/api/errors'

const mocks = vi.hoisted(() => ({
  permissions: new Set<string>(),
  ticket: { ticket: vi.fn(), comments: vi.fn(), timeline: vi.fn(), slaStatus: vi.fn(), workflowStart: vi.fn(), transfer: vi.fn() },
  candidates: vi.fn(),
  users: vi.fn(),
}))
vi.mock('@/api/endpoints', () => ({ ticketApi: mocks.ticket, iamApi: { ticketAssigneeCandidates: mocks.candidates, users: mocks.users }, alertApi: {} }))
vi.mock('@/stores/auth', () => ({ useAuthStore: () => ({
  user: { userId: 'agent-1' },
  hasRole: () => false,
  can: (permission: string) => mocks.permissions.has(permission),
}) }))
vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { id: 'ticket-1' } }),
  useRouter: () => ({ back: vi.fn() }),
  RouterLink: { template: '<a><slot /></a>', props: ['to'] },
}))

import TicketDetailPage from './TicketDetailPage.vue'

const ticket = { ticketId: 'ticket-1', subject: '需要同事接手', description: '处理中的服务请求', originType: 'MANUAL', requesterUserId: 'requester-1', assigneeUserId: 'agent-1', status: 'IN_PROGRESS', lockVersion: 3, workflowRef: null }
/**
 * 构造单资源 API 测试结果。
 * 附带当前资源 ETag 和基础元数据，供页面测试验证并发条件及详情读取。
 */
const result = (data: unknown) => ({ data, headers: new Headers({ ETag: '"lv:3"' }), meta: {} })
/**
 * 构造分页集合的 API 测试结果。
 * 保留条目及下一页游标结构，供列表加载与分页行为断言使用。
 */
const list = (items: unknown[], nextPageToken: string | null = null) => ({ items, meta: { page: { nextPageToken } } })

beforeEach(() => {
  vi.resetAllMocks()
  mocks.permissions.clear()
  mocks.permissions.add('TICKET_READ_ASSIGNED')
  mocks.permissions.add('TICKET_TRANSFER_ASSIGNED')
  mocks.ticket.ticket.mockResolvedValue(result(ticket))
  mocks.ticket.comments.mockResolvedValue(list([]))
  mocks.ticket.timeline.mockResolvedValue(list([]))
  mocks.ticket.slaStatus.mockRejectedValue(new ApiClientError({ status: 404, code: 'SLA_NOT_STARTED' }))
  mocks.ticket.workflowStart.mockRejectedValue(new ApiClientError({ status: 404, code: 'RESOURCE_NOT_FOUND' }))
  mocks.candidates.mockResolvedValueOnce(list([{ userId: 'agent-1', username: 'agent1', displayName: '当前处理人' }], 'page-2'))
    .mockResolvedValueOnce(list([{ userId: 'agent-2', username: 'agent2', displayName: '接替同事' }]))
})

describe('assigned agent transfer', () => {
  it('lets an ordinary agent select a candidate from a later page and transfer their ticket', async () => {
    mocks.ticket.ticket.mockResolvedValueOnce(result(ticket))
      .mockResolvedValue(result({ ...ticket, assigneeUserId: 'agent-2', status: 'ASSIGNED', lockVersion: 4 }))
    mocks.ticket.transfer.mockResolvedValue(result({ ...ticket, assigneeUserId: 'agent-2', status: 'ASSIGNED', lockVersion: 4 }))
    const wrapper = mount(TicketDetailPage)
    await flushPromises()
    expect(mocks.users).not.toHaveBeenCalled()
    expect(mocks.candidates).toHaveBeenNthCalledWith(2, { pageSize: 100, pageToken: 'page-2', sort: 'displayName' })
    await wrapper.findAll('.action-buttons button').find(button => button.text() === '转派')!.trigger('click')
    await wrapper.get('#action-assignee').setValue('agent-2')
    await wrapper.get('#action-reason').setValue('交由同事继续处理')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mocks.ticket.transfer).toHaveBeenCalledWith('ticket-1', { newAssigneeUserId: 'agent-2', reason: '交由同事继续处理' }, '"lv:3"')
    wrapper.unmount()
  })

  it('does not query candidates or offer transfer without transfer permission', async () => {
    mocks.permissions.delete('TICKET_TRANSFER_ASSIGNED')
    const wrapper = mount(TicketDetailPage)
    await flushPromises()
    expect(mocks.candidates).not.toHaveBeenCalled()
    expect(wrapper.findAll('.action-buttons button').some(button => button.text() === '转派')).toBe(false)
    wrapper.unmount()
  })
})
