import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import router from './index'
import { useAuthStore } from '@/stores/auth'

describe('router access', () => {
  beforeEach(async () => { setActivePinia(createPinia()); useAuthStore().logout(); await router.replace('/login') })

  it('redirects unauthenticated users to login', async () => {
    await router.push('/tickets')
    expect(router.currentRoute.value.name).toBe('login')
  })

  it('keeps role-gated pages out of the requester navigation surface', async () => {
    const auth = useAuthStore()
    auth.session = { accessToken: 'memory', tokenType: 'Bearer', expiresAt: new Date().toISOString(), user: { userId: 'u', username: 'requester', displayName: '申请人', status: 'ACTIVE', tenant: { tenantCode: 't', displayName: '租户' }, roles: ['REQUESTER'], permissions: ['TICKET_READ_OWN'] } }
    await router.push('/approvals')
    expect(router.currentRoute.value.name).toBe('dashboard')
  })

  it('does not treat workflow rule access as runtime approval access', async () => {
    const auth = useAuthStore()
    auth.session = {
      accessToken: 'memory',
      tokenType: 'Bearer',
      expiresAt: new Date().toISOString(),
      user: {
        userId: 'admin',
        username: 'admin',
        displayName: '服务台管理员',
        status: 'ACTIVE',
        tenant: { tenantCode: 't', displayName: '组织' },
        roles: ['SERVICE_DESK_ADMIN'],
        permissions: ['WORKFLOW_READ_TENANT'],
      },
    }

    await router.push('/approvals')
    expect(router.currentRoute.value.name).toBe('approvals')
    await router.push('/approvals/00000000-0000-4000-8000-000000000001')
    expect(router.currentRoute.value.name).toBe('dashboard')
  })
})
