import { expect, test, type Page } from "@playwright/test";
import { join } from "node:path";

const artifactDirectory = process.env.FLOWDESK_E2E_ARTIFACT_DIR || "artifacts/playwright";
/**
 * 将产物文件名拼接到本次端到端验证目录。
 * 使截图及报告附件使用统一保存位置。
 */
const artifact = (name: string): string => join(artifactDirectory, name);
const browserTicketAnnotation = "flowdesk-browser-ticket";

/**
 * 校验浏览器场景产生的工单标识并写入测试注解。
 * 注解保存场景与工单的对应关系，供部署验证报告追踪。
 */
function recordBrowserTicket(
  scenario: "DEMO-001" | "DEMO-002",
  ticketId: string,
): void {
  expect(ticketId).toMatch(/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/);
  test.info().annotations.push({
    type: browserTicketAnnotation,
    description: JSON.stringify({ scenario, ticketId }),
  });
}

const gatewayConfig = {
  tenant: process.env.FLOWDESK_E2E_TENANT || "",
  password: process.env.FLOWDESK_E2E_PASSWORD || "",
  requester: process.env.FLOWDESK_E2E_REQUESTER || "",
  admin: process.env.FLOWDESK_E2E_ADMIN || "",
  agent: process.env.FLOWDESK_E2E_AGENT || "",
  approver1: process.env.FLOWDESK_E2E_APPROVER1 || "",
  approver2: process.env.FLOWDESK_E2E_APPROVER2 || "",
  expectedIncidentId: process.env.FLOWDESK_E2E_EXPECTED_INCIDENT_ID || "",
};

const configured = [
  gatewayConfig.tenant,
  gatewayConfig.password,
  gatewayConfig.requester,
  gatewayConfig.admin,
  gatewayConfig.agent,
  gatewayConfig.approver1,
  gatewayConfig.approver2,
  gatewayConfig.expectedIncidentId,
].every(Boolean);

if (["web", "gateway"].includes(process.env.FLOWDESK_E2E_BACKEND || "") && !configured) {
  throw new Error(
    "Deployed web acceptance requires FLOWDESK_E2E_TENANT, FLOWDESK_E2E_PASSWORD, " +
      "FLOWDESK_E2E_REQUESTER, FLOWDESK_E2E_ADMIN, FLOWDESK_E2E_AGENT, " +
      "FLOWDESK_E2E_APPROVER1, FLOWDESK_E2E_APPROVER2 and " +
      "FLOWDESK_E2E_EXPECTED_INCIDENT_ID",
  );
}

/**
 * 通过已部署网关完成真实浏览器登录。
 * 同步捕获登录响应并校验访问令牌，返回令牌供后续受保护状态查询使用。
 */
async function login(page: Page, username: string): Promise<string> {
  await page.goto("/login");
  await page.getByLabel("组织代码").fill(gatewayConfig.tenant);
  await page.getByLabel("用户名").fill(username);
  await page.getByTestId("password").fill(gatewayConfig.password);
  const loginResponse = page.waitForResponse(
    (response) =>
      response.request().method() === "POST" &&
      response.url().endsWith("/api/v1/auth/login"),
  );
  await page.getByRole("button", { name: "进入工作台" }).click();
  const response = await loginResponse;
  const payload = (await response.json()) as {
    data?: { accessToken?: string };
  };
  if (!response.ok() || !payload.data?.accessToken) {
    throw new Error(`Gateway login did not return an access token (${response.status()}).`);
  }
  await expect(page).toHaveURL(/\/$/);
  return payload.data.accessToken;
}

/**
 * 从已部署应用退出并等待登录页面。
 * 确保后续角色登录从明确的无会话状态开始。
 */
async function logout(page: Page): Promise<void> {
  await page.getByRole("button", { name: "退出登录" }).click();
  await expect(page).toHaveURL(/\/login/);
}

/**
 * 在已部署单页应用内部变更路由。
 * 使用历史事件保留仅存于内存的会话，并等待页面完成响应。
 */
async function navigate(page: Page, path: string): Promise<void> {
  // Keep the memory-only session while changing views within the SPA.
  await page.evaluate((target) => {
    history.pushState({}, "", target);
    window.dispatchEvent(new PopStateEvent("popstate"));
  }, path);
  await page.waitForTimeout(80);
}

/**
 * 在超时前循环检查页面正文是否满足业务条件。
 * 必要时点击页面刷新按钮，等待异步后端状态在界面中收敛。
 */
async function refreshUntil(
  page: Page,
  predicate: (body: string) => boolean,
  timeout = 60_000,
): Promise<void> {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    if (predicate(await page.locator("body").innerText())) return;
    const refresh = page.getByRole("button", { name: "刷新" }).first();
    if (await refresh.isVisible().catch(() => false)) {
      await refresh.click().catch(() => undefined);
    }
    await page.waitForTimeout(900);
  }
  throw new Error("Gateway state did not settle before the timeout");
}

