import { spawn } from 'node:child_process'
import { join } from 'node:path'
import { app, BrowserWindow, ipcMain, shell, type Tray } from 'electron'
import { IPC, type AppState } from '../shared/ipc'
import { ApiClient } from './api-client'
import { startCopilotLogin } from './copilot-login'
import { DueChecker, overloadedMembers } from './due-check'
import { electronNotify } from './electron-notify'
import { registerIpc } from './ipc'
import { notifyOverload } from './notifications'
import { ServiceProcess, serviceCommand } from './service-launcher'
import { SettingsStore } from './settings-store'
import { createTray } from './tray'
import { shouldQuitOnLastWindowClosed } from './window-policy'
import type { CopilotStatus, Meta, RunCreated, Team } from '../shared/types'

export class AppController {
  private client: ApiClient | null = null
  private service: ServiceProcess | null = null
  private window: BrowserWindow | null = null
  private state: AppState = { service: 'starting', serviceMessage: 'Starting the forecast service…', version: app.getVersion(), platform: process.platform }
  readonly settings = new SettingsStore(join(app.getPath('userData'), 'settings.json'))
  quitting = false
  private tray: Tray | null = null
  readonly dueChecker = new DueChecker({ request: (req) => this.client ? this.client.request(req) : Promise.resolve({ ok: false, status: 0, error: 'service not ready' }), notify: electronNotify })

  getClient(): ApiClient | null { return this.client }
  getState(): AppState { return this.state }

  setState(patch: Partial<AppState>): void {
    this.state = { ...this.state, ...patch }
    for (const win of BrowserWindow.getAllWindows()) win.webContents.send(IPC.appStateChanged, this.state)
  }

  async startService(): Promise<void> {
    try {
      const cmd = serviceCommand({ isPackaged: app.isPackaged, resourcesPath: process.resourcesPath, appPath: app.getAppPath(), env: process.env, platform: process.platform })
      this.service = new ServiceProcess({ spawnFn: spawn, fetchFn: fetch, ...cmd, env: {}, log: (l) => console.log(l) })
      this.service.onExit((code) => {
        this.client = null
        if (!this.quitting) this.setState({ service: 'failed', serviceMessage: `The forecast service stopped (exit code ${code}). Restart the application.` })
      })
      const { port, token } = await this.service.start()
      this.client = new ApiClient(`http://127.0.0.1:${port}`, token)
      this.setState({ service: 'ready', serviceMessage: '' })
      void this.dueChecker.checkNow()
      this.dueChecker.start()
    } catch (err) {
      this.setState({ service: 'failed', serviceMessage: err instanceof Error ? err.message : String(err) })
      if (!this.window) this.showWindow()
    }
  }

  createTray(): void {
    if (this.tray) return
    try {
      this.tray = createTray({ showWindow: () => this.showWindow(), checkNow: () => this.dueChecker.checkNow(), quit: () => { this.quitting = true; app.quit() }, iconPath: join(__dirname, '../../resources/icon.png') })
    } catch (err) {
      console.error('failed to create tray', err)
    }
  }

  async afterRun(run: RunCreated): Promise<void> {
    try {
      if (!Array.isArray(run.forecasts)) return
      const meta = await this.client?.request({ method: 'GET', path: '/meta' })
      if (!meta || !meta.ok) return
      const m = meta.data as Meta
      if (!Array.isArray(m.teams) || !Array.isArray(m.members)) return
      const team = m.teams.find((t: Team) => t.id === run.team_id)
      notifyOverload(electronNotify, team?.name ?? `team ${run.team_id}`, overloadedMembers(run.forecasts, m.members))
    } catch (err) {
      console.error(err)
    }
  }

  async copilotStatus(): Promise<{ cli_path: string | null; message: string }> {
    const res = await this.client?.request({ method: 'GET', path: '/copilot/status' })
    if (!res || !res.ok) return { cli_path: null, message: res && !res.ok ? res.error : 'service not ready' }
    const status = res.data as CopilotStatus
    return { cli_path: status.cli_path, message: status.message }
  }

