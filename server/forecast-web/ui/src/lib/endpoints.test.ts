import { describe, expect, it } from 'vitest'
import { ENDPOINTS } from './endpoints'

/** The route list of design 2026-09-18, section 3.3: the reference table must name each once and nothing else. */
const ROUTES = [
  'GET /api/system', 'POST /api/system/clock', 'GET /api/directory/users', 'GET /api/directory/teams', 'GET /api/directory/teams/{id}',
  'GET /api/me', 'GET /api/permissions', 'POST /api/teams/{teamId}/forecast-runs', 'GET /api/teams/{teamId}/forecast-runs',
  'GET /api/teams/{teamId}/forecast', 'GET /api/teams/{teamId}/accuracy', 'GET /api/forecast-runs/{id}', 'GET /api/forecast-runs/{id}/progress',
  'POST /api/forecast-runs/{id}/narratives', 'GET /api/forecast-runs/{id}/narratives/{lang}', 'GET /api/me/copilot', 'GET /api/me/github-token',
  'PUT /api/me/github-token', 'DELETE /api/me/github-token',
]

describe('the endpoint table', () => {
  it('names every route of the design exactly once', () => {
    const listed = ENDPOINTS.map((e) => e.method + ' ' + e.path).sort()
    expect(listed).toEqual([...ROUTES].sort())
  })
  it('says who may call each route and what comes back', () => {
    for (const e of ENDPOINTS) {
      expect(e.who.length, e.path).toBeGreaterThan(0)
      expect(e.response.length, e.path).toBeGreaterThan(0)
      expect(e.purpose.length, e.path).toBeGreaterThan(20)
    }
  })
  it('marks the demo-only routes', () => {
    expect(ENDPOINTS.filter((e) => !e.production).map((e) => e.path)).toEqual(['/api/system', '/api/system/clock', '/api/permissions'])
  })
})