/**
 * 轮询指定工单的工作流启动状态并返回已建立实例标识。
 * 网关请求失败立即报错，超过等待时限则明确失败。
 */
async function waitForWorkflowInstance(
  page: Page,
  token: string,
  ticketId: string,
): Promise<string> {
  const deadline = Date.now() + 60_000;
  while (Date.now() < deadline) {
    const response = await page.request.get(
      `/api/v1/tickets/${ticketId}/workflow-start`,
      { headers: { Authorization: `Bearer ${token}` } },
    );
    const payload = (await response.json()) as {
      data?: { workflowInstanceId?: string | null };
    };
    if (!response.ok()) {
      throw new Error(
        `Workflow start lookup failed with HTTP ${response.status()}.`,
      );
    }
    const workflowInstanceId = payload.data?.workflowInstanceId;
    if (workflowInstanceId) return workflowInstanceId;
    await page.waitForTimeout(500);
  }
  throw new Error(`Workflow instance for ticket ${ticketId} did not become visible.`);
}

/**
 * 按流程实例、审批级别和待处理状态轮询任务集合。
 * 仅返回完全匹配的任务标识，避免场景误用其他人的审批任务。
 */
async function waitForApprovalTask(
  page: Page,
  token: string,
  workflowInstanceId: string,
  level: number,
): Promise<string> {
  const deadline = Date.now() + 60_000;
  const query = new URLSearchParams({
    status: "PENDING",
    levelNo: String(level),
    workflowInstanceId,
    pageSize: "20",
    sort: "-createdAt",
  });
  while (Date.now() < deadline) {
    const response = await page.request.get(
      `/api/v1/approval-tasks?${query.toString()}`,
      { headers: { Authorization: `Bearer ${token}` } },
    );
    const payload = (await response.json()) as {
      data?: Array<{
        approvalTaskId?: string;
        workflowInstanceId?: string;
        levelNo?: number;
        status?: string;
      }>;
    };
    if (!response.ok()) {
      throw new Error(
        `Approval task lookup failed with HTTP ${response.status()}.`,
      );
    }
    const task = payload.data?.find(
      (candidate) =>
        candidate.approvalTaskId &&
        candidate.workflowInstanceId === workflowInstanceId &&
        candidate.levelNo === level &&
        candidate.status === "PENDING",
    );
    if (task?.approvalTaskId) return task.approvalTaskId;
    await page.waitForTimeout(500);
  }
  throw new Error(
    `Level ${level} approval task for workflow ${workflowInstanceId} did not become visible.`,
  );
}

/**
 * 导航到指定审批任务详情并等待级别标题出现。
 * 确保后续同意、拒绝或交接操作作用于已加载的页面。
 */
async function openApproval(page: Page, taskId: string): Promise<void> {
  await navigate(page, `/approvals/${taskId}`);
  await expect(page.getByRole("heading", { name: /第 [12] 级审批/ })).toBeVisible();
}

