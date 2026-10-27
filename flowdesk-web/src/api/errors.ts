/** 统一识别 API 错误，并将业务错误码转换为可展示的中文信息。 */
import type { FieldViolation } from "@/types/api";

/**
 * 表示已标准化的 API 请求错误。
 * 统一保留 HTTP 状态、业务码、关联标识、重试提示、详细信息和响应头供页面判断。
 */
export class ApiClientError extends Error {
  readonly status: number;
  readonly code: string;
  readonly correlationId: string | null;
  readonly retryable: boolean;
  readonly details: FieldViolation[];
  readonly headers: Headers;

  /**
   * 根据错误类型初始化诊断信息和错误消息。
   * 保留服务端关联标识及响应头，便于提示用户并定位请求故障。
   */
  constructor(args: {
    status: number;
    code: string;
    message?: string;
    correlationId?: string | null;
    retryable?: boolean;
    details?: FieldViolation[];
    headers?: Headers;
  }) {
    super(args.message || args.code);
    this.name = "ApiClientError";
    this.status = args.status;
    this.code = args.code;
    this.correlationId = args.correlationId ?? null;
    this.retryable = args.retryable ?? false;
    this.details = args.details ?? [];
    this.headers = args.headers ?? new Headers();
  }
}

/**
 * 表示某项可选能力当前没有启用。
 * 保留能力名称与运行模式，使页面能够提供明确的降级说明。
 */
export class FeatureUnavailableError extends Error {
  readonly feature: string;
  readonly mode: string;

  /**
   * 根据错误类型初始化诊断信息和错误消息。
   * 保留服务端关联标识及响应头，便于提示用户并定位请求故障。
   */
  constructor(feature: string, mode = "disabled") {
    super(`${feature} is not enabled (${mode})`);
    this.name = "FeatureUnavailableError";
    this.feature = feature;
    this.mode = mode;
  }
}

/**
 * 判断未知异常是否为统一 API 错误。
 * 作为类型守卫让调用方安全读取状态码、业务码和关联信息。
 */
export function isApiClientError(error: unknown): error is ApiClientError {
  return error instanceof ApiClientError;
}

/**
 * 判断未知异常是否表示能力未启用。
 * 作为类型守卫供页面区分功能降级与普通请求失败。
 */
export function isFeatureUnavailableError(
  error: unknown,
): error is FeatureUnavailableError {
  return error instanceof FeatureUnavailableError;
}

/**
 * 判断错误是否属于网络或服务暂时不可达。
 * 仅涵盖传输和服务中断，不把权限不足或资源不存在当作临时故障。
 */
export function isTransientServiceError(error: unknown): boolean {
  if (!isApiClientError(error)) return false;
  return (
    error.status === 0 ||
    error.status === 503 ||
    error.status === 504 ||
    ["SERVICE_UNAVAILABLE", "SERVICE_NOT_READY", "DEPENDENCY_UNAVAILABLE", "GATEWAY_TIMEOUT"].includes(
      error.code,
    )
  );
}

/**
 * 判断可选能力能否在当前页面按不可用状态降级。
 * 结合能力、集合或详情上下文识别未挂载端点，并保留合法的未开始计时状态。
 */
export function isCapabilityUnavailable(
  error: unknown,
  scope: "notification" | "recovery" | "sla",
  operation: "collection" | "detail" | "mutation" = "collection",
): boolean {
  if (isFeatureUnavailableError(error) || isTransientServiceError(error)) return true;
  if (!isApiClientError(error)) return false;
  if (scope === "recovery") {
    return operation === "collection" && error.code === "RECOVERY_ROUTE_MISMATCH";
  }
  if (scope === "notification") {
    // A service/gateway that has not mounted the optional collection can only
    // answer with the opaque generic 404. Recipient-specific 404s are kept as
    // ordinary errors for mutation/detail calls.
    return operation === "collection" && error.code === "RESOURCE_NOT_FOUND" && error.status === 404;
  }
  // SLA_NOT_STARTED is a valid business state, not an outage.
  return error.status === 404 && error.code !== "SLA_NOT_STARTED";
}

