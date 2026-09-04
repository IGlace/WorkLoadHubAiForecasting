import type { ChildProcess, SpawnOptions } from 'node:child_process'
import type { LoginResult } from '../shared/ipc'

export function copilotLoginCommand(cliPath: string, platform: NodeJS.Platform): { command: string; args: string[] } {
  if (platform === 'win32') {
    const quoted = cliPath.replace(/'/g, "''")
    return { command: 'powershell.exe', args: ['-NoExit', '-NoProfile', '-Command', `& '${quoted}' login`] }
  }
  return { command: cliPath, args: ['login'] }
}

export async function startCopilotLogin(deps: {
  status: () => Promise<{ cli_path: string | null; message: string }>
  spawnFn: (command: string, args: string[], options: SpawnOptions) => ChildProcess
  platform: NodeJS.Platform
}): Promise<LoginResult> {
  const status = await deps.status()
  if (!status.cli_path) {
    return status.message
      ? { started: false, code: 'copilot.login.noCli', detail: status.message }
      : { started: false, code: 'copilot.login.noCli' }
  }
  const { command, args } = copilotLoginCommand(status.cli_path, deps.platform)
  const child = deps.spawnFn(command, args, { detached: true, stdio: 'ignore', windowsHide: false })
  child.on('error', () => {})
  child.unref()
  return { started: true, code: 'copilot.login.started' }
}
