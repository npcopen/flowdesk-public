import type { FeatureMode } from "@/types/api";

export type SlaMode = "readonly-api" | "mock" | "disabled";
export type FeatureName = "notification" | "recovery" | "sla";

type FeatureEnv = Pick<
  ImportMetaEnv,
  "VITE_E2E_BACKEND" | "VITE_NOTIFICATION_MODE" | "VITE_RECOVERY_MODE" | "VITE_SLA_MODE"
>;

function mode(value: string | undefined, fallback: FeatureMode): FeatureMode {
  return value === "api" || value === "mock" || value === "disabled"
    ? value
    : fallback;
}

function slaMode(value: string | undefined, fallback: SlaMode): SlaMode {
  return value === "readonly-api" || value === "mock" || value === "disabled"
    ? value
    : fallback;
}

/**
 * Resolve optional M8 surfaces from the deployment profile. An explicit
 * `disabled` remains meaningful in development, while a Gateway build can
 * never accidentally ship the browser-only mock transport.
 */
export function resolveFeatureFlags(
  env: FeatureEnv = import.meta.env,
  gatewayBuild =
    typeof __FLOWDESK_GATEWAY_MODE__ !== "undefined" && __FLOWDESK_GATEWAY_MODE__,
) {
  const mockProfile = !gatewayBuild && env.VITE_E2E_BACKEND === "mock";
  const notificationDefault: FeatureMode = mockProfile ? "mock" : "api";
  const recoveryDefault: FeatureMode = mockProfile ? "mock" : "api";
  const slaDefault: SlaMode = mockProfile ? "mock" : "readonly-api";

  // Gateway assets are the production same-origin bundle. Ignore only mock
  // overrides there; retaining an explicit disabled switch lets an operator
  // hide a capability while its backend is being rolled out.
  const notificationValue =
    gatewayBuild && env.VITE_NOTIFICATION_MODE === "mock"
      ? "api"
      : env.VITE_NOTIFICATION_MODE;
  const recoveryValue =
    gatewayBuild && env.VITE_RECOVERY_MODE === "mock"
      ? "api"
      : env.VITE_RECOVERY_MODE;
  const slaValue =
    gatewayBuild && env.VITE_SLA_MODE === "mock"
      ? "readonly-api"
      : env.VITE_SLA_MODE;

  return {
    notification: mode(notificationValue, notificationDefault),
    recovery: mode(recoveryValue, recoveryDefault),
    sla: slaMode(slaValue, slaDefault),
    fullMock: mockProfile,
    gatewayBuild,
  } as const;
}

export const featureFlags = resolveFeatureFlags();

export function featureNotice(modeValue: FeatureMode | SlaMode): string | null {
  if (modeValue === "mock") return "当前显示本地数据，不会同步正式系统。";
  if (modeValue === "disabled") return "当前环境暂时无法提供此功能。";
  return null;
}

export function capabilityName(name: FeatureName): string {
  return {
    notification: "通知",
    recovery: "更新处理",
    sla: "处理时效",
  }[name];
}
