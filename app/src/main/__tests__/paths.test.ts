import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { bundledCliPath, dataRoot, iconPath, serviceEnv } from '../paths'

describe('dataRoot', () => {
  it('uses LOCALAPPDATA\\WorkloadHubForecast on Windows', () => {
    expect(dataRoot({ platform: 'win32', env: { LOCALAPPDATA: 'C:\\Users\\a\\AppData\\Local' }, fallback: '/x' })).toBe(join('C:\\Users\\a\\AppData\\Local', 'WorkloadHubForecast'))
  })
  it('falls back elsewhere or when LOCALAPPDATA is missing', () => {
    expect(dataRoot({ platform: 'linux', env: {}, fallback: '/home/a/.config/app' })).toBe('/home/a/.config/app')
    expect(dataRoot({ platform: 'win32', env: {}, fallback: 'C:\\fallback' })).toBe('C:\\fallback')
  })
})

describe('iconPath', () => {
  it('reads from the asar when packaged and from the app dir in development', () => {
    expect(iconPath({ isPackaged: true, resourcesPath: '/r', appPath: '/a', platform: 'win32' })).toBe(join('/r', 'app.asar', 'resources', 'icon.ico'))
    expect(iconPath({ isPackaged: true, resourcesPath: '/r', appPath: '/a', platform: 'linux' })).toBe(join('/r', 'app.asar', 'resources', 'icon.png'))
    expect(iconPath({ isPackaged: false, resourcesPath: '/r', appPath: '/a', platform: 'win32' })).toBe(join('/a', 'resources', 'icon.png'))
  })
})

describe('bundledCliPath and serviceEnv', () => {
  it('finds the bundled CLI only when packaged and present', () => {
    const win = join('/r', 'service', 'whf', 'copilot-cli', 'copilot.exe')
    expect(bundledCliPath({ isPackaged: true, resourcesPath: '/r', platform: 'win32', exists: (p) => p === win })).toBe(win)
    expect(bundledCliPath({ isPackaged: true, resourcesPath: '/r', platform: 'win32', exists: () => false })).toBeNull()
    expect(bundledCliPath({ isPackaged: false, resourcesPath: '/r', platform: 'win32', exists: () => true })).toBeNull()
  })
  it('injects COPILOT_CLI_PATH unless the user already set one', () => {
    expect(serviceEnv({ cliPath: 'C:\\x\\copilot.exe', env: {} })).toEqual({ COPILOT_CLI_PATH: 'C:\\x\\copilot.exe' })
    expect(serviceEnv({ cliPath: 'C:\\x\\copilot.exe', env: { COPILOT_CLI_PATH: 'D:\\mine.exe' } })).toEqual({})
    expect(serviceEnv({ cliPath: null, env: {} })).toEqual({})
  })
})
