import { describe, expect, it } from 'vitest'
import { DEFAULT_SETTINGS } from '../../shared/ipc'
import { shouldQuitOnLastWindowClosed } from '../window-policy'

describe('shouldQuitOnLastWindowClosed', () => {
  it('quits when closeToTray is off', () => {
    expect(shouldQuitOnLastWindowClosed({ ...DEFAULT_SETTINGS, closeToTray: false }, 'win32')).toBe(true)
  })
  it('keeps running in the tray when closeToTray is on', () => {
    expect(shouldQuitOnLastWindowClosed({ ...DEFAULT_SETTINGS, closeToTray: true }, 'win32')).toBe(false)
  })
})
