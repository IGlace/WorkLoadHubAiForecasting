import { join } from 'node:path'

export const DATA_DIR_NAME = 'WorkloadHubForecast'

export function dataRoot(opts: { platform: NodeJS.Platform; env: NodeJS.ProcessEnv; fallback: string }): string {
  const local = opts.env['LOCALAPPDATA']
  if (opts.platform === 'win32' && local) return join(local, DATA_DIR_NAME)
  return opts.fallback
}

export function iconPath(opts: { isPackaged: boolean; resourcesPath: string; appPath: string; platform: NodeJS.Platform }): string {
  if (opts.isPackaged) return join(opts.resourcesPath, 'app.asar', 'resources', opts.platform === 'win32' ? 'icon.ico' : 'icon.png')
  return join(opts.appPath, 'resources', 'icon.png')
}

export function bundledCliPath(opts: { isPackaged: boolean; resourcesPath: string; platform: NodeJS.Platform; exists: (p: string) => boolean }): string | null {
  if (!opts.isPackaged) return null
  const candidate = join(opts.resourcesPath, 'service', 'whf', 'copilot-cli', opts.platform === 'win32' ? 'copilot.exe' : 'copilot')
  return opts.exists(candidate) ? candidate : null
}

export function serviceEnv(opts: { cliPath: string | null; env: NodeJS.ProcessEnv }): NodeJS.ProcessEnv {
  if (opts.cliPath && !opts.env['COPILOT_CLI_PATH']) return { COPILOT_CLI_PATH: opts.cliPath }
  return {}
}