test.describe("FlowDesk deployed web acceptance", () => {
  test.describe.configure({ mode: "serial" });

  test("DEMO-001 ordinary manual ticket closure through Gateway", async ({ page }) => {
    await login(page, gatewayConfig.requester);
    await page.getByRole("link", { name: "新建工单" }).click();
    await expect(page.getByRole("heading", { name: "新建工单" })).toBeVisible();
    await page.getByLabel("服务目录").selectOption({ label: "笔记本电脑申请" });
    const subject = `办公软件访问申请-${Date.now()}`;
    await page.getByLabel("主题").fill(subject);
    await page.getByLabel("描述").fill("申请办公软件访问权限，完成后请确认。");
    await page.getByRole("button", { name: "提交工单" }).click();
    await expect(page).toHaveURL(/\/tickets\/[0-9a-f-]+$/);
    const ticketPath = new URL(page.url()).pathname;
    const ticketId = ticketPath.split("/").pop() || "";
    await expect(page.locator(".detail-heading .status-badge")).toHaveText("待分配");

    await logout(page);
    await login(page, gatewayConfig.admin);
    await navigate(page, ticketPath);
    await page.getByRole("button", { name: "分配" }).click();
    const assignee = page.getByLabel("处理人");
    const agentOption = assignee
      .locator("option")
      .filter({ hasText: `（${gatewayConfig.agent}）` });
    await expect(agentOption).toHaveCount(1);
    const agentValue = await agentOption.getAttribute("value");
    expect(agentValue).toBeTruthy();
    await assignee.selectOption({ value: agentValue! });
    await page.getByRole("button", { name: "确认操作" }).click();
    await expect(page.locator(".detail-heading .status-badge")).toHaveText("已分配");

    await logout(page);
    await login(page, gatewayConfig.agent);
    await navigate(page, ticketPath);
    await page.getByRole("button", { name: "接单" }).click();
    await page.getByRole("button", { name: "确认操作" }).click();
    await expect(page.locator(".detail-heading .status-badge")).toHaveText("处理中");
    await page.getByLabel("评论内容").fill("已接单，开始处理。");
    await page.getByRole("button", { name: "添加评论" }).click();
    await page.getByRole("button", { name: "标记已解决" }).click();
    await page.getByLabel("解决摘要").fill("处理完成，验证通过。");
    await page.getByRole("button", { name: "确认操作" }).click();
    await expect(page.locator(".detail-heading .status-badge")).toHaveText("已解决");

    await logout(page);
    await login(page, gatewayConfig.requester);
    await navigate(page, ticketPath);
    await page.getByRole("button", { name: "关闭工单" }).click();
    await page.getByRole("button", { name: "确认操作" }).click();
    await expect(page.locator(".detail-heading .status-badge")).toHaveText("已关闭");
    recordBrowserTicket("DEMO-001", ticketId);
    await page.screenshot({ path: artifact("gateway-demo-001.png"), fullPage: true });
  });

  test("DEMO-002 two-level conditional approval through Gateway", async ({ page }) => {
    test.setTimeout(120_000);
    const requesterToken = await login(page, gatewayConfig.requester);
    await page.getByRole("link", { name: "新建工单" }).click();
    const catalog = page.getByLabel("服务目录");
    const highRiskOption = catalog
      .locator("option")
      .filter({ hasText: "生产数据库权限申请" });
    await expect(highRiskOption).toHaveCount(1);
    const highRiskValue = await highRiskOption.getAttribute("value");
    expect(highRiskValue).toBeTruthy();
    await catalog.selectOption({ value: highRiskValue! });
    const subject = `生产数据库权限申请-${Date.now()}`;
    await page.getByLabel("主题").fill(subject);
    await page.getByLabel("描述").fill("申请生产环境权限，需要两级审批。");
    await page.getByRole("button", { name: "提交工单" }).click();
    await expect(page).toHaveURL(/\/tickets\/[0-9a-f-]+$/);
    const ticketPath = new URL(page.url()).pathname;
    const ticketId = ticketPath.split("/").pop() || "";
    const workflowInstanceId = await waitForWorkflowInstance(
      page,
      requesterToken,
      ticketId,
    );
    await expect(page.locator(".detail-heading .status-badge")).toHaveText("等待审批");

    await logout(page);
    const approver1Token = await login(page, gatewayConfig.approver1);
    const level1TaskId = await waitForApprovalTask(
      page,
      approver1Token,
      workflowInstanceId,
      1,
    );
    await openApproval(page, level1TaskId);
    await expect(page.getByRole("heading", { name: "第 1 级审批", exact: true })).toBeVisible();
    await page.getByRole("button", { name: "同意" }).click();
    await page.getByLabel("审批原因").fill("一级条件满足。");
    await page.getByRole("button", { name: "提交决定" }).click();
    await expect(page.locator(".terminal-decision .status-badge")).toHaveText("已同意");

    await logout(page);
    const approver2Token = await login(page, gatewayConfig.approver2);
    const level2TaskId = await waitForApprovalTask(
      page,
      approver2Token,
      workflowInstanceId,
      2,
    );
    await openApproval(page, level2TaskId);
    await expect(page.getByRole("heading", { name: "第 2 级审批", exact: true })).toBeVisible();
    await page.getByRole("button", { name: "同意" }).click();
    await page.getByLabel("审批原因").fill("二级风险复核通过。");
    await page.getByRole("button", { name: "提交决定" }).click();
    await expect(page.locator(".terminal-decision .status-badge")).toHaveText("已同意");

    await logout(page);
    await login(page, gatewayConfig.requester);
    await navigate(page, ticketPath);
    await refreshUntil(page, (body) => body.includes("待分配"));
    await expect(page.locator(".detail-heading .status-badge")).toHaveText("待分配");
    recordBrowserTicket("DEMO-002", ticketId);
    await page.screenshot({ path: artifact("gateway-demo-002.png"), fullPage: true });
  });

  test("DEMO-003 observes an alert recovery through Gateway", async ({ page }) => {
    await login(page, gatewayConfig.admin);
    await navigate(page, "/alerts");
    const incidentLink = page
      .locator(`a[href="/alerts/${gatewayConfig.expectedIncidentId}"]`)
      .filter({ hasText: "查看详情" });
    await expect(incidentLink).toBeVisible();
    await incidentLink.click();
    // The harness supplies the Incident created by this tenant's API flow.
    // Matching the route prevents a misleading pass when an older or other
    // tenant's first list item happens to be displayed.
    await expect(page).toHaveURL(
      new RegExp(`/alerts/${gatewayConfig.expectedIncidentId}$`),
    );
    await expect(page.getByRole("heading", { name: /告警/ }).first()).toBeVisible();
    await expect(page.getByRole("heading", { name: "关联工单", exact: true })).toBeVisible();
    await expect(page.getByText("告警恢复后，工单仍需单独完成。")).toBeVisible();
    await expect(page.locator(".detail-heading .status-badge")).toHaveText("已恢复");
    await page.screenshot({ path: artifact("gateway-demo-003.png"), fullPage: true });
  });
});
