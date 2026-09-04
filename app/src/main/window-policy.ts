import type { Settings } from '../shared/ipc'

/**
 * Whether the app should quit when the last window closes, instead of keeping
 * running in the tray. Pure so it can be unit-tested without Electron.
 */
export function shouldQuitOnLastWindowClosed(settings: Settings, _platform: NodeJS.Platform): boolean {
  return !settings.closeToTray
}
