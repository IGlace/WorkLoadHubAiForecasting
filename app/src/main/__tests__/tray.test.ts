import { describe, expect, it, vi } from 'vitest'
import { trayMenuTemplate } from '../tray'

const handlers = { showWindow: vi.fn(), checkNow: vi.fn(async () => undefined), quit: vi.fn() }

describe('tray menu', () => {
  it('labels the items in English', () => {
    const labels = trayMenuTemplate('en', handlers).map((i) => i.label)
    expect(labels).toEqual(['Open', 'Check whether a forecast is due', undefined, 'Quit'])
  })
  it('labels the items in French', () => {
    const labels = trayMenuTemplate('fr', handlers).map((i) => i.label)
    expect(labels).toEqual(['Ouvrir', 'Vérifier si une prévision est à faire', undefined, 'Quitter'])
  })
  it('wires each item to its handler', () => {
    const items = trayMenuTemplate('fr', handlers)
    // Electron passes the menu item and window to `click`; none of these handlers looks at them.
    for (const item of items) (item.click as (() => void) | undefined)?.()
    expect(handlers.showWindow).toHaveBeenCalled()
    expect(handlers.checkNow).toHaveBeenCalled()
    expect(handlers.quit).toHaveBeenCalled()
  })
})
