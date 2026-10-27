import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

const mocks = vi.hoisted(() => ({ tickets: vi.fn(), tasks: vi.fn(), instances: vi.fn() }))
vi.mock('@/api/endpoints', () => ({
  ticketApi: { tickets: mocks.tickets },
  workflowApi: { tasks: mocks.tasks, instances: mocks.instances },
  iamApi: {}, catalogApi: {},
}))
vi.mock('@/stores/auth', () => ({ useAuthStore: () => ({
  can: (permission: string) => ['TICKET_READ_ASSIGNED', 'APPROVAL_READ_ASSIGNED'].includes(permission),
  canAny: () => true,
  hasRole: () => false,
}) }))
vi.mock('vue-router', () => ({
  useRoute: () => ({ query: {} }), useRouter: () => ({}),
  RouterLink: { template: '<a><slot /></a>', props: ['to'] },
}))

import TicketsPage from './TicketsPage.vue'
import ApprovalsPage from './ApprovalsPage.vue'

beforeEach(() => {
  vi.resetAllMocks()
  const page = { items: [], meta: { page: { nextPageToken: null } } }
  mocks.tickets.mockResolvedValue(page)
  mocks.tasks.mockResolvedValue(page)
  mocks.instances.mockResolvedValue(page)
})

describe('list refresh', () => {
  it.each([
    ['tickets', TicketsPage, mocks.tickets],
    ['approvals', ApprovalsPage, mocks.tasks],
  ] as const)('refreshes %s without sending the click event as a page token', async (_name, page, query) => {
    const wrapper = mount(page)
    await flushPromises()
    query.mockClear()
    await wrapper.findAll('.page-heading button').find(button => button.text() === '刷新')!.trigger('click')
    await flushPromises()
    expect(query).toHaveBeenCalledTimes(1)
    expect(query.mock.calls[0][0].pageToken).toBeUndefined()
    wrapper.unmount()
  })
})
