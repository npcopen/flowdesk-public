import { defineConfig, devices } from '@playwright/test'

const backend = process.env.FLOWDESK_E2E_BACKEND || 'mock'
const baseURL = process.env.FLOWDESK_E2E_GATEWAY_URL ||
  (backend === 'gateway' ? 'http://127.0.0.1:8080' : 'http://127.0.0.1:5173')
const executablePath = process.env.FLOWDESK_CHROME_PATH || 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe'

export default defineConfig({
  testDir: './src/e2e',
  testIgnore: backend === 'gateway' ? ['**/demo.spec.ts', '**/administration.spec.ts'] : ['**/gateway.spec.ts'],
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  workers: 1,
  reporter: [['html', { outputFolder: 'playwright-report', open: 'never' }], ['list']],
  use: {
    baseURL,
    launchOptions: { executablePath },
    trace: 'retain-on-failure',
    screenshot: 'on',
    video: 'off',
    ignoreHTTPSErrors: true,
    extraHTTPHeaders: backend === 'mock'
      ? { 'X-FlowDesk-E2E-Backend': 'mock' }
      : undefined
  },
  projects: [
    { name: 'desktop', use: { ...devices['Desktop Chrome'], viewport: { width: 1440, height: 900 } } },
    // Chromium with a narrow viewport keeps CI/browser prerequisites small while
    // still exercising the responsive mobile layout.
    { name: 'mobile', testIgnore: backend === 'gateway'
      ? ['**/gateway.spec.ts', '**/demo.spec.ts', '**/administration.spec.ts'] : undefined,
      use: { ...devices['Desktop Chrome'], viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true } }
  ],
  webServer: backend === 'mock' ? {
    command: 'npm run dev -- --host 127.0.0.1',
    url: 'http://127.0.0.1:5173',
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
    env: { VITE_E2E_BACKEND: 'mock', VITE_NOTIFICATION_MODE: 'mock', VITE_RECOVERY_MODE: 'mock', VITE_SLA_MODE: 'mock' }
  } : undefined
})
