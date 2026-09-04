import { describe, expect, it, vi } from 'vitest'
import { ApiClient } from '../api-client'

describe('ApiClient', () => {
  it('sends the token header and JSON body, returns parsed data', async () => {
    const fetchFn = vi.fn(async () => new Response('{"id": 3}', { status: 200, headers: { 'content-type': 'application/json' } }))
    const client = new ApiClient('http://127.0.0.1:6001', 'tok', fetchFn as unknown as typeof fetch)
    const res = await client.request({ method: 'POST', path: '/projects', body: { name: 'x' } })
    expect(res).toEqual({ ok: true, status: 200, data: { id: 3 } })
    const [url, init] = fetchFn.mock.calls[0] as unknown as [string, RequestInit]
    expect(url).toBe('http://127.0.0.1:6001/projects')
    expect(init.method).toBe('POST')
    expect(new Headers(init.headers).get('X-WHF-Token')).toBe('tok')
    expect(init.body).toBe('{"name":"x"}')
  })
  it('maps HTTP errors to the FastAPI detail', async () => {
    const fetchFn = vi.fn(async () => new Response('{"detail": "run 9 not found"}', { status: 404 }))
    const client = new ApiClient('http://127.0.0.1:6001', 'tok', fetchFn as unknown as typeof fetch)
    expect(await client.request({ method: 'GET', path: '/runs/9' })).toEqual({ ok: false, status: 404, error: 'run 9 not found' })
  })
  it('maps validation errors to a readable message', async () => {
    const body = '{"detail": [{"loc": ["body", "deadline"], "msg": "deadline must be after start_date"}]}'
    const fetchFn = vi.fn(async () => new Response(body, { status: 422 }))
    const client = new ApiClient('http://127.0.0.1:6001', 'tok', fetchFn as unknown as typeof fetch)
    expect(await client.request({ method: 'GET', path: '/x' })).toEqual({ ok: false, status: 422, error: 'deadline: deadline must be after start_date' })
  })
  it('maps network failures to status 0', async () => {
    const fetchFn = vi.fn(async () => { throw new Error('ECONNREFUSED') })
    const client = new ApiClient('http://127.0.0.1:6001', 'tok', fetchFn as unknown as typeof fetch)
    expect(await client.request({ method: 'GET', path: '/meta' })).toEqual({ ok: false, status: 0, error: 'ECONNREFUSED' })
  })
})
