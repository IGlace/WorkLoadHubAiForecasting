import { Menu, Tray, nativeImage } from 'electron'

export function createTray(deps: { showWindow: () => void; checkNow: () => Promise<unknown>; quit: () => void; iconPath: string }): Tray {
  const image = nativeImage.createFromPath(deps.iconPath).resize({ width: 16, height: 16 })
  const tray = new Tray(image)
  tray.setToolTip('WorkloadHub Forecast')
  tray.setContextMenu(Menu.buildFromTemplate([
    { label: 'Open', click: () => deps.showWindow() },
    { label: 'Check whether a forecast is due', click: () => { void deps.checkNow() } },
    { type: 'separator' },
    { label: 'Quit', click: () => deps.quit() },
  ]))
  tray.on('click', () => deps.showWindow())
  tray.on('double-click', () => deps.showWindow())
  return tray
}
