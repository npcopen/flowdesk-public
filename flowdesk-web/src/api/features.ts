/** 根据构建环境决定可选功能使用真实接口、本地模拟或禁用模式。 */
import type { FeatureMode } from "@/types/api";

export type SlaMode = "readonly-api" | "mock" | "disabled";
export type FeatureName = "notification" | "recovery" | "sla";

type FeatureEnv = Pick<
  ImportMetaEnv,
  "VITE_E2E_BACKEND" | "VITE_NOTIFICATION_MODE" | "VITE_RECOVERY_MODE" | "VITE_SLA_MODE"
>;

/**
 * 把环境变量解析为普通可选能力模式。
 * 仅接受约定取值，其他值回退到调用方提供的默认模式。
 */
function mode(value: string | undefined, fallback: FeatureMode): FeatureMode {
  return value === "api" || value === "mock" || value === "disabled"
    ? value
    : fallback;
}

/**
 * 解析 SLA 能力的运行模式。
 * 对白名单之外的值使用默认配置，避免无效环境变量产生未定义行为。
 */
function slaMode(value: string | undefined, fallback: SlaMode): SlaMode {
  return value === "readonly-api" || value === "mock" || value === "disabled"
    ? value
    : fallback;
}

/**
 * 根据部署配置解析可选功能。显式 disabled 用于隐藏尚未上线的能力，
 * 生产构建会排除浏览器模拟传输，防止正式入口意外使用演示数据。
 */
export function resolveFeatureFlags(
  env: FeatureEnv = import.meta.env,
  productionBuild = import.meta.env.PROD,
) {
  const mockProfile = !productionBuild && env.VITE_E2E_BACKEND === "mock";
  const notificationDefault: FeatureMode = mockProfile ? "mock" : "api";
  const recoveryDefault: FeatureMode = mockProfile ? "mock" : "api";
  const slaDefault: SlaMode = mockProfile ? "mock" : "readonly-api";

  // 生产环境忽略 mock 覆盖；保留显式 disabled 以支持后端分批上线。
  const notificationValue =
    productionBuild && env.VITE_NOTIFICATION_MODE === "mock"
      ? "api"
      : env.VITE_NOTIFICATION_MODE;
  const recoveryValue =
    productionBuild && env.VITE_RECOVERY_MODE === "mock"
      ? "api"
      : env.VITE_RECOVERY_MODE;
  const slaValue =
    productionBuild && env.VITE_SLA_MODE === "mock"
      ? "readonly-api"
      : env.VITE_SLA_MODE;

  return {
    notification: mode(notificationValue, notificationDefault),
    recovery: mode(recoveryValue, recoveryDefault),
    sla: slaMode(slaValue, slaDefault),
    fullMock: mockProfile,
    productionBuild,
  } as const;
}

export const featureFlags = resolveFeatureFlags();

/**
 * 按能力名称和当前运行模式生成中文说明。
 * 供页面解释能力关闭、模拟或其他受限状态。
 */
export function featureNotice(modeValue: FeatureMode | SlaMode): string | null {
  if (modeValue === "mock") return "当前显示本地数据，不会同步正式系统。";
  if (modeValue === "disabled") return "当前环境暂时无法提供此功能。";
  return null;
}

/**
 * 将内部能力标识转换为中文名称。
 * 统一通知、消息恢复和 SLA 等能力在页面中的展示文案。
 */
export function capabilityName(name: FeatureName): string {
  return {
    notification: "通知",
    recovery: "更新处理",
    sla: "处理时效",
  }[name];
}
