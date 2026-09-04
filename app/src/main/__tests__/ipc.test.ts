import { describe, expect, it, vi } from 'vitest'
import { DEFAULT_SETTINGS, IPC } from '../../shared/ipc'
import { registerIpc } from '../ipc'

function harness(clientPresent = true) {
  const handlers = new Map<string, (event: unknown, ...args: unknown[]) => unknown>()
  const ipcMain = { handle: vi.fn((channel: string, fn: (event: unknown, ...args: unknown[]) => unknown) => handlers.set(channel, fn)) }
  const request = vi.fn(async () => ({ ok: true, status: 200, data: { x: 1 } }))
  const settings = { get: vi.fn(() => ({ ...DEFAULT_SETTINGS })), set: vi.fn((p: object) => ({ ...DEFAULT_SETTINGS, ...p })) }
  const applyLaunchAtLogin = vi.fn()
  const onLanguageChanged = vi.fn()
  registerIpc({
    ipcMain, getClient: () => (clientPresent ? ({ request } as never) : null), settings: settings as never,
    getState: () => ({ service: 'ready', serviceMessage: '', version: '0.1.0', platform: 'win32' }),
    login: async () => ({ started: true, code: 'copilot.login.started' as const }), applyLaunchAtLogin, onLanguageChanged,
  })
  return { handlers, request, settings, applyLaunchAtLogin, onLanguageChanged }
}

describe('registerIpc', () => {
  it('forwards api:request to the client', async () => {
    const { handlers, request } = harness()
    const res = await handlers.get(IPC.apiRequest)!({}, { method: 'GET', path: '/meta' })
    expect(res).toEqual({ ok: true, status: 200, data: { x: 1 } })
    expect(request).toHaveBeenCalledWith({ method: 'GET', path: '/meta' })
  })
  it('answers with status 0 while the service is not ready', async () => {
    const { handlers } = harness(false)
    expect(await handlers.get(IPC.apiRequest)!({}, { method: 'GET', path: '/meta' })).toEqual({ ok: false, status: 0, error: 'service not ready' })
  })
  it('rejects malformed requests without calling the client', async () => {
    const { handlers, request } = harness()
    expect(await handlers.get(IPC.apiRequest)!({}, { method: 'TRACE', path: 'meta' })).toEqual({ ok: false, status: 0, error: 'invalid request' })
    expect(request).not.toHaveBeenCalled()
  })
  it('reads and patches settings, applying launch-at-login', async () => {
    const { handlers, settings, applyLaunchAtLogin } = harness()
    expect(await handlers.get(IPC.settingsGet)!({})).toEqual(DEFAULT_SETTINGS)
    await handlers.get(IPC.settingsSet)!({}, { launchAtLogin: true })
    expect(settings.set).toHaveBeenCalledWith({ launchAtLogin: true })
    expect(applyLaunchAtLogin).toHaveBeenCalledWith(true)
  })
  it('tells the main process to relabel the tray when the language changes', async () => {
    // The tray menu is built once, in the main process, so it cannot re-read the dictionary itself.
    const { handlers, onLanguageChanged } = harness()
    await handlers.get(IPC.settingsSet)!({}, { model: 'gpt-5' })
    expect(onLanguageChanged).not.toHaveBeenCalled()
    await handlers.get(IPC.settingsSet)!({}, { language: 'fr' })
    expect(onLanguageChanged).toHaveBeenCalledWith('fr')
  })
  it('exposes state and login', async () => {
    const { handlers } = harness()
    expect(await handlers.get(IPC.appState)!({})).toMatchObject({ service: 'ready' })
    expect(await handlers.get(IPC.copilotLogin)!({})).toEqual({ started: true, code: 'copilot.login.started' })
  })
  it('rejects an invalid settings patch and answers the current settings unchanged', async () => {
    const { handlers, settings } = harness()
    expect(await handlers.get(IPC.settingsSet)!({}, 'nope')).toEqual(DEFAULT_SETTINGS)
    expect(await handlers.get(IPC.settingsSet)!({}, { language: 'de' })).toEqual(DEFAULT_SETTINGS)
    expect(await handlers.get(IPC.settingsSet)!({}, { launchAtLogin: 'yes' })).toEqual(DEFAULT_SETTINGS)
    expect(await handlers.get(IPC.settingsSet)!({}, { unknownKey: 1 })).toEqual(DEFAULT_SETTINGS)
    expect(settings.set).not.toHaveBeenCalled()
  })
  it('reports created runs to the hook', async () => {
    const handlers = new Map<string, (event: unknown, ...args: unknown[]) => unknown>()
    const onRunCreated = vi.fn()
    registerIpc({
      ipcMain: { handle: vi.fn((c: string, fn: (event: unknown, ...args: unknown[]) => unknown) => handlers.set(c, fn)) },
      getClient: () => ({ request: async () => ({ ok: true, status: 200, data: { run_id: 7, team_id: 1, forecasts: [] } }) } as never),
      settings: { get: () => DEFAULT_SETTINGS, set: () => DEFAULT_SETTINGS } as never,
      getState: () => ({ service: 'ready', serviceMessage: '', version: '0', platform: 'win32' }),
      login: async () => ({ started: false, code: 'copilot.login.noCli' as const }), applyLaunchAtLogin: () => {}, onRunCreated,
    })
    await handlers.get(IPC.apiRequest)!({}, { method: 'POST', path: '/runs', body: { team_id: 1 } })
    await handlers.get(IPC.apiRequest)!({}, { method: 'GET', path: '/runs' })
    expect(onRunCreated).toHaveBeenCalledTimes(1)
    expect(onRunCreated).toHaveBeenCalledWith(expect.objectContaining({ run_id: 7 }))
  })
})
