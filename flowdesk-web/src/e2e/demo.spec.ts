import { test, expect, type Page } from '@playwright/test'

async function reset(page: Page): Promise<void> {
  await page.goto('/login')
  await page.evaluate(() => window.__FLOWDESK_E2E_RESET?.())
}

async function login(page: Page, username: string): Promise<void> {
  if (!page.url().includes('/login')) await page.goto('/login')
  await page.getByLabel('组织代码').fill('acme-tenant')
  await page.getByLabel('用户名').fill(username)
  await page.getByTestId('password').fill('local-password')
  await page.getByRole('button', { name: '进入工作台' }).click()
  await expect(page).toHaveURL(/\/$/)
}

async function logout(page: Page): Promise<void> {
  await page.getByRole('button', { name: '退出登录' }).click()
  await expect(page).toHaveURL(/\/login/)
}

async function navigate(page: Page, path: string): Promise<void> {
  // Keep the memory-only session alive: a full reload intentionally clears it.
  await page.evaluate((target) => { history.pushState({}, '', target); window.dispatchEvent(new PopStateEvent('popstate')) }, path)
  await page.waitForTimeout(30)
}

async function openMobileNav(page: Page): Promise<void> {
  if (await page.evaluate(() => window.innerWidth < 761)) {
    await page.getByRole('button', { name: '打开导航' }).click()
  }
}

