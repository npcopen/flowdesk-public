import { describe, expect, it } from "vitest";
import { mount } from "@vue/test-utils";
import { ApiClientError } from "@/api/errors";
import ErrorNotice from "./ErrorNotice.vue";

describe("ErrorNotice", () => {
  it("uses the stable error code instead of server wording", () => {
    const wrapper = mount(ErrorNotice, {
      props: {
        error: new ApiClientError({
          status: 412,
          code: "RESOURCE_VERSION_CONFLICT",
          message: "internal server wording",
          correlationId: "correlation-1",
        }),
      },
    });

    expect(wrapper.text()).toContain("内容已被其他人更新，请刷新后重试。");
    expect(wrapper.text()).toContain("问题已记录，如需帮助可联系支持人员。");
    expect(wrapper.text()).not.toContain("internal server wording");
  });
});
