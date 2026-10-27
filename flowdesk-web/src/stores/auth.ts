/** 在内存中维护当前会话与权威用户资料，为页面提供角色和权限判断。 */
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

  /**
   * 提交登录凭证并用当前用户端点确认权威身份。
   * 成功后建立内存会话，任一步骤失败则清理会话，防止遗留半登录状态。
   */
  async function login(input: { tenantCode: string; username: string; password: string }): Promise<void> {
    loading.value = true
    authError.value = null
    try {
      const result = await authApi.login(input)
      session.value = result.data
      // 登录响应只建立短期会话；显示资料和有效权限以 /users/me 的返回结果为准。
      const currentUser = await authApi.me()
      session.value = { ...session.value, user: currentUser.data }
      // 显式启用的离线功能也使用相同的用户身份投影，保持与真实 API 一致。
      if (!import.meta.env.PROD) mockApi.setSession(currentUser.data)
      initialized.value = true
    } catch (error) {
      session.value = null
      authError.value = error
      throw error
    } finally {
      loading.value = false
    }
  }

  /**
   * 重新读取当前用户、角色和权限并更新会话。
   * 认证失效时退出登录，其他请求故障保留明确错误供调用方处理。
   */
  async function loadCurrent(): Promise<boolean> {
    if (!session.value) {
      initialized.value = true
      return false
    }
    loading.value = true
    try {
      const result = await authApi.me()
      session.value = { ...session.value, user: result.data }
      if (!import.meta.env.PROD) mockApi.setSession(result.data)
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

  /**
   * 清除内存中的访问令牌和用户会话。
   * 非生产环境同时清理模拟身份，使后续请求不再使用旧用户。
   */
  function logout(): void {
    // v1 契约未提供登出接口，在本地移除 Bearer 令牌并清理模拟会话。
    session.value = null
    if (!import.meta.env.PROD) mockApi.clearSession()
    authError.value = null
    initialized.value = true
  }

  /**
   * 判断当前会话是否包含指定权限码。
   * 页面用此结果控制功能入口，实际业务授权由服务端校验。
   */
  function can(permission: PermissionCode): boolean {
    return Boolean(user.value?.permissions.includes(permission))
  }

  /**
   * 判断当前用户是否具有指定角色。
   * 未登录时返回否，供界面选择相应展示内容。
   */
  function hasRole(role: RoleCode): boolean {
    return Boolean(user.value?.roles.includes(role))
  }

  /**
   * 判断当前会话是否至少具有一个候选权限。
   * 用于多个权限均可进入的功能入口或路由。
   */
  function canAny(permissions: PermissionCode[]): boolean {
    return permissions.some(permission => can(permission))
  }

  return { session, user, loading, initialized, authError, isAuthenticated, login, loadCurrent, logout, can, canAny, hasRole }
})
