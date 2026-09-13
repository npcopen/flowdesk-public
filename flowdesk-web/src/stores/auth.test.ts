import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAuthStore } from './auth'
import { authApi } from '@/api/endpoints'
import { mockApi } from '@/mocks/mockApi'

describe('auth store', () => {
  beforeEach(() => { setActivePinia(createPinia()); vi.restoreAllMocks() })

  it('keeps the session in memory and exposes permission checks', async () => {
    vi.spyOn(authApi, 'login').mockResolvedValue({ data: { accessToken: 'secret', tokenType: 'Bearer', expiresAt: new Date().toISOString(), user: { userId: 'u', username: 'requester', displayName: '申请人', status: 'ACTIVE', tenant: { tenantCode: 't', displayName: '租户' }, roles: ['REQUESTER'], permissions: ['TICKET_CREATE'] } }, meta: { correlationId: 'c' }, status: 200, headers: new Headers() })
    vi.spyOn(authApi, 'me').mockResolvedValue({ data: { userId: 'u', username: 'requester', displayName: '申请人', status: 'ACTIVE', tenant: { tenantCode: 't', displayName: '租户' }, roles: ['REQUESTER'], permissions: ['TICKET_CREATE'] }, meta: { correlationId: 'c-me' }, status: 200, headers: new Headers() })
    const store = useAuthStore()
    await store.login({ tenantCode: 't', username: 'requester', password: 'never-recorded' })
    expect(store.isAuthenticated).toBe(true)
    expect(store.can('TICKET_CREATE')).toBe(true)
    await expect(mockApi.request('/api/v1/users/me')).resolves.toMatchObject({
      data: { userId: 'u' },
    })
    store.logout()
    expect(store.isAuthenticated).toBe(false)
    expect(store.session).toBeNull()
  })
})
