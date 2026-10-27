import { describe, expect, it } from "vitest";
import { resolveFeatureFlags } from "./features";

const emptyEnv = {
  VITE_E2E_BACKEND: undefined,
  VITE_NOTIFICATION_MODE: undefined,
  VITE_RECOVERY_MODE: undefined,
  VITE_SLA_MODE: undefined,
};

describe("feature flag resolution", () => {
  it("uses the real API by default", () => {
    expect(resolveFeatureFlags(emptyEnv, false)).toMatchObject({
      notification: "api",
      recovery: "api",
      sla: "readonly-api",
      fullMock: false,
      productionBuild: false,
    });
  });

  it("enables the local transport only for an explicit mock profile", () => {
    expect(
      resolveFeatureFlags({ ...emptyEnv, VITE_E2E_BACKEND: "mock" }, false),
    ).toMatchObject({
      notification: "mock",
      recovery: "mock",
      sla: "mock",
      fullMock: true,
    });
  });

  it("forces real APIs in production even when every mock override is present", () => {
    expect(
      resolveFeatureFlags(
        {
          ...emptyEnv,
          VITE_E2E_BACKEND: "mock",
          VITE_NOTIFICATION_MODE: "mock",
          VITE_RECOVERY_MODE: "mock",
          VITE_SLA_MODE: "mock",
        },
        true,
      ),
    ).toMatchObject({
      notification: "api",
      recovery: "api",
      sla: "readonly-api",
      fullMock: false,
      productionBuild: true,
    });
  });

  it("keeps an explicit disabled switch available during rollout", () => {
    expect(
      resolveFeatureFlags(
        {
          ...emptyEnv,
          VITE_NOTIFICATION_MODE: "disabled",
          VITE_RECOVERY_MODE: "disabled",
          VITE_SLA_MODE: "disabled",
        },
        true,
      ),
    ).toMatchObject({
      notification: "disabled",
      recovery: "disabled",
      sla: "disabled",
    });
  });
});
