import type { IpcMain } from 'electron'
import { IPC, type ApiRequest, type ApiResponse, type AppState, type Settings } from '../shared/ipc'
import type { ApiClient } from './api-client'
import type { SettingsStore } from './settings-store'

export interface IpcDeps {
  ipcMain: Pick<IpcMain, 'handle'>
  getClient: () => ApiClient | null
  settings: SettingsStore
  getState: () => AppState
  login: () => Promise<{ started: boolean; message: string }>
  openExternal: (url: string) => Promise<void>
  applyLaunchAtLogin: (on: boolean) => void
}

const METHODS = new Set(['GET', 'POST', 'PUT', 'DELETE'])

function isApiRequest(value: unknown): value is ApiRequest {
  if (typeof value !== 'object' || value === null) return false
  const v = value as Record<string, unknown>
  return typeof v['method'] === 'string' && METHODS.has(v['method']) && typeof v['path'] === 'string' && v['path'].startsWith('/')
}

export function registerIpc(deps: IpcDeps): void {
  deps.ipcMain.handle(IPC.apiRequest, async (_e, raw: unknown): Promise<ApiResponse> => {
    if (!isApiRequest(raw)) return { ok: false, status: 0, error: 'invalid request' }
    const client = deps.getClient()
    if (!client) return { ok: false, status: 0, error: 'service not ready' }
    return client.request(raw)
  })
  deps.ipcMain.handle(IPC.settingsGet, () => deps.settings.get())
  deps.ipcMain.handle(IPC.settingsSet, (_e, patch: Partial<Settings>) => {
    const next = deps.settings.set(patch)
    if ('launchAtLogin' in patch) deps.applyLaunchAtLogin(next.launchAtLogin)
    return next
  })
  deps.ipcMain.handle(IPC.appState, () => deps.getState())
  deps.ipcMain.handle(IPC.copilotLogin, () => deps.login())
  deps.ipcMain.handle(IPC.openExternal, async (_e, url: unknown) => {
    if (typeof url !== 'string' || !/^https?:\/\//.test(url)) throw new Error('only http(s) urls can be opened')
    await deps.openExternal(url)
  })
}
