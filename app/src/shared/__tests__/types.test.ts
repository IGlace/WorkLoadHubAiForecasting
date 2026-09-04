import { describe, expect, it } from 'vitest'
import { DEFAULT_SETTINGS, IPC } from '../ipc'

describe('shared contracts', () => {
  it('has unique channel names', () => {
    const names = Object.values(IPC)
    expect(new Set(names).size).toBe(names.length)
  })
  it('defaults to English, no model, no auto-start, close to tray', () => {
    expect(DEFAULT_SETTINGS).toEqual({ language: 'en', model: null, launchAtLogin: false, closeToTray: true })
  })
})
