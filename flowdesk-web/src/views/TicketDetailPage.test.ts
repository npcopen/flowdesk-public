import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { ApiClientError } from '@/api/errors'

const mocks = vi.hoisted(() => ({
  ticket: {
    ticket: vi.fn(), comments: vi.fn(), timeline: vi.fn(),
    slaStatus: vi.fn(), workflowStart: vi.fn(), addComment: vi.fn(),
  },
  alert: { incidents: vi.fn(), ticketLink: vi.fn() },
  candidates: vi.fn(),
}))

vi.mock('@/api/endpoints', () => ({
  ticketApi: mocks.ticket,
  iamApi: { ticketAssigneeCandidates: mocks.candidates },
  alertApi: mocks.alert,
}))
vi.mock('@/stores/auth', () => ({ useAuthStore: () => ({
  user: { userId: 'agent-1' },
  hasRole: () => false,
  can: (permission: string) => ['TICKET_READ_ASSIGNED', 'TICKET_COMMENT_ASSIGNED'].includes(permission),
}) }))
vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { id: 'ticket-1' } }),
  useRouter: () => ({ back: vi.fn() }),
  RouterLink: { template: '<a><slot /></a>', props: ['to'] },
}))

import TicketDetailPage from './TicketDetailPage.vue'

const ticket = {
  ticketId: 'ticket-1', ticketNo: 'FD-001', subject: '处理中的工单',
  description: '需要保留的工单正文', originType: 'MANUAL', requesterUserId: 'requester-1',
  assigneeUserId: 'agent-1', status: 'IN_PROGRESS', lockVersion: 3,
  reopenCount: 0, workflowRef: null,
}
/**
 * 构造单资源 API 测试结果。
 * 附带当前资源 ETag 和基础元数据，供页面测试验证并发条件及详情读取。
 */
const result = (data: unknown) => ({ data, headers: new Headers({ ETag: '"lv:3"' }), meta: {} })
/**
 * 构造分页集合的 API 测试结果。
 * 保留条目及下一页游标结构，供列表加载与分页行为断言使用。
 */
const list = (items: unknown[]) => ({ items, meta: { page: { nextPageToken: null } } })
const timelineEntry = {
  timelineId: 'timeline-1', eventType: 'TICKET_ACCEPTED', summary: '工程师已接单',
  occurredAt: '2026-10-22T08:00:00Z', relatedRefType: null, relatedRefId: null,
}

beforeEach(() => {
  vi.resetAllMocks()
  mocks.ticket.ticket.mockResolvedValue(result(ticket))
  mocks.ticket.comments.mockResolvedValue(list([]))
  mocks.ticket.timeline.mockResolvedValue(list([timelineEntry]))
  mocks.ticket.slaStatus.mockRejectedValue(new ApiClientError({ status: 404, code: 'SLA_NOT_STARTED' }))
  mocks.ticket.workflowStart.mockRejectedValue(new ApiClientError({ status: 404, code: 'RESOURCE_NOT_FOUND' }))
  mocks.ticket.addComment.mockResolvedValue(result({ commentId: 'comment-1' }))
})

describe('ticket detail state across panels and refreshes', () => {
  it('preserves the comment intent after an uncertain write and resets it only after success', async () => {
    mocks.ticket.addComment.mockRejectedValueOnce(new TypeError('Response lost after commit'))
    const wrapper = mount(TicketDetailPage)
    await flushPromises()
    await wrapper.get('[aria-label="评论内容"]').setValue('  补充处理进展  ')
    await wrapper.get('.comment-form').trigger('submit')
    await flushPromises()

    expect(mocks.ticket.addComment).toHaveBeenCalledTimes(1)
    const firstKey = mocks.ticket.addComment.mock.calls[0][2]
    expect(firstKey).toEqual(expect.any(String))
    expect(mocks.ticket.addComment.mock.calls[0].slice(0, 2))
      .toEqual(['ticket-1', { body: '补充处理进展' }])
    expect((wrapper.get('[aria-label="评论内容"]').element as HTMLTextAreaElement).value)
      .toBe('  补充处理进展  ')

    // Retrying the detail read also unmounts and remounts the discussion panel.
    await wrapper.get('[role="alert"] button').trigger('click')
    await flushPromises()
    await wrapper.get('.comment-form').trigger('submit')
    await flushPromises()
    expect(mocks.ticket.addComment.mock.calls[1][2]).toBe(firstKey)
    expect((wrapper.get('[aria-label="评论内容"]').element as HTMLTextAreaElement).value).toBe('')

    await wrapper.get('[aria-label="评论内容"]').setValue('补充处理进展')
    await wrapper.get('.comment-form').trigger('submit')
    await flushPromises()
    expect(mocks.ticket.addComment.mock.calls[2][2]).not.toBe(firstKey)
    wrapper.unmount()
  })

  it('keeps a draft through refresh while comment errors stay separate from the ticket and timeline', async () => {
    mocks.ticket.comments.mockRejectedValueOnce(new ApiClientError({ status: 503, code: 'SERVICE_UNAVAILABLE' }))
    const wrapper = mount(TicketDetailPage)
    await flushPromises()
    expect(wrapper.get('h1').text()).toBe(ticket.subject)
    expect(wrapper.get('.timeline-list').text()).toContain(timelineEntry.summary)
    expect(wrapper.findAll('[role="alert"]')).toHaveLength(1)
    expect(wrapper.text()).toContain('处理时效尚未开始')
    await wrapper.get('[aria-label="评论内容"]').setValue('尚未提交的草稿')

    await wrapper.get('.heading-actions button').trigger('click')
    await flushPromises()
    expect(mocks.ticket.ticket).toHaveBeenCalledTimes(2)
    expect((wrapper.get('[aria-label="评论内容"]').element as HTMLTextAreaElement).value)
      .toBe('尚未提交的草稿')
    expect(wrapper.findAll('[role="alert"]')).toHaveLength(0)
    expect(wrapper.get('.timeline-list').text()).toContain(timelineEntry.summary)
    expect(mocks.candidates).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('uses the immutable incident reference and confines alert failures to the association panel', async () => {
    mocks.ticket.ticket.mockResolvedValue(result({ ...ticket, originType: 'INCIDENT' }))
    mocks.ticket.timeline.mockResolvedValue(list([
      { ...timelineEntry, relatedRefType: 'INCIDENT', relatedRefId: 'incident-1' },
    ]))
    mocks.alert.ticketLink.mockRejectedValue(new ApiClientError({ status: 503, code: 'SERVICE_UNAVAILABLE' }))
    const wrapper = mount(TicketDetailPage)
    await flushPromises()
    expect(mocks.alert.ticketLink).toHaveBeenCalledWith('incident-1')
    expect(mocks.alert.incidents).not.toHaveBeenCalled()
    const associationPanel = wrapper.findAll('section.panel')
      .find(panel => panel.find('h2').text() === '关联告警')!
    expect(associationPanel.find('[role="alert"]').exists()).toBe(true)
    expect(wrapper.findAll('[role="alert"]')).toHaveLength(1)
    expect(wrapper.get('h1').text()).toBe(ticket.subject)
    expect(wrapper.get('.timeline-list').text()).toContain(timelineEntry.summary)
    expect(wrapper.find('.comment-form').exists()).toBe(true)
    wrapper.unmount()
  })
})
