/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_GATEWAY_DEV_URL?: string
  readonly VITE_DEV_PORT?: string
  readonly VITE_NOTIFICATION_MODE?: 'disabled' | 'mock' | 'api'
  readonly VITE_RECOVERY_MODE?: 'disabled' | 'mock' | 'api'
  readonly VITE_SLA_MODE?: 'readonly-api' | 'mock' | 'disabled'
  readonly VITE_E2E_BACKEND?: 'mock' | 'web' | 'gateway'
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}

interface Window {
  /** Present only in the mock Playwright profile; never used by the product UI. */
  __FLOWDESK_E2E_TRIGGER_ALERT?: (eventType?: 'FIRING' | 'RESOLVED') => void
  __FLOWDESK_E2E_RESET?: () => void
}
