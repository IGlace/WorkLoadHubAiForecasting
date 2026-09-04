import type { ChildProcess, SpawnOptions } from 'node:child_process'
import { join } from 'node:path'
import { createInterface } from 'node:readline'

export interface ServiceCommand { command: string; args: string[]; cwd: string }

export function serviceCommand(opts: {
  isPackaged: boolean; resourcesPath: string; appPath: string; env: NodeJS.ProcessEnv; platform: NodeJS.Platform
}): ServiceCommand {
  const override = opts.env['WHF_SERVICE_COMMAND']
  if (override) {
    const parsed: unknown = JSON.parse(override)
    if (!Array.isArray(parsed) || parsed.length === 0 || !parsed.every((p) => typeof p === 'string')) {
      throw new Error('WHF_SERVICE_COMMAND must be a JSON array of strings')
    }
    const [command, ...args] = parsed as string[]
    return { command: command!, args, cwd: opts.appPath }
  }
  if (opts.isPackaged) {
    const exe = opts.platform === 'win32' ? 'whf.exe' : 'whf'
    return { command: join(opts.resourcesPath, 'service', 'whf', exe), args: ['serve'], cwd: opts.resourcesPath }
  }
  return { command: 'uv', args: ['run', 'whf', 'serve'], cwd: `${opts.appPath}/../service` }
}

export function parseHandshake(line: string): { port: number; token: string } | null {
  const trimmed = line.trim()
  if (!trimmed.startsWith('{')) return null
  try {
    const obj: unknown = JSON.parse(trimmed)
    if (typeof obj === 'object' && obj !== null && 'port' in obj && 'token' in obj) {
      const { port, token } = obj as { port: unknown; token: unknown }
      if (typeof port === 'number' && Number.isInteger(port) && typeof token === 'string' && token) return { port, token }
    }
  } catch { /* not JSON */ }
  return null
}

const defaultSleep = (ms: number): Promise<void> => new Promise((r) => setTimeout(r, ms))

export async function waitForHealth(
  port: number,
  opts: { fetchFn: typeof fetch; timeoutMs: number; intervalMs: number; sleep?: (ms: number) => Promise<void>; now?: () => number },
): Promise<void> {
  const sleep = opts.sleep ?? defaultSleep
  const now = opts.now ?? Date.now
  const start = now()
  for (;;) {
    try {
      const res = await opts.fetchFn(`http://127.0.0.1:${port}/health`)
      if (res.ok) {
        const body = (await res.json()) as { status?: string }
        if (body.status === 'ok') return
      }
    } catch { /* not up yet */ }
    if (now() - start >= opts.timeoutMs) throw new Error(`service did not become healthy within ${opts.timeoutMs} ms`)
    await sleep(opts.intervalMs)
  }
}

type SpawnFn = (command: string, args: string[], options: SpawnOptions) => ChildProcess

export class ServiceProcess {
  private child: ChildProcess | null = null
  private exitListeners: ((code: number | null) => void)[] = []
  private stderrTail: string[] = []

  constructor(
    private readonly opts: {
      spawnFn: SpawnFn; fetchFn: typeof fetch; command: string; args: string[]; cwd: string
      env: NodeJS.ProcessEnv; log: (line: string) => void; sleep?: (ms: number) => Promise<void>; healthTimeoutMs?: number
    },
  ) {}

  onExit(listener: (code: number | null) => void): void { this.exitListeners.push(listener) }

  start(): Promise<{ port: number; token: string }> {
    const child = this.opts.spawnFn(this.opts.command, this.opts.args, {
      cwd: this.opts.cwd, env: { ...process.env, ...this.opts.env }, stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true,
    })
    this.child = child
    return new Promise((resolve, reject) => {
      let settled = false
      const stderr = createInterface({ input: child.stderr! })
      stderr.on('line', (line) => { this.opts.log(`[service] ${line}`); this.stderrTail = [...this.stderrTail.slice(-19), line] })
      const stdout = createInterface({ input: child.stdout! })
      stdout.on('line', (line) => {
        const hs = parseHandshake(line)
        if (hs && !settled) {
          settled = true
          waitForHealth(hs.port, { fetchFn: this.opts.fetchFn, timeoutMs: this.opts.healthTimeoutMs ?? 60_000, intervalMs: 250, sleep: this.opts.sleep })
            .then(() => resolve(hs), reject)
        } else this.opts.log(`[service] ${line}`)
      })
      child.on('error', (err) => { if (!settled) { settled = true; reject(err) } })
      child.on('exit', (code) => {
        if (!settled) { settled = true; reject(new Error(`service exited with code ${code} before it was ready\n${this.stderrTail.join('\n')}`)) }
        for (const l of this.exitListeners) l(code)
      })
    })
  }

  stop(): void {
    this.child?.kill()
    this.child = null
  }
}
