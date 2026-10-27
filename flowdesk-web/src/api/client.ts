/** 统一 API 传输层：处理鉴权、追踪标识、幂等键、版本条件和标准响应。 */
import { ApiClientError } from './errors'
import { featureFlags } from './features'
import { mockApi } from '@/mocks/mockApi'
import type { ApiMeta, ErrorEnvelope, SuccessEnvelope } from '@/types/api'

type FetchLike = (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>

export interface RequestOptions {
  method?: string
  body?: unknown
  query?: Record<string, string | number | boolean | null | undefined>
  headers?: Record<string, string>
  auth?: boolean
  idempotencyKey?: string
  ifMatch?: string
  ifNoneMatch?: string
  signal?: AbortSignal
}

export interface RequestResult<T> {
  data: T
  meta: ApiMeta
  status: number
  headers: Headers
}

/**
 * 提供当前内存会话的访问令牌。
 * 默认返回空值，由认证模块注册实际读取逻辑，避免请求层直接依赖状态仓库。
 */
let tokenProvider: () => string | null = () => null
type AuthFailureHandler = (error: ApiClientError) => void | Promise<void>
/**
 * 保存认证失效时的通知回调。
 * 默认不执行操作，由应用启动流程接入会话清理和登录跳转。
 */
let authFailureHandler: AuthFailureHandler = () => undefined

/**
 * 注册访问令牌的读取函数。
 * 每次发送受保护请求时再读取最新令牌，使登录和退出后的会话状态立即生效。
 */
export function configureTokenProvider(provider: () => string | null): void {
  tokenProvider = provider
}

/**
 * 由应用入口连接内存会话与路由；通过回调解耦，避免传输层直接依赖状态仓库和路由。
 */
export function configureAuthFailureHandler(handler: AuthFailureHandler): void {
  authFailureHandler = handler
}

/**
 * 识别 HTTP 401 或认证相关业务错误并通知认证模块。
 * 回调自身的异常会被忽略，以保留原始请求错误供页面处理。
 */
export function notifyAuthFailure(error: unknown): void {
  if (!(error instanceof ApiClientError)) return
  if (error.status !== 401 && !['AUTHENTICATION_REQUIRED', 'TOKEN_EXPIRED', 'AUTHENTICATION_FAILED'].includes(error.code)) return
  try {
    void Promise.resolve(authFailureHandler(error)).catch(() => undefined)
  } catch {
    // 导航回调失败时保留原始 API 错误，避免掩盖真正的请求失败原因。
  }
}

/**
 * 生成请求关联标识。
 * 优先使用浏览器 UUID 能力，缺失时组合时间和随机值，供日志追踪与幂等请求使用。
 */
function requestId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') return crypto.randomUUID()
  return `fd-${Date.now()}-${Math.random().toString(36).slice(2)}`
}

/**
 * 为一次新的业务写入意图生成幂等键。
 * 同一意图的重试应复用已生成的键，而不是再次调用本方法。
 */
export function newIdempotencyKey(): string {
  return requestId()
}

/**
 * 将查询参数编码为 URL 查询字符串。
 * 跳过未定义、空值和空字符串，保留数字及布尔值等有效筛选条件。
 */
function makeQuery(query: RequestOptions['query']): string {
  if (!query) return ''
  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(query)) {
    if (value === undefined || value === null || value === '') continue
    params.set(key, String(value))
  }
  const encoded = params.toString()
  return encoded ? `?${encoded}` : ''
}

/**
 * 安全解析响应正文。
 * 空正文或无法解析的 JSON 返回空值，由上层统一决定成功响应或错误响应的处理方式。
 */
async function parseJson(response: Response): Promise<unknown> {
  const text = await response.text()
  if (!text) return null
  try { return JSON.parse(text) } catch { return null }
}

/**
 * 提取标准响应中的元数据。
 * 缺少有效元数据时提供基础关联信息结构，保持调用方读取方式一致。
 */
function responseMeta(value: unknown): ApiMeta {
  if (value && typeof value === 'object' && 'meta' in value && (value as { meta?: ApiMeta }).meta) {
    return (value as { meta: ApiMeta }).meta
  }
  return { correlationId: '' }
}

// 身份与租户信息由令牌确定，禁止调用方通过自定义请求头伪造。
const FORBIDDEN_CLIENT_HEADERS = new Set([
  'authorization',
  'x-tenant-id',
  'x-tenant',
  'x-user-id',
  'x-actor-type',
  'x-actor-id',
])

/**
 * 清理调用方传入的请求头。
 * 移除自行提供的认证及租户、用户、操作者身份头，身份由可信会话和后端确定。
 */
function safeHeaders(input: Record<string, string> | undefined): Record<string, string> {
  if (!input) return {}
  return Object.fromEntries(
    Object.entries(input).filter(([name]) => !FORBIDDEN_CLIENT_HEADERS.has(name.toLowerCase())),
  )
}

/**
 * 封装统一的前端 HTTP 访问边界。
 * 集中处理认证、关联标识、幂等键、乐观锁、标准响应和可选模拟请求。
 */
export class ApiClient {
  /**
   * 初始化 API 基础路径和请求发送函数。
   * 未传入替代实现时绑定全局 fetch，便于正常运行和测试注入。
   */
  constructor(
    private readonly basePath = '/api/v1',
    // 绑定浏览器全局对象，避免通过实例属性调用 fetch 时丢失正确的接收者。
    private readonly fetcher: FetchLike = fetch.bind(globalThis)
  ) {}

