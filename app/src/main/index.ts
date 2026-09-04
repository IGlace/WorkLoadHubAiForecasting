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
    const cmd = serviceCommand({ isPackaged: app.isPackaged, resourcesPath: process.resourcesPath, appPath: app.getAppPath(), env: process.env, platform: process.platform })
    this.service = new ServiceProcess({ spawnFn: spawn, fetchFn: fetch, ...cmd, env: {}, log: (l) => console.log(l) })
    this.service.onExit((code) => {
      this.client = null
      if (!this.quitting) this.setState({ service: 'failed', serviceMessage: `The forecast service stopped (exit code ${code}). Restart the application.` })
    })
    try {
      const { port, token } = await this.service.start()
      this.client = new ApiClient(`http://127.0.0.1:${port}`, token)
      this.setState({ service: 'ready', serviceMessage: '' })
      this.createTray()
      void this.dueChecker.checkNow()
      this.dueChecker.start()
    } catch (err) {
      this.setState({ service: 'failed', serviceMessage: err instanceof Error ? err.message : String(err) })
    }
  }

  createTray(): void {
    if (this.tray) return
    this.tray = createTray({ showWindow: () => this.showWindow(), checkNow: () => this.dueChecker.checkNow(), quit: () => { this.quitting = true; app.quit() }, iconPath: join(__dirname, '../../resources/icon.png') })
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
      webPreferences: { preload: join(__dirname, '../preload/index.mjs'), contextIsolation: true, nodeIntegration: false, sandbox: true },
    })
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
    if (!process.argv.includes('--hidden')) controller.createWindow()
    await controller.startService()
  })
  app.on('activate', () => controller.showWindow())
  app.on('before-quit', () => controller.shutdown())
  app.on('window-all-closed', () => { /* keep running in the tray */ })
}
