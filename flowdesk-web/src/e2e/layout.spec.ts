import { test, expect } from '@playwright/test'
import { join } from 'node:path'

const artifactDirectory = process.env.FLOWDESK_E2E_ARTIFACT_DIR || 'artifacts/playwright'

test('desktop and mobile surfaces do not overflow horizontally', async ({ page }) => {
  await page.goto('/login')
  await expect(page.getByRole('heading', { name: '登录工作空间' })).toBeVisible()
  const dimensions = await page.evaluate(() => ({ width: document.documentElement.scrollWidth, viewport: window.innerWidth }))
  expect(dimensions.width).toBeLessThanOrEqual(dimensions.viewport + 1)
  await page.screenshot({ path: join(artifactDirectory, `layout-${test.info().project.name}.png`), fullPage: true })
})
