import { describe, expect, it } from 'vitest'
import { ApiError, toApiError } from './api'

describe('the API client', () => {
  it('turns a {code, message} body into an ApiError with the code', () => {
    const e = toApiError(403, { code: 'FORBIDDEN', message: 'MEMBER may not run a forecast' })
    expect(e).toBeInstanceOf(ApiError)
    expect(e.status).toBe(403)
    expect(e.code).toBe('FORBIDDEN')
    expect(e.message).toBe('MEMBER may not run a forecast')
  })
  it('falls back to the status for a body that is not ours', () => {
    expect(toApiError(502, '<html>bad gateway</html>').code).toBe('HTTP_502')
    expect(toApiError(500, null).message).toBe('HTTP 500')
    expect(toApiError(404, { error: 'Not Found' }).code).toBe('HTTP_404')
  })
})
