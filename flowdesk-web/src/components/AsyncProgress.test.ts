import { describe, expect, it } from "vitest";
import { mount } from "@vue/test-utils";
import AsyncProgress from "./AsyncProgress.vue";

describe("AsyncProgress", () => {
  it("shows business labels for pending cross-service effects", () => {
    const wrapper = mount(AsyncProgress, {
      props: {
        effects: [
          { kind: "WORKFLOW_START", state: "REQUESTED", relatedRefId: null },
          {
            kind: "INCIDENT_TICKET_LINK",
            state: "RECOVERY_REQUIRED",
            relatedRefId: "internal-id",
          },
        ],
      },
    });

    expect(wrapper.text()).toContain("正在更新");
    expect(wrapper.text()).toContain("审批处理");
    expect(wrapper.text()).toContain("关联工单");
    expect(wrapper.text()).toContain("正在处理");
    expect(wrapper.text()).toContain("等待恢复");
    expect(wrapper.text()).not.toContain("internal-id");
  });
});