  /**
   * 通过统一执行流程发送真实 API 请求。
   * 保留泛型结果类型，并由执行流程处理请求头、响应解析和错误转换。
   */
  async request<T>(path: string, options: RequestOptions = {}): Promise<RequestResult<T>> {
    return this.execute<T>(path, options, featureFlags.fullMock)
  }

  /**
   * 对显式配置为模拟模式的功能使用内存传输，同时保留与网关调用一致的请求结构。
   */
  async requestMock<T>(path: string, options: RequestOptions = {}): Promise<RequestResult<T>> {
    if (import.meta.env.PROD) throw new Error('Mock transport is unavailable in production')
    return this.execute<T>(path, options, true)
  }

  /**
   * 执行完整请求生命周期并返回数据、响应头和元数据。
   * 集中设置认证与并发控制头，将网络故障和后端错误转换为统一异常。
   */
  private async execute<T>(path: string, options: RequestOptions, useMock: boolean): Promise<RequestResult<T>> {
    const method = (options.method ?? 'GET').toUpperCase()
    const query = makeQuery(options.query)
    const headers: Record<string, string> = {
      Accept: 'application/json',
      'X-Correlation-Id': requestId(),
      ...safeHeaders(options.headers),
    }
    if (options.auth !== false) {
      const token = tokenProvider()
      if (token) headers.Authorization = `Bearer ${token}`
    }
    if (options.body !== undefined) {
      headers['Content-Type'] = 'application/json; charset=utf-8'
    }
    // 幂等键防止重复写入，If-Match / If-None-Match 约束写入时资源的版本或存在性。
    if (options.idempotencyKey) headers['Idempotency-Key'] = options.idempotencyKey
    if (options.ifMatch) headers['If-Match'] = options.ifMatch
    if (options.ifNoneMatch) headers['If-None-Match'] = options.ifNoneMatch

    if (!import.meta.env.PROD && useMock) {
      try {
        return await mockApi.request<T>(`${this.basePath}${path}${query}`, {
          method,
          headers,
          body: options.body,
        })
      } catch (error) {
        if (options.auth !== false) notifyAuthFailure(error)
        throw error
      }
    }

    let response: Response
    try {
      response = await this.fetcher(`${this.basePath}${path}${query}`, {
        method,
        headers,
        body: options.body === undefined ? undefined : JSON.stringify(options.body),
        signal: options.signal,
        credentials: 'same-origin'
      })
    } catch (cause) {
      throw new ApiClientError({
        status: 0,
        code: 'SERVICE_UNAVAILABLE',
        message: cause instanceof Error ? cause.message : 'Network request failed',
        retryable: true
      })
    }

    const payload = await parseJson(response)
    if (!response.ok) {
      const body = payload as Partial<ErrorEnvelope> | null
      const error = body?.error
      const apiError = new ApiClientError({
        status: response.status,
        code: error?.code ?? statusCode(response.status),
        message: error?.message,
        correlationId: error?.correlationId ?? response.headers.get('X-Correlation-Id'),
        retryable: error?.retryable,
        details: error?.details,
        headers: response.headers
      })
      if (options.auth !== false) notifyAuthFailure(apiError)
      throw apiError
    }
    const envelope = payload as SuccessEnvelope<T>
    return {
      data: envelope?.data as T,
      meta: responseMeta(envelope),
      status: response.status,
      headers: response.headers
    }
  }

  /**
   * 发送只读 GET 请求。
   * 查询参数及其他选项交由统一请求流程编码和处理。
   */
  get<T>(path: string, options: Omit<RequestOptions, 'method' | 'body'> = {}) {
    return this.request<T>(path, { ...options, method: 'GET' })
  }

  /**
   * 发送带业务载荷的 POST 请求。
   * 可通过选项传递幂等键及版本条件，返回标准化 API 结果。
   */
  post<T>(path: string, body?: unknown, options: Omit<RequestOptions, 'method' | 'body'> = {}) {
    return this.request<T>(path, { ...options, method: 'POST', body })
  }

  /**
   * 发送局部更新 PATCH 请求。
   * 保留调用方提供的版本条件和幂等信息，由统一请求流程处理响应。
   */
  patch<T>(path: string, body: unknown, options: Omit<RequestOptions, 'method'> = {}) {
    return this.request<T>(path, { ...options, method: 'PATCH', body })
  }

  /**
   * 发送资源替换或绑定更新 PUT 请求。
   * 支持创建条件及版本条件，沿用统一认证和错误处理规则。
   */
  put<T>(path: string, body: unknown, options: Omit<RequestOptions, 'method'> = {}) {
    return this.request<T>(path, { ...options, method: 'PUT', body })
  }
}

/**
 * 在后端没有提供业务错误码时按 HTTP 状态推导稳定错误码。
 * 页面可据此展示一致的中文错误提示。
 */
function statusCode(status: number): string {
  if (status === 400) return 'VALIDATION_FAILED'
  if (status === 401) return 'AUTHENTICATION_REQUIRED'
  if (status === 403) return 'ACCESS_DENIED'
  if (status === 404) return 'RESOURCE_NOT_FOUND'
  if (status === 409) return 'STATE_CONFLICT'
  if (status === 412) return 'RESOURCE_VERSION_CONFLICT'
  if (status === 428) return 'PRECONDITION_REQUIRED'
  if (status === 413) return 'REQUEST_TOO_LARGE'
  if (status === 415) return 'MEDIA_TYPE_UNSUPPORTED'
  if (status === 422) return 'VALIDATION_FAILED'
  if (status === 503) return 'SERVICE_UNAVAILABLE'
  if (status === 504) return 'GATEWAY_TIMEOUT'
  return 'INTERNAL_ERROR'
}

export const apiClient = new ApiClient()