/**
 * 识别后端为隐藏资源可见性而返回的通用不存在错误。
 * 仅匹配约定状态及业务码，避免吞掉权限和服务故障。
 */
export function isOpaqueResourceNotFound(error: unknown): boolean {
  return isApiClientError(error) && error.status === 404 && error.code === "RESOURCE_NOT_FOUND";
}

/** UI copy is keyed by the stable code; server message is never used for branching. */
export const errorLabels: Record<string, string> = {
  AUTHENTICATION_REQUIRED: "登录状态已失效，请重新登录。",
  AUTHENTICATION_FAILED: "身份验证失败，请重新登录。",
  TOKEN_EXPIRED: "登录已过期，请重新登录。",
  LOGIN_FAILED: "账号或密码不正确。",
  ACCOUNT_INACTIVE: "账号当前不可登录。",
  ACCOUNT_TEMPORARILY_LOCKED: "账号暂时被锁定，请稍后再试。",
  ACCESS_DENIED: "当前账号没有执行此操作的权限。",
  METHOD_NOT_ALLOWED: "当前操作不受支持。",
  MALFORMED_JSON: "提交内容格式有误。",
  RESOURCE_NOT_FOUND: "暂时找不到这项内容，或它已不再对当前账号可见。",
  APPROVAL_TASK_NOT_ASSIGNED: "这项审批当前不可见。",
  NOTIFICATION_RECIPIENT_ONLY: "这条通知当前不可见。",
  RECOVERY_ROUTE_MISMATCH: "该恢复方式暂时不可用。",
  RECOVERY_NOT_REPLAYABLE: "这条更新已不能再次处理。",
  RECOVERY_REASON_REQUIRED: "请说明重新处理的原因。",
  RECOVERY_REPLAY_CONFLICT: "这条更新正在被其他操作处理，请稍后刷新。",
  RECOVERY_ALREADY_REPLAYING: "这条更新正在处理中，请稍后刷新。",
  VALIDATION_FAILED: "提交内容未通过校验。",
  UNKNOWN_FIELD: "请求包含未声明的字段。",
  TENANT_INPUT_FORBIDDEN: "请求内容不符合当前组织范围。",
  INVALID_QUERY_PARAMETER: "筛选条件不符合要求。",
  UNKNOWN_QUERY_PARAMETER: "筛选条件不符合要求。",
  CURSOR_INVALID: "列表已更新，请重新加载。",
  PAGE_TOKEN_INVALID: "列表已更新，请重新加载。",
  SORT_UNSUPPORTED: "当前排序方式暂不支持。",
  PRECONDITION_REQUIRED: "页面信息已过期，请刷新后重试。",
  PRECONDITION_INVALID: "页面信息已过期，请刷新后重试。",
  RESOURCE_VERSION_CONFLICT: "内容已被其他人更新，请刷新后重试。",
  RESOURCE_PRECONDITION_FAILED: "当前内容已发生变化，请刷新后重试。",
  IDEMPOTENCY_KEY_REQUIRED: "请求信息不完整，请重试。",
  IDEMPOTENCY_KEY_INVALID: "请求信息不完整，请重试。",
  IDEMPOTENCY_KEY_REUSED: "这次操作与已有请求不一致，请重新提交。",
  IDEMPOTENCY_IN_PROGRESS: "相同操作仍在处理中，请稍后查看结果。",
  RESOURCE_ALREADY_EXISTS: "相同内容已经存在。",
  STATE_CONFLICT: "当前状态不允许执行此操作。",
  TICKET_STATE_INVALID: "当前工单状态不允许执行此操作。",
  TICKET_NOT_ASSIGNED_TO_ACTOR: "当前账号不是该工单负责人。",
  TICKET_COMMENT_FORBIDDEN: "当前账号不能评论此工单。",
  ASSIGNEE_NOT_ELIGIBLE: "所选处理人暂不可用。",
  TICKET_SCOPE_FORBIDDEN: "当前账号不能查看更大范围的工单。",
  TICKET_TRANSFER_FORBIDDEN: "当前账号不能转派这张工单。",
  TICKET_TERMINAL: "工单已结束，不能继续追加操作。",
  TICKET_REQUESTER_ONLY: "只有原申请人可以执行此操作。",
  TICKET_ORIGIN_NOT_CLOSABLE: "此类工单不能手动关闭。",
  TICKET_ORIGIN_NOT_REOPENABLE: "此类工单不能重新打开。",
  CATALOG_ITEM_NOT_AVAILABLE: "所选服务项目当前不可申请。",
  CATALOG_CODE_EXISTS: "类型编码已存在，请查看现有类型或使用其他编码。",
  CATALOG_CODE_IMMUTABLE: "类型编码创建后不能修改，请调整名称或新增类型。",
  WORKFLOW_VERSION_REQUIRED: "请选择已发布的高风险审批版本。",
  WORKFLOW_VERSION_INVALID: "所选审批版本不可用，请重新选择已发布的高风险版本。",
  WORKFLOW_VERSION_UNAVAILABLE: "暂时无法校验审批版本，请稍后再保存。",
  IAM_SELF_ACCOUNT_REQUIRED: "不能停用当前登录账号，请由另一位组织管理员办理交接。",
  IAM_SELF_ADMIN_REQUIRED: "不能移除自己的组织管理员角色，请由另一位管理员办理交接。",
  IAM_CORE_PERMISSION_REQUIRED: "组织管理员的基础管理权限需要保留，请通过分配成员角色调整管理人员。",
  USERNAME_ALREADY_EXISTS: "该账号已经存在，请使用其他账号名称。",
  PERMISSION_CEILING_VIOLATION: "此权限不适用于所选角色，请为成员分配对应业务角色。",
  SLA_POLICY_NOT_AVAILABLE: "所选处理时效当前不可用。",
  CATALOG_FIELD_IMMUTABLE: "该服务项目的基本信息不能修改。",
  PREVIOUS_ATTEMPT_INVALID: "只能关联本人同组织的已拒绝工单。",
  IAM_ELIGIBILITY_UNAVAILABLE: "无法确认处理人资格，请稍后重试。",
  WORKFLOW_DRAFT_INVALID: "审批规则草稿尚未通过最新校验。",
  WORKFLOW_DRAFT_ALREADY_EXISTS: "当前已有一份可编辑的审批规则。",
  WORKFLOW_DRAFT_NOT_EDITABLE: "这份审批规则已经不能修改。",
  WORKFLOW_PUBLICATION_CONFLICT: "审批规则已被其他操作发布，请刷新。",
  WORKFLOW_SCOPE_FORBIDDEN: "当前账号不能查看更大范围的审批信息。",
  WORKFLOW_APPROVER_NOT_ELIGIBLE: "所选审批人暂不可用。",
  APPROVAL_DECISION_CONFLICT: "该审批任务已被作出不同决定。",
  APPROVAL_TASK_NOT_PENDING: "该审批任务已不再等待处理。",
  SLA_NOT_STARTED: "该工单的处理时效尚未开始。",
  SERVICE_UNAVAILABLE: "目标服务暂时不可用。",
  DEPENDENCY_UNAVAILABLE: "必要服务暂时不可用，操作结果尚未确认。",
  GATEWAY_TIMEOUT: "请求等待超时，结果尚未确认，请先刷新确认。",
  SERVICE_NOT_READY: "服务尚未准备好接收请求。",
  REQUEST_TOO_LARGE: "提交内容过大，请缩短后重试。",
  MEDIA_TYPE_UNSUPPORTED: "提交格式暂不支持。",
  CONTENT_ENCODING_UNSUPPORTED: "提交内容的编码暂不支持。",
  INTERNAL_ERROR: "服务发生未预期错误。",
};

/**
 * 把统一异常映射为面向用户的中文说明。
 * 优先使用稳定业务码，未知错误回退为通用提示并保留上层诊断信息。
 */
export function errorLabel(error: unknown): string {
  if (!isApiClientError(error)) return "请求失败，请稍后重试。";
  return errorLabels[error.code] ?? "请求失败，请稍后重试。";
}
