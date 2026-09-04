import type { Settings } from '../shared/ipc'

/**
 * Whether the app should quit when the last window closes, instead of keeping
 * running in the tray. Pure so it can be unit-tested without Electron.
 */
export function shouldQuitOnLastWindowClosed(settings: Settings, _platform: NodeJS.Platform): boolean {
  return !settings.closeToTray
}

/**
 * What to do when the last window closes. Only flag the quit and ask Electron to quit: `quit()`
 * fires 'before-quit', whose handler shuts the service down — doing it here too would shut down
 * twice. Kept out of index.ts, and given its collaborators, so both branches can be tested.
 */
export function onWindowAllClosed(deps: {
  settings: Settings
  platform: NodeJS.Platform
  markQuitting: () => void
  quit: () => void
}): void {
  if (!shouldQuitOnLastWindowClosed(deps.settings, deps.platform)) return
  deps.markQuitting()
  deps.quit()
}
