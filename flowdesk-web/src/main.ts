import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import { configureAuthFailureHandler, configureTokenProvider } from '@/api/client'
import { useAuthStore } from '@/stores/auth'
import { featureFlags } from '@/api/features'
import { mockApi } from '@/mocks/mockApi'
import './styles.css'

const app = createApp(App)
const pinia = createPinia()
app.use(pinia)
const auth = useAuthStore(pinia)
configureTokenProvider(() => auth.session?.accessToken ?? null)
configureAuthFailureHandler(() => {
  auth.logout()
  if (router.currentRoute.value.name !== 'login') {
    void router.replace({ name: 'login', query: { reason: 'session-expired' } })
  }
})
app.use(router)
app.mount('#app')

if (featureFlags.fullMock && typeof window !== 'undefined') {
  // Playwright uses this bridge to represent the external fixed monitoring script.
  window.__FLOWDESK_E2E_TRIGGER_ALERT = (eventType = 'FIRING') => mockApi.triggerAlert(eventType)
  window.__FLOWDESK_E2E_RESET = () => mockApi.reset()
}
