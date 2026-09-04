import { vi } from 'vitest'
import { DEFAULT_SETTINGS, type ApiRequest, type ApiResponse, type AppState, type Settings, type WhfBridge } from '../../../shared/ipc'

type Route = unknown | ((body: unknown, req: ApiRequest) => unknown)

export function installFakeWhf(routes: Record<string, Route>, options: { state?: Partial<AppState>; settings?: Partial<Settings> } = {}) {
  const calls: ApiRequest[] = []
  let settings: Settings = { ...DEFAULT_SETTINGS, ...options.settings }
  const state: AppState = { service: 'ready', serviceMessage: '', version: '0.1.0', platform: 'win32', ...options.state }
  const bridge: WhfBridge = {
    request: vi.fn(async (req: ApiRequest): Promise<ApiResponse> => {
      calls.push(req)
      const key = `${req.method} ${req.path}`
      const exact = routes[key]
      const pattern = Object.keys(routes).find((k) => k.includes('*') && new RegExp('^' + k.replace(/[.+?^${}()|[\]\\]/g, '\\$&').replace(/\*/g, '[^/]+') + '$').test(key))
      const route = exact !== undefined ? exact : pattern ? routes[pattern] : undefined
      if (route === undefined) return { ok: false, status: 404, error: `no fake route for ${key}` }
      const data = typeof route === 'function' ? (route as (b: unknown, r: ApiRequest) => unknown)(req.body, req) : route
      if (data instanceof Error) return { ok: false, status: 400, error: data.message }
      return { ok: true, status: 200, data }
    }),
    getSettings: async () => settings,
    setSettings: async (patch) => { settings = { ...settings, ...patch }; return settings },
    copilotLogin: async () => ({ started: true, message: 'opened' }),
    getState: async () => state,
    onStateChanged: () => () => {},
    openExternal: async () => {},
  }
  Object.defineProperty(window, 'whf', { value: bridge, configurable: true })
  return { calls, get settings() { return settings } }
}

export const META = {
  departments: [{ id: 1, name: 'Platform', skill_team_leader_id: 10 }],
  teams: [{ id: 1, department_id: 1, name: 'Core', team_leader_id: 11 }, { id: 2, department_id: 1, name: 'Data', team_leader_id: 12 }],
  members: [
    { id: 10, name: 'Sara Idrissi', team_id: null, department_id: 1, role: 'skill_team_leader', counted_in_workload: 0 },
    { id: 11, name: 'Ali Benjelloun', team_id: 1, department_id: 1, role: 'team_leader', counted_in_workload: 1 },
    { id: 12, name: 'Nour Alami', team_id: 2, department_id: 1, role: 'team_leader', counted_in_workload: 1 },
    { id: 13, name: 'Yara Tazi', team_id: 1, department_id: 1, role: 'member', counted_in_workload: 1 },
  ],
  capacity_default: 40,
}
