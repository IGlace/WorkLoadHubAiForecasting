import type { ApiRequest, ApiResponse } from '../shared/ipc'

function describeDetail(detail: unknown): string {
  if (typeof detail === 'string') return detail
  if (Array.isArray(detail)) {
    return detail
      .map((d: unknown) => {
        const item = d as { loc?: unknown[]; msg?: string }
        const field = Array.isArray(item.loc) ? String(item.loc[item.loc.length - 1]) : ''
        return field ? `${field}: ${item.msg ?? ''}` : (item.msg ?? '')
      })
      .join('; ')
  }
  return JSON.stringify(detail)
}

export class ApiClient {
  constructor(private readonly baseUrl: string, private readonly token: string, private readonly fetchFn: typeof fetch = fetch) {}

  async request(req: ApiRequest): Promise<ApiResponse> {
    const headers: Record<string, string> = { 'X-WHF-Token': this.token, Accept: 'application/json' }
    const init: RequestInit = { method: req.method, headers }
    if (req.body !== undefined) { headers['Content-Type'] = 'application/json'; init.body = JSON.stringify(req.body) }
    let res: Response
    try { res = await this.fetchFn(`${this.baseUrl}${req.path}`, init) }
    catch (err) { return { ok: false, status: 0, error: err instanceof Error ? err.message : String(err) } }
    const text = await res.text()
    let data: unknown = null
    if (text) { try { data = JSON.parse(text) } catch { data = text } }
    if (!res.ok) {
      const detail = typeof data === 'object' && data !== null && 'detail' in data ? (data as { detail: unknown }).detail : data
      return { ok: false, status: res.status, error: describeDetail(detail) || `HTTP ${res.status}` }
    }
    return { ok: true, status: res.status, data }
  }
}
