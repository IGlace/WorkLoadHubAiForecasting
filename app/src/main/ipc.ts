import type { IpcMain } from 'electron'
import { IPC, type ApiRequest, type ApiResponse, type AppState, type Settings } from '../shared/ipc'
import type { RunCreated } from '../shared/types'
import type { ApiClient } from './api-client'
import type { SettingsStore } from './settings-store'

export interface IpcDeps {
  ipcMain: Pick<IpcMain, 'handle'>
  getClient: () => ApiClient | null
  settings: SettingsStore
  getState: () => AppState
  login: () => Promise<{ started: boolean; message: string }>
  applyLaunchAtLogin: (on: boolean) => void
  onRunCreated?: (run: RunCreated) => void
}

const METHODS = new Set(['GET', 'POST', 'PUT', 'DELETE'])

function isApiRequest(value: unknown): value is ApiRequest {
  if (typeof value !== 'object' || value === null) return false
  const v = value as Record<string, unknown>
  return typeof v['method'] === 'string' && METHODS.has(v['method']) && typeof v['path'] === 'string' && v['path'].startsWith('/')
}

const SETTINGS_VALIDATORS: Record<keyof Settings, (v: unknown) => boolean> = {
  language: (v) => v === 'en' || v === 'fr',
  model: (v) => typeof v === 'string' || v === null,
  launchAtLogin: (v) => typeof v === 'boolean',
  closeToTray: (v) => typeof v === 'boolean',
}

function isValidSettingsPatch(value: unknown): value is Partial<Settings> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) return false
  return Object.entries(value).every(
    ([key, v]) => Object.prototype.hasOwnProperty.call(SETTINGS_VALIDATORS, key) && SETTINGS_VALIDATORS[key as keyof Settings](v),
  )
}

export function registerIpc(deps: IpcDeps): void {
  deps.ipcMain.handle(IPC.apiRequest, async (_e, raw: unknown): Promise<ApiResponse> => {
    if (!isApiRequest(raw)) return { ok: false, status: 0, error: 'invalid request' }
    const client = deps.getClient()
    if (!client) return { ok: false, status: 0, error: 'service not ready' }
    const res = await client.request(raw)
    if (res.ok && raw.method === 'POST' && raw.path === '/runs') deps.onRunCreated?.(res.data as RunCreated)
    return res
  })
  deps.ipcMain.handle(IPC.settingsGet, () => deps.settings.get())
  deps.ipcMain.handle(IPC.settingsSet, (_e, patch: unknown) => {
    if (!isValidSettingsPatch(patch)) return deps.settings.get()
    const next = deps.settings.set(patch)
    if ('launchAtLogin' in patch) deps.applyLaunchAtLogin(next.launchAtLogin)
    return next
  })
  deps.ipcMain.handle(IPC.appState, () => deps.getState())
  deps.ipcMain.handle(IPC.copilotLogin, () => deps.login())
}
