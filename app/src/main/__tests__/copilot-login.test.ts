import { describe, expect, it, vi } from 'vitest'
import { copilotLoginCommand, startCopilotLogin } from '../copilot-login'

describe('copilot login', () => {
  it('opens a PowerShell window that stays open on Windows', () => {
    expect(copilotLoginCommand('C:\\cli\\copilot.exe', 'win32')).toEqual({
      command: 'powershell.exe', args: ['-NoExit', '-NoProfile', '-Command', "& 'C:\\cli\\copilot.exe' login"],
    })
  })
  it('runs the CLI directly elsewhere', () => {
    expect(copilotLoginCommand('/usr/bin/copilot', 'linux')).toEqual({ command: '/usr/bin/copilot', args: ['login'] })
  })
  it('asks the service where the CLI is and reports when it is missing', async () => {
    const spawnFn = vi.fn(() => ({ unref: vi.fn(), on: vi.fn() }))
    const missing = await startCopilotLogin({ status: async () => ({ cli_path: null, message: 'no cli' }), spawnFn: spawnFn as never, platform: 'win32' })
    expect(missing).toEqual({ started: false, message: 'no cli' })
    const started = await startCopilotLogin({ status: async () => ({ cli_path: 'C:\\c.exe', message: '' }), spawnFn: spawnFn as never, platform: 'win32' })
    expect(started.started).toBe(true)
    expect(spawnFn).toHaveBeenCalledWith('powershell.exe', expect.arrayContaining(['-NoExit']), expect.objectContaining({ detached: true }))
  })
})
