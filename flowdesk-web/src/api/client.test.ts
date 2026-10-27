import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiClient, configureAuthFailureHandler, configureTokenProvider } from './client'
import { ApiClientError } from './errors'

describe('ApiClient', () => {
  beforeEach(() => {
    configureTokenProvider(() => null)
    configureAuthFailureHandler(() => undefined)
  })

  it('uses the same-origin API path and protocol headers', async () => {
    configureTokenProvider(() => 'memory-token')
    const fetcher = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => new Response(JSON.stringify({ data: { ok: true }, meta: { correlationId: 'c-1' } }), { status: 202, headers: { ETag: '"lv:1"' } }))
    const client = new ApiClient('/api/v1', fetcher)
    const result = await client.post('/tickets', { subject: 'test' }, { ifMatch: '"lv:0"', idempotencyKey: '1234567890abcdef' })
    expect(String(fetcher.mock.calls[0][0])).toBe('/api/v1/tickets')
    const headers = new Headers(fetcher.mock.calls[0][1]?.headers)
    expect(headers.get('Authorization')).toBe('Bearer memory-token')
    expect(headers.get('If-Match')).toBe('"lv:0"')
    expect(headers.get('Idempotency-Key')).toBe('1234567890abcdef')
    expect(result.status).toBe(202)
    expect(result.meta.correlationId).toBe('c-1')
  })

  it('branches by stable error code and keeps response details opaque', async () => {
    configureTokenProvider(() => null)
    const fetcher = vi.fn(async () => new Response(JSON.stringify({ error: { code: 'RESOURCE_VERSION_CONFLICT', message: 'localized', correlationId: 'c-2', details: [] } }), { status: 412, headers: { 'X-Correlation-Id': 'c-2' } }))
    const client = new ApiClient('/api/v1', fetcher)
    await expect(client.get('/tickets/1')).rejects.toMatchObject({ status: 412, code: 'RESOURCE_VERSION_CONFLICT', correlationId: 'c-2' })
  })

  it('sanitizes caller-controlled identity headers', async () => {
    configureTokenProvider(() => 'trusted-memory-token')
    const fetcher = vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) =>
      new Response(JSON.stringify({ data: {}, meta: { correlationId: 'c-3' } }), { status: 200 }))
    const client = new ApiClient('/api/v1', fetcher)

    await client.get('/users/me', {
      headers: {
        Authorization: 'Bearer forged-token',
        'X-Tenant-Id': 'forged-tenant',
        'X-User-Id': 'forged-user',
        'X-Actor-Type': 'SERVICE',
        'X-Actor-Id': 'forged-actor',
        'X-Client-Trace': 'kept',
      },
    })

    const headers = new Headers(fetcher.mock.calls[0][1]?.headers)
    expect(headers.get('Authorization')).toBe('Bearer trusted-memory-token')
    expect(headers.get('X-Tenant-Id')).toBeNull()
    expect(headers.get('X-User-Id')).toBeNull()
    expect(headers.get('X-Actor-Type')).toBeNull()
    expect(headers.get('X-Actor-Id')).toBeNull()
    expect(headers.get('X-Client-Trace')).toBe('kept')
  })

  it('does not forward an authorization header on anonymous requests', async () => {
    configureTokenProvider(() => 'trusted-memory-token')
    const fetcher = vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) =>
      new Response(JSON.stringify({ data: {}, meta: { correlationId: 'c-4' } }), { status: 200 }))
    const client = new ApiClient('/api/v1', fetcher)

    await client.post('/auth/login', { username: 'alice' }, {
      auth: false,
      headers: { Authorization: 'Bearer forged-token', 'X-User-Id': 'forged-user' },
    })

    const headers = new Headers(fetcher.mock.calls[0][1]?.headers)
    expect(headers.get('Authorization')).toBeNull()
    expect(headers.get('X-User-Id')).toBeNull()
  })

  it('notifies the application when an authenticated request returns 401', async () => {
    configureTokenProvider(() => 'memory-token')
    const authFailure = vi.fn()
    configureAuthFailureHandler(authFailure)
    const fetcher = vi.fn(async () =>
      new Response(JSON.stringify({
        error: { code: 'AUTHENTICATION_REQUIRED', message: 'server wording' },
      }), { status: 401, headers: { 'X-Correlation-Id': 'c-5' } }))
    const client = new ApiClient('/api/v1', fetcher)

    await expect(client.get('/users/me')).rejects.toMatchObject({
      status: 401,
      code: 'AUTHENTICATION_REQUIRED',
      correlationId: 'c-5',
    })
    expect(authFailure).toHaveBeenCalledTimes(1)
    expect(authFailure.mock.calls[0][0]).toBeInstanceOf(ApiClientError)
  })

  it('uses the API contract code for a 415 fallback response', async () => {
    const fetcher = vi.fn(async () => new Response(null, { status: 415 }))
    const client = new ApiClient('/api/v1', fetcher)

    await expect(client.get('/tickets')).rejects.toMatchObject({
      status: 415,
      code: 'MEDIA_TYPE_UNSUPPORTED',
    })
  })
})
