import { Menu, Tray, nativeImage, type MenuItemConstructorOptions } from 'electron'
import type { Language } from '../shared/ipc'

export interface TrayHandlers {
  showWindow: () => void
  checkNow: () => Promise<unknown>
  quit: () => void
}

// The menu is built in the main process, which cannot reach the renderer's dictionary, so the
// wording lives here in the same shape notifications.ts uses.
const LABELS: Record<Language, { open: string; check: string; quit: string }> = {
  en: { open: 'Open', check: 'Check whether a forecast is due', quit: 'Quit' },
  fr: { open: 'Ouvrir', check: 'Vérifier si une prévision est à faire', quit: 'Quitter' },
}

export function trayMenuTemplate(lang: Language, deps: TrayHandlers): MenuItemConstructorOptions[] {
  const labels = LABELS[lang]
  return [
    { label: labels.open, click: () => deps.showWindow() },
    { label: labels.check, click: () => { void deps.checkNow() } },
    { type: 'separator' },
    { label: labels.quit, click: () => deps.quit() },
  ]
}

export function createTray(deps: TrayHandlers & { iconPath: string; lang: Language }): Tray {
  const image = nativeImage.createFromPath(deps.iconPath).resize({ width: 16, height: 16 })
  const tray = new Tray(image)
  tray.setToolTip('WorkloadHub Forecast')
  tray.setContextMenu(Menu.buildFromTemplate(trayMenuTemplate(deps.lang, deps)))
  tray.on('click', () => deps.showWindow())
  tray.on('double-click', () => deps.showWindow())
  return tray
}

/** Rebuild the menu after the user changes language; Electron has no way to relabel it in place. */
export function retranslateTray(tray: Tray, lang: Language, deps: TrayHandlers): void {
  tray.setContextMenu(Menu.buildFromTemplate(trayMenuTemplate(lang, deps)))
}
