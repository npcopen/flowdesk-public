import { beforeEach, describe, expect, it, vi } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import { createPinia, setActivePinia } from "pinia";
import { ApiClientError } from "@/api/errors";
import { useAuthStore } from "@/stores/auth";
import type { MessageRecovery } from "@/types/api";

const recoveryMocks = vi.hoisted(() => ({
  list: vi.fn(),
  get: vi.fn(),
  replay: vi.fn(),
}));

vi.mock("@/api/endpoints", () => ({ recoveryApi: recoveryMocks }));

import RecoveryPanel from "./RecoveryPanel.vue";

const recovery: MessageRecovery = {
  recoveryId: "recovery-1",
  owner: "WORKFLOW",
  direction: "INBOX",
  sourceRecordId: "source-1",
  businessIntentDigest: "digest-1",
  state: "OPEN",
  attemptCount: 3,
  nextAttemptAt: null,
  lastErrorCode: "DEPENDENCY_UNAVAILABLE",
  lastErrorSummary: "依赖服务暂时不可用",
  deadLetteredAt: "2026-08-30T10:00:00Z",
  recoveryRequiredAt: "2026-08-30T09:00:00Z",
  replayCount: 0,
  lastReplayedAt: null,
  lastReplayedBy: null,
  lastReplayReason: null,
  lastReplayCorrelationId: null,
  resolvedAt: null,
  lockVersion: 1,
  createdAt: "2026-08-30T09:00:00Z",
  updatedAt: "2026-08-30T09:00:00Z",
};

/**
 * 构造消息恢复列表的测试响应。
 * 包含结束游标、排序与关联标识，模拟组件依赖的标准分页元数据。
 */
function listResult(items: MessageRecovery[] = []) {
  return {
    items,
    meta: {
      correlationId: "list-correlation",
      page: { pageSize: items.length, nextPageToken: null, sort: "-recoveryRequiredAt" },
    },
  };
}

/**
 * 向测试认证仓库写入具有恢复读取和重放权限的管理员会话。
 * 使组件测试在明确授权条件下验证界面行为。
 */
function authorize(): void {
  const auth = useAuthStore();
  auth.session = {
    accessToken: "memory-token",
    tokenType: "Bearer",
    expiresAt: new Date(Date.now() + 60_000).toISOString(),
    user: {
      userId: "admin-1",
      username: "servicedesk",
      displayName: "服务台管理员",
      status: "ACTIVE",
      tenant: { tenantCode: "tenant", displayName: "组织" },
      roles: ["SERVICE_DESK_ADMIN"],
      permissions: ["RECOVERY_READ_TENANT", "RECOVERY_REPLAY_TENANT"],
    },
  };
}

describe("RecoveryPanel", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    authorize();
    vi.clearAllMocks();
    recoveryMocks.list.mockImplementation((owner: string) =>
      Promise.resolve(owner === "WORKFLOW" ? listResult([recovery]) : listResult()),
    );
    recoveryMocks.get.mockResolvedValue({
      data: recovery,
      status: 200,
      meta: { correlationId: "detail-correlation" },
      headers: new Headers({ ETag: '"lv:1"' }),
    });
    recoveryMocks.replay.mockResolvedValue({
      data: { ...recovery, state: "REPLAYING", lockVersion: 2 },
      status: 202,
      meta: { correlationId: "replay-correlation" },
      headers: new Headers({ ETag: '"lv:2"' }),
    });
  });

  it("loads all four owner routes independently", async () => {
    recoveryMocks.list.mockImplementation((owner: string) => {
      if (owner === "ALERT") {
        return Promise.reject(new ApiClientError({ status: 503, code: "SERVICE_UNAVAILABLE" }));
      }
      return Promise.resolve(owner === "WORKFLOW" ? listResult([recovery]) : listResult());
    });

    const wrapper = mount(RecoveryPanel);
    await flushPromises();

    expect(recoveryMocks.list).toHaveBeenCalledTimes(4);
    expect(wrapper.text()).toContain("暂无需要关注的更新");
    const alertTab = wrapper.findAll('[role="tab"]').find((tab) => tab.text().includes("告警更新"));
    expect(alertTab).toBeDefined();
    await alertTab!.trigger("click");
    expect(wrapper.text()).toContain("当前环境暂时无法提供这类更新");
    const workflowTab = wrapper.findAll('[role="tab"]').find((tab) => tab.text().includes("审批更新"));
    expect(workflowTab).toBeDefined();
    await workflowTab!.trigger("click");
    expect(wrapper.text()).toContain("接收处理");
  });

  it("keeps a 403 visible instead of marking the route unavailable", async () => {
    recoveryMocks.list.mockImplementation((owner: string) => {
      if (owner === "TICKET") {
        return Promise.reject(new ApiClientError({ status: 403, code: "ACCESS_DENIED" }));
      }
      return Promise.resolve(listResult());
    });

    const wrapper = mount(RecoveryPanel);
    await flushPromises();

    expect(wrapper.text()).toContain("当前账号没有执行此操作的权限");
    expect(wrapper.text()).not.toContain("当前环境暂时无法提供这类更新");
  });

  it("sends the reason and current ETag, then keeps a 202 replay in progress", async () => {
    const wrapper = mount(RecoveryPanel);
    await flushPromises();
    const workflowTab = wrapper.findAll('[role="tab"]').find((tab) => tab.text().includes("审批更新"));
    expect(workflowTab).toBeDefined();
    await workflowTab!.trigger("click");
    const replayButton = wrapper.findAll("button").find((button) => button.text() === "重新处理");
    expect(replayButton).toBeDefined();
    await replayButton!.trigger("click");
    await flushPromises();

    await wrapper.get("#recovery-reason").setValue("已完成依赖核查");
    await wrapper.get("form.recovery-replay-form").trigger("submit");
    await flushPromises();

    expect(recoveryMocks.replay).toHaveBeenCalledWith(
      "WORKFLOW",
      "recovery-1",
      "已完成依赖核查",
      '"lv:1"',
    );
    expect(wrapper.text()).toContain("正在等待处理结果");
    expect(wrapper.text()).toContain("处理中");
  });

  it("uses the stable validation code when the replay reason is empty", async () => {
    const wrapper = mount(RecoveryPanel);
    await flushPromises();
    const workflowTab = wrapper.findAll('[role="tab"]').find((tab) => tab.text().includes("审批更新"));
    await workflowTab!.trigger("click");
    const replayButton = wrapper.findAll("button").find((button) => button.text() === "重新处理");
    await replayButton!.trigger("click");
    await flushPromises();
    await wrapper.get("form.recovery-replay-form").trigger("submit");
    await flushPromises();

    expect(wrapper.text()).toContain("请说明重新处理的原因");
    expect(recoveryMocks.replay).not.toHaveBeenCalled();
  });
});
