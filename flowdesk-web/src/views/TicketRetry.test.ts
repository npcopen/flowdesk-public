import { expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

const state = vi.hoisted(() => ({ push: vi.fn() }))
vi.mock('@/stores/auth', () => ({ useAuthStore: () => ({ user: { userId: 'requester' }, can: () => true, hasRole: () => false }) }))
vi.mock('vue-router', () => ({ useRoute: () => ({ query: { create: '1' } }), useRouter: () => ({ push: state.push }), RouterLink: { template: '<a><slot /></a>', props: ['to'] } }))

it('reuses the same ticket intent after the first successful write loses its response', async () => {
  const keys: string[] = []
  const bodies: string[] = []
  const persisted = new Map<string, { ticketId: string }>()
  const envelope = (data: unknown) => new Response(JSON.stringify({ data, meta: { page: { nextPageToken: null } } }), { status: 200, headers: { 'Content-Type': 'application/json' } })
  const transport = vi.fn(async (input: string, init: RequestInit) => {
    if (init.method !== 'POST') return envelope(input.includes('service-catalog-items')
      ? [{ catalogItemId: 'catalog-1', itemName: '笔记本申请', status: 'ACTIVE', riskLevel: 'NORMAL' }] : [])
    expect(input).toContain('/tickets')
    const key = new Headers(init.headers).get('Idempotency-Key')!
    keys.push(key); bodies.push(String(init.body))
    if (!persisted.has(key)) persisted.set(key, { ticketId: `created-${persisted.size + 1}` })
    if (keys.length === 1) throw new TypeError('Simulated response loss after commit')
    return envelope(persisted.get(key))
  })
  vi.stubGlobal('fetch', transport)
  // Import after installing the transport to exercise real component, endpoint and HTTP-client code.
  const { default: TicketsPage } = await import('@/views/TicketsPage.vue')
  const wrapper = mount(TicketsPage)
  await flushPromises()
  await wrapper.get('#new-ticket-subject').setValue('申请办公笔记本')
  await wrapper.get('#new-ticket-description').setValue('用于新员工日常工作')
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  expect(wrapper.find('[role="dialog"]').exists()).toBe(true)
  expect(persisted.size).toBe(1)
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  expect(keys.length).toBe(2)
  expect(keys[0]).toBe(keys[1])
  expect(bodies[0]).toBe(bodies[1])
  expect(persisted.size).toBe(1)
  expect(state.push).toHaveBeenCalledWith('/tickets/created-1')
  wrapper.unmount()
  vi.unstubAllGlobals()
})