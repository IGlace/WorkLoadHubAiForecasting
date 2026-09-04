import { EventEmitter } from 'node:events'
import { join } from 'node:path'
import { PassThrough } from 'node:stream'
import { describe, expect, it, vi } from 'vitest'
import { ServiceProcess, parseHandshake, serviceCommand, waitForHealth } from '../service-launcher'

describe('serviceCommand', () => {
  const base = { resourcesPath: '/res', appPath: '/app', platform: 'win32' as const }
  it('uses uv from the sibling service directory in development', () => {
    const cmd = serviceCommand({ ...base, isPackaged: false, env: {} })
    expect(cmd).toEqual({ command: 'uv', args: ['run', 'whf', 'serve'], cwd: join('/app', '..', 'service') })
  })
  it('uses the frozen executable when packaged', () => {
    const cmd = serviceCommand({ ...base, isPackaged: true, env: {} })
    expect(cmd.command.replace(/\\/g, '/')).toBe('/res/service/whf/whf.exe')
    expect(cmd.args).toEqual(['serve'])
  })
  it('uses whf without .exe on other platforms when packaged', () => {
    const cmd = serviceCommand({ ...base, platform: 'linux', isPackaged: true, env: {} })
    expect(cmd.command.replace(/\\/g, '/')).toBe('/res/service/whf/whf')
  })
  it('honours WHF_SERVICE_COMMAND as a JSON array', () => {
    const cmd = serviceCommand({ ...base, isPackaged: true, env: { WHF_SERVICE_COMMAND: '["C:\\\\x\\\\whf.exe","serve","--port","0"]' } })
    expect(cmd).toEqual({ command: 'C:\\x\\whf.exe', args: ['serve', '--port', '0'], cwd: '/app' })
  })
})

describe('parseHandshake', () => {
  it('reads the port and token from the first JSON line', () => {
    expect(parseHandshake('{"port": 51234, "token": "abc"}')).toEqual({ port: 51234, token: 'abc' })
  })
  it('ignores other lines', () => {
    expect(parseHandshake('INFO: started')).toBeNull()
    expect(parseHandshake('{"port": "x"}')).toBeNull()
  })
})

describe('waitForHealth', () => {
  it('resolves once /health answers ok', async () => {
    const answers = [Promise.reject(new Error('refused')), Promise.resolve(new Response('{"status":"ok"}', { status: 200 }))]
    const fetchFn = vi.fn(() => answers.shift()!) as unknown as typeof fetch
    await waitForHealth(5000, { fetchFn, timeoutMs: 1000, intervalMs: 1, sleep: async () => {} })
    expect(fetchFn).toHaveBeenCalledTimes(2)
    expect(fetchFn).toHaveBeenCalledWith('http://127.0.0.1:5000/health')
  })
  it('rejects after the timeout', async () => {
    const fetchFn = vi.fn(() => Promise.reject(new Error('refused'))) as unknown as typeof fetch
    let now = 0
    await expect(
      waitForHealth(5000, { fetchFn, timeoutMs: 10, intervalMs: 5, sleep: async (ms) => { now += ms }, now: () => now }),
    ).rejects.toThrow('did not become healthy')
  })
})

function fakeChild(): { child: EventEmitter & { stdout: PassThrough; stderr: PassThrough; kill: ReturnType<typeof vi.fn>; pid: number }; } {
  const child = Object.assign(new EventEmitter(), { stdout: new PassThrough(), stderr: new PassThrough(), kill: vi.fn(), pid: 42 })
  return { child }
}

describe('ServiceProcess', () => {
  it('spawns, reads the handshake, waits for health and reports exit', async () => {
    const { child } = fakeChild()
    const spawnFn = vi.fn(() => child)
    const fetchFn = vi.fn(() => Promise.resolve(new Response('{"status":"ok"}', { status: 200 }))) as unknown as typeof fetch
    const proc = new ServiceProcess({ spawnFn: spawnFn as never, fetchFn, command: 'whf', args: ['serve'], cwd: '/x', env: {}, log: () => {}, sleep: async () => {} })
    const exit = vi.fn()
    proc.onExit(exit)
    const started = proc.start()
    child.stdout.write('{"port": 6001, "token": "tok"}\n')
    await expect(started).resolves.toEqual({ port: 6001, token: 'tok' })
    expect(spawnFn).toHaveBeenCalledWith('whf', ['serve'], expect.objectContaining({ cwd: '/x', windowsHide: true }))
    child.emit('exit', 0)
    expect(exit).toHaveBeenCalledWith(0)
    proc.stop()
    expect(child.kill).toHaveBeenCalled()
  })
  it('rejects when the process exits before the handshake', async () => {
    const { child } = fakeChild()
    const proc = new ServiceProcess({ spawnFn: (() => child) as never, fetchFn: fetch, command: 'whf', args: [], cwd: '/x', env: {}, log: () => {}, sleep: async () => {} })
    const started = proc.start()
    child.stderr.write('Traceback: boom\n')
    child.emit('exit', 1)
    await expect(started).rejects.toThrow(/exited with code 1.*boom/s)
  })
  it('rejects when the process exits while still waiting for health', async () => {
    const { child } = fakeChild()
    // Never settles: represents a health check that is still in flight, without
    // spinning the microtask queue (an always-rejecting fetchFn combined with a
    // no-op sleep never yields to real timers, so it starves vi.waitFor below).
    const fetchFn = vi.fn(() => new Promise<Response>(() => {})) as unknown as typeof fetch
    const proc = new ServiceProcess({
      spawnFn: (() => child) as never,
      fetchFn,
      command: 'whf',
      args: ['serve'],
      cwd: '/x',
      env: {},
      log: () => {},
      sleep: async () => {},
      healthTimeoutMs: 1_000_000,
    })
    const started = proc.start()
    child.stdout.write('{"port": 6001, "token": "tok"}\n')
    await vi.waitFor(() => expect(fetchFn).toHaveBeenCalled())
    child.emit('exit', 1)
    await expect(started).rejects.toThrow(/exited with code 1/)
  })
})
