import { describe, expect, it, vi } from 'vitest'
import { DEFAULT_SETTINGS } from '../../shared/ipc'
import { onWindowAllClosed, shouldQuitOnLastWindowClosed } from '../window-policy'

describe('shouldQuitOnLastWindowClosed', () => {
  it('quits when closeToTray is off', () => {
    expect(shouldQuitOnLastWindowClosed({ ...DEFAULT_SETTINGS, closeToTray: false }, 'win32')).toBe(true)
  })
  it('keeps running in the tray when closeToTray is on', () => {
    expect(shouldQuitOnLastWindowClosed({ ...DEFAULT_SETTINGS, closeToTray: true }, 'win32')).toBe(false)
  })
})

describe('onWindowAllClosed', () => {
  it('flags the quit and asks Electron to quit when the tray is off', () => {
    const markQuitting = vi.fn()
    const quit = vi.fn()
    onWindowAllClosed({ settings: { ...DEFAULT_SETTINGS, closeToTray: false }, platform: 'win32', markQuitting, quit })
    // The flag has to be set before quit(): 'before-quit' reads it to tell a real quit from
    // a window close, and it is that handler — not this one — that shuts the service down.
    expect(markQuitting).toHaveBeenCalledBefore(quit)
    expect(quit).toHaveBeenCalledOnce()
  })
  it('does nothing when the app should keep running in the tray', () => {
    const markQuitting = vi.fn()
    const quit = vi.fn()
    onWindowAllClosed({ settings: { ...DEFAULT_SETTINGS, closeToTray: true }, platform: 'win32', markQuitting, quit })
    expect(markQuitting).not.toHaveBeenCalled()
    expect(quit).not.toHaveBeenCalled()
  })
})