test.describe('FlowDesk browser flows', () => {
  test.beforeEach(async ({ page }) => { await reset(page) })

  test('DEMO-001 ordinary manual ticket closure', async ({ page }) => {
    await login(page, 'requester')
    await page.getByRole('link', { name: '新建工单' }).click()
    await expect(page.getByRole('heading', { name: '新建工单' })).toBeVisible()
    await page.getByLabel('服务目录').selectOption({ label: '标准 IT 服务请求' })
    await page.getByLabel('主题').fill('办公软件访问申请')
    await page.getByLabel('描述').fill('申请办公软件访问权限，完成后请确认。')
    await page.getByRole('button', { name: '提交工单' }).click()
    await expect(page).toHaveURL(/\/tickets\/[0-9a-f-]+$/)
    const ticketUrl = page.url()
    await expect(page.locator('.detail-heading .status-badge')).toHaveText('待分配')

    await logout(page); await login(page, 'admin'); await navigate(page, new URL(ticketUrl).pathname)
    await page.getByRole('button', { name: '分配' }).click()
    await page.getByLabel('处理人').selectOption({ label: '值班工程师（agent）' })
    await page.getByRole('button', { name: '确认操作' }).click()
    await expect(page.locator('.detail-heading .status-badge')).toHaveText('已分配')

    await logout(page); await login(page, 'agent'); await navigate(page, new URL(ticketUrl).pathname)
    await page.getByRole('button', { name: '接单' }).click(); await page.getByRole('button', { name: '确认操作' }).click()
    await expect(page.locator('.detail-heading .status-badge')).toHaveText('处理中')
    await page.getByLabel('评论内容').fill('已接单，开始处理。'); await page.getByRole('button', { name: '添加评论' }).click()
    await page.getByRole('button', { name: '标记已解决' }).click(); await page.getByLabel('解决摘要').fill('处理完成，验证通过。'); await page.getByRole('button', { name: '确认操作' }).click()
    await expect(page.locator('.detail-heading .status-badge')).toHaveText('已解决')

    await logout(page); await login(page, 'requester'); await navigate(page, new URL(ticketUrl).pathname)
    await page.getByRole('button', { name: '重新打开', exact: true }).click()
    await page.getByLabel('原因（必填）', { exact: true }).fill('实际使用仍然报错，请继续处理。')
    await page.getByRole('button', { name: '确认操作' }).click()
    await expect(page.locator('.detail-heading .status-badge')).toHaveText('处理中')
    await expect(page.getByText('本轮预计解决时间', { exact: true })).toBeVisible()
    await expect(page.locator('details')).toContainText('第 1 轮')
    await logout(page); await login(page, 'agent'); await navigate(page, '/notifications')
    await page.locator('.notification-row').filter({ hasText: '工单已重新打开' }).getByLabel('查看通知详情').click()
    await page.getByRole('dialog').getByRole('button', { name: '查看工单' }).click()
    await expect(page).toHaveURL(ticketUrl)
    await page.getByRole('button', { name: '标记已解决' }).click()
    await page.getByLabel('解决摘要').fill('已修复复现问题并再次验证。')
    await page.getByRole('button', { name: '确认操作' }).click()
    await expect(page.locator('.detail-heading .status-badge')).toHaveText('已解决')
    await logout(page); await login(page, 'requester'); await navigate(page, new URL(ticketUrl).pathname)
    await page.getByRole('button', { name: '关闭工单' }).click(); await page.getByRole('button', { name: '确认操作' }).click()
    await expect(page.locator('.detail-heading .status-badge')).toHaveText('已关闭')
    await page.screenshot({ path: `${process.env.FLOWDESK_E2E_EVIDENCE_DIR || 'artifacts/playwright/business-scenarios'}/${test.info().project.name}-demo-001.png`, fullPage: true })
  })

  test('DEMO-002 two-level conditional approval', async ({ page }) => {
    await login(page, 'requester'); await page.getByRole('link', { name: '新建工单' }).click()
    await page.getByLabel('服务目录').selectOption({ label: '生产数据库权限 · 两级审批' })
    await page.getByLabel('主题').fill('生产数据库权限申请')
    await page.getByLabel('描述').fill('申请生产环境权限，需要两级审批。')
    await page.getByRole('button', { name: '提交工单' }).click()
    await expect(page).toHaveURL(/\/tickets\/[0-9a-f-]+$/); const ticketUrl = page.url()
    await expect(page.locator('.detail-heading .status-badge')).toHaveText('等待审批')
    await page.waitForTimeout(120)

    await logout(page); await login(page, 'approver1'); await openMobileNav(page); await page.getByRole('link', { name: '审批', exact: true }).click()
    await page.locator('.data-table tbody tr a.link-strong').first().click()
    await expect(page.getByText('申请生产环境权限，需要两级审批。', { exact: true })).toBeVisible()
    await expect(page.getByRole('heading', { name: '第 1 级审批', exact: true })).toBeVisible(); await page.getByRole('button', { name: '同意' }).click(); await page.getByLabel('审批原因').fill('一级条件满足。'); await page.getByRole('button', { name: '提交决定' }).click()
    await expect(page.locator('.terminal-decision .status-badge')).toHaveText('已同意')

    await logout(page); await login(page, 'approver2'); await openMobileNav(page); await page.getByRole('link', { name: '审批', exact: true }).click()
    await expect(page.getByText('第 2 级', { exact: true })).toBeVisible(); await page.locator('.data-table tbody tr a.link-strong').first().click(); await page.getByRole('button', { name: '同意' }).click(); await page.getByLabel('审批原因').fill('二级风险复核通过。'); await page.getByRole('button', { name: '提交决定' }).click()
    await expect(page.locator('.terminal-decision .status-badge')).toHaveText('已同意')

    await logout(page); await login(page, 'requester'); await navigate(page, new URL(ticketUrl).pathname); await expect(page.locator('.detail-heading .status-badge')).toHaveText('待分配')
    await page.screenshot({ path: `${process.env.FLOWDESK_E2E_EVIDENCE_DIR || 'artifacts/playwright/business-scenarios'}/${test.info().project.name}-demo-002.png`, fullPage: true })
  })

  test('approval handoff preserves progress and gives the replacement a usable notification', async ({ page }) => {
    await login(page, 'requester'); await page.getByRole('link', { name: '新建工单' }).click()
    await page.getByLabel('服务目录').selectOption({ label: '生产数据库权限 · 两级审批' })
    await page.getByLabel('主题').fill('主管离职期间的权限申请')
    await page.getByLabel('描述').fill('申请临时只读权限两小时，请接替主管审核。')
    await page.getByRole('button', { name: '提交工单' }).click()
    await expect(page).toHaveURL(/\/tickets\/[0-9a-f-]+$/)
    await logout(page); await login(page, 'admin'); await navigate(page, '/approvals')
    await page.locator('.data-table tbody tr a.link-strong').first().click()
    await expect(page).toHaveURL(/\/approvals\/[0-9a-f-]+$/)
    const taskUrl = page.url()
    await page.getByRole('button', { name: '交接审批人', exact: true }).click()
    await page.getByLabel('接替人员').selectOption({ label: '备用审批人（approver3）' })
    await page.getByLabel('交接原因').fill('原主管已离职，由备用审批人接替。')
    await page.getByRole('button', { name: '确认交接', exact: true }).click()
    await expect(page.getByRole('heading', { name: '审批交接记录', exact: true })).toBeVisible()
    await expect(page.getByText('原主管已离职，由备用审批人接替。', { exact: true })).toBeVisible()
    await logout(page); await login(page, 'approver1'); await navigate(page, new URL(taskUrl).pathname)
    await expect(page.getByRole('alert')).toBeVisible()
    await expect(page.getByRole('button', { name: '同意', exact: true })).toHaveCount(0)
    await logout(page); await login(page, 'approver3'); await navigate(page, '/notifications')
    await page.locator('.notification-row').filter({ hasText: '审批交接待办' }).getByLabel('查看通知详情').click()
    await page.getByRole('dialog').getByRole('button', { name: '查看审批' }).click()
    await expect(page).toHaveURL(taskUrl)
    await expect(page.getByText('申请临时只读权限两小时，请接替主管审核。', { exact: true })).toBeVisible()
    await page.getByRole('button', { name: '同意', exact: true }).click()
    await page.getByRole('button', { name: '提交决定' }).click()
    await expect(page.locator('.terminal-decision .status-badge')).toHaveText('已同意')
    await logout(page); await login(page, 'approver2'); await navigate(page, '/approvals')
    await expect(page.getByText('第 2 级', { exact: true })).toBeVisible()
    await page.screenshot({ path: `${process.env.FLOWDESK_E2E_EVIDENCE_DIR || 'artifacts/playwright/business-scenarios'}/${test.info().project.name}-approval-handoff.png`, fullPage: true })
  })

  test('DEMO-003 incident observation and recovery', async ({ page }) => {
    await login(page, 'admin')
    await page.evaluate(() => window.__FLOWDESK_E2E_TRIGGER_ALERT?.('FIRING'))
    await openMobileNav(page); await page.getByRole('link', { name: '告警', exact: true }).click(); await expect(page.getByText('支付接口响应异常').first()).toBeVisible()
    const incidentLink = page.locator('a[href^="/alerts/"]').filter({ hasText: '查看详情' }).first(); await incidentLink.click()
    await expect(page.locator('.detail-heading .status-badge').filter({ hasText: '进行中' })).toBeVisible(); await expect(page.getByRole('heading', { name: '关联工单', exact: true })).toBeVisible(); await expect(page.getByText(/监控告警：支付接口异常/)).toBeVisible()
    await page.evaluate(() => window.__FLOWDESK_E2E_TRIGGER_ALERT?.('RESOLVED'))
    await page.getByRole('button', { name: '刷新' }).click(); await expect(page.getByText(/已恢复/)).toBeVisible(); await expect(page.getByText(/告警恢复后，工单仍需单独完成/)).toBeVisible()
    await page.screenshot({ path: `${process.env.FLOWDESK_E2E_EVIDENCE_DIR || 'artifacts/playwright/business-scenarios'}/${test.info().project.name}-demo-003.png`, fullPage: true })
  })
})
