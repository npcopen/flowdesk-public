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

let tokenProvider: () => string | null = () => null
type AuthFailureHandler = (error: ApiClientError) => void | Promise<void>
let authFailureHandler: AuthFailureHandler = () => undefined

export function configureTokenProvider(provider: () => string | null): void {
  tokenProvider = provider
}

/**
 * The application wires this to its in-memory auth store and router. Keeping the
 * hook here avoids importing either module into the transport layer.
 */
export function configureAuthFailureHandler(handler: AuthFailureHandler): void {
  authFailureHandler = handler
}

export function notifyAuthFailure(error: unknown): void {
  if (!(error instanceof ApiClientError)) return
  if (error.status !== 401 && !['AUTHENTICATION_REQUIRED', 'TOKEN_EXPIRED', 'AUTHENTICATION_FAILED'].includes(error.code)) return
  try {
    void Promise.resolve(authFailureHandler(error)).catch(() => undefined)
  } catch {
    // A navigation callback must never hide the original API error.
  }
}

function requestId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') return crypto.randomUUID()
  return `fd-${Date.now()}-${Math.random().toString(36).slice(2)}`
}

export function newIdempotencyKey(): string {
  return requestId()
}

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

async function parseJson(response: Response): Promise<unknown> {
  const text = await response.text()
  if (!text) return null
  try { return JSON.parse(text) } catch { return null }
}

function responseMeta(value: unknown): ApiMeta {
  if (value && typeof value === 'object' && 'meta' in value && (value as { meta?: ApiMeta }).meta) {
    return (value as { meta: ApiMeta }).meta
  }
  return { correlationId: '' }
}

const FORBIDDEN_CLIENT_HEADERS = new Set([
  'authorization',
  'x-tenant-id',
  'x-tenant',
  'x-user-id',
  'x-actor-type',
  'x-actor-id',
])

function safeHeaders(input: Record<string, string> | undefined): Record<string, string> {
  if (!input) return {}
  return Object.fromEntries(
    Object.entries(input).filter(([name]) => !FORBIDDEN_CLIENT_HEADERS.has(name.toLowerCase())),
  )
}

export class ApiClient {
  constructor(
    private readonly basePath = '/api/v1',
    // Keep the browser's fetch receiver intact when the client invokes it
    // through the instance property.
    private readonly fetcher: FetchLike = fetch.bind(globalThis)
  ) {}

  async request<T>(path: string, options: RequestOptions = {}): Promise<RequestResult<T>> {
    return this.execute<T>(path, options, featureFlags.fullMock)
  }

  /**
   * Uses the in-memory transport while preserving the same request envelope as
   * Gateway calls. This is only used for explicitly flagged, unavailable slices.
   */
  async requestMock<T>(path: string, options: RequestOptions = {}): Promise<RequestResult<T>> {
    return this.execute<T>(path, options, true)
  }

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
    if (options.idempotencyKey) headers['Idempotency-Key'] = options.idempotencyKey
    if (options.ifMatch) headers['If-Match'] = options.ifMatch
    if (options.ifNoneMatch) headers['If-None-Match'] = options.ifNoneMatch

    if (useMock) {
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

  get<T>(path: string, options: Omit<RequestOptions, 'method' | 'body'> = {}) {
    return this.request<T>(path, { ...options, method: 'GET' })
  }

  post<T>(path: string, body?: unknown, options: Omit<RequestOptions, 'method' | 'body'> = {}) {
    return this.request<T>(path, { ...options, method: 'POST', body })
  }

  patch<T>(path: string, body: unknown, options: Omit<RequestOptions, 'method'> = {}) {
    return this.request<T>(path, { ...options, method: 'PATCH', body })
  }

  put<T>(path: string, body: unknown, options: Omit<RequestOptions, 'method'> = {}) {
    return this.request<T>(path, { ...options, method: 'PUT', body })
  }
}

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
