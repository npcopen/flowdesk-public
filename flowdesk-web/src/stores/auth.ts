import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { authApi } from '@/api/endpoints'
import { isApiClientError } from '@/api/errors'
import { mockApi } from '@/mocks/mockApi'
import type { AuthSession, PermissionCode, RoleCode, CurrentUser } from '@/types/api'

/** Authentication is intentionally memory-only. Reloading the SPA requires a new login. */
export const useAuthStore = defineStore('auth', () => {
  const session = ref<AuthSession | null>(null)
  const loading = ref(false)
  const initialized = ref(false)
  const authError = ref<unknown>(null)

  const user = computed<CurrentUser | null>(() => session.value?.user ?? null)
  const isAuthenticated = computed(() => Boolean(session.value?.accessToken && user.value))

  async function login(input: { tenantCode: string; username: string; password: string }): Promise<void> {
    loading.value = true
    authError.value = null
    try {
      const result = await authApi.login(input)
      session.value = result.data
      // API-002 is the authoritative source for the signed-in user's display
      // profile and effective permissions. The login response only establishes
      // the short-lived session.
      const currentUser = await authApi.me()
      session.value = { ...session.value, user: currentUser.data }
      // Feature-scoped local data, when explicitly enabled for offline work,
      // still uses the same safe identity projection as the real API.
      mockApi.setSession(currentUser.data)
      initialized.value = true
    } catch (error) {
      session.value = null
      authError.value = error
      throw error
    } finally {
      loading.value = false
    }
  }

  async function loadCurrent(): Promise<boolean> {
    if (!session.value) {
      initialized.value = true
      return false
    }
    loading.value = true
    try {
      const result = await authApi.me()
      session.value = { ...session.value, user: result.data }
      mockApi.setSession(result.data)
      return true
    } catch (error) {
      if (isApiClientError(error) && (error.status === 401 || error.code === 'TOKEN_EXPIRED' || error.code === 'AUTHENTICATION_REQUIRED')) {
        logout()
      }
      return false
    } finally {
      initialized.value = true
      loading.value = false
    }
  }

  function logout(): void {
    // There is no logout API in the v1 contract. Drop the bearer token locally.
    session.value = null
    mockApi.clearSession()
    authError.value = null
    initialized.value = true
  }

  function can(permission: PermissionCode): boolean {
    return Boolean(user.value?.permissions.includes(permission))
  }

  function hasRole(role: RoleCode): boolean {
    return Boolean(user.value?.roles.includes(role))
  }

  function canAny(permissions: PermissionCode[]): boolean {
    return permissions.some(permission => can(permission))
  }

  return { session, user, loading, initialized, authError, isAuthenticated, login, loadCurrent, logout, can, canAny, hasRole }
})