  createWindow(): BrowserWindow {
    const win = new BrowserWindow({
      width: 1280, height: 820, minWidth: 960, minHeight: 600, show: false, icon: join(__dirname, '../../resources/icon.png'),
      webPreferences: { preload: join(__dirname, '../preload/index.cjs'), contextIsolation: true, nodeIntegration: false, sandbox: true },
    })
    win.webContents.on('preload-error', (_e, path, err) => console.error('preload failed', path, err))
    win.once('ready-to-show', () => win.show())
    win.on('close', (e) => {
      if (!this.quitting && this.settings.get().closeToTray) { e.preventDefault(); win.hide() }
    })
    win.on('closed', () => { this.window = null })
    win.webContents.setWindowOpenHandler(({ url }) => { if (/^https?:\/\//.test(url)) void shell.openExternal(url); return { action: 'deny' } })
    if (process.env['ELECTRON_RENDERER_URL']) void win.loadURL(process.env['ELECTRON_RENDERER_URL'])
    else void win.loadFile(join(__dirname, '../renderer/index.html'))
    this.window = win
    return win
  }

  showWindow(): void {
    if (this.window) { this.window.show(); this.window.focus() } else this.createWindow()
  }

  applyLaunchAtLogin(on: boolean): void {
    if (process.platform === 'win32') app.setLoginItemSettings({ openAtLogin: on, args: ['--hidden'] })
  }

  shutdown(): void {
    this.quitting = true
    this.dueChecker.stop()
    this.tray?.destroy()
    this.service?.stop()
  }
}

export const controller = new AppController()

/**
 * WHF_SMOKE=1 support: once the window has finished loading, check whether the
 * sandboxed preload actually exposed `window.whf` (the C1 regression) and print
 * a machine-readable result once the service reaches a final state, or after a
 * 30 s cap, then exit(0) if the bridge is present and exit(1) otherwise.
 */
function armSmoke(win: BrowserWindow): void {
  win.webContents.once('did-finish-load', () => {
    void win.webContents.executeJavaScript('typeof window.whf').then((whf: unknown) => {
      console.log(`SMOKE window.whf=${String(whf)}`)
      const deadline = Date.now() + 30_000
      const check = (): void => {
        const phase = controller.getState().service
        if (phase === 'ready' || phase === 'failed' || Date.now() >= deadline) {
          console.log(`SMOKE service=${phase}`)
          controller.shutdown()
          app.exit(whf === 'object' ? 0 : 1)
        } else {
          setTimeout(check, 250)
        }
      }
      check()
    })
  })
}

if (!app.requestSingleInstanceLock()) {
  app.quit()
} else {
  app.setAppUserModelId('com.workloadhub.forecast')
  app.on('second-instance', () => controller.showWindow())
  registerIpc({
    ipcMain, getClient: () => controller.getClient(), settings: controller.settings, getState: () => controller.getState(),
    login: () => startCopilotLogin({ status: () => controller.copilotStatus(), spawnFn: spawn, platform: process.platform }),
    openExternal: (url) => shell.openExternal(url), applyLaunchAtLogin: (on) => controller.applyLaunchAtLogin(on),
    onRunCreated: (run) => { controller.afterRun(run).catch((err: unknown) => console.error(err)) },
  })
  void app.whenReady().then(async () => {
    const win = process.argv.includes('--hidden') ? null : controller.createWindow()
    if (process.env['WHF_SMOKE'] === '1' && win) armSmoke(win)
    controller.createTray()
    await controller.startService()
  }).catch((err: unknown) => {
    controller.setState({ service: 'failed', serviceMessage: err instanceof Error ? err.message : String(err) })
  })
  app.on('activate', () => controller.showWindow())
  app.on('before-quit', () => controller.shutdown())
  app.on('window-all-closed', () => {
    if (shouldQuitOnLastWindowClosed(controller.settings.get(), process.platform)) {
      controller.shutdown()
      app.quit()
    }
  })
}
