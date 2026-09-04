import nodeFs from 'node:fs'
import { join } from 'node:path'
import { format } from 'node:util'

export class RotatingLog {
  readonly path: string
  private readonly fs: typeof nodeFs
  private readonly maxBytes: number
  private readonly keep: number
  private failed = false

  constructor(opts: { dir: string; name?: string; maxBytes?: number; keep?: number; fs?: typeof nodeFs }) {
    this.fs = opts.fs ?? nodeFs
    this.maxBytes = opts.maxBytes ?? 1_000_000
    this.keep = opts.keep ?? 5
    this.path = join(opts.dir, `${opts.name ?? 'app'}.log`)
    try {
      this.fs.mkdirSync(opts.dir, { recursive: true })
    } catch (err) {
      // The wrapped console isn't installed yet at construction time, so
      // this can't recurse into RotatingLog.write.
      console.error('log directory unavailable:', err)
      this.failed = true
    }
  }

  write(line: string): void {
    if (this.failed) return
    try {
      this.rotateIfNeeded()
      this.fs.appendFileSync(this.path, `${new Date().toISOString()} ${line}\n`)
    } catch (err) {
      this.failed = true
      console.error('log file disabled:', err)
    }
  }

  private rotateIfNeeded(): void {
    let size: number
    try { size = this.fs.statSync(this.path).size } catch { return }
    if (size < this.maxBytes) return
    const base = this.path.slice(0, -'.log'.length)
    for (let i = this.keep - 1; i >= 1; i--) {
      const from = i === 1 ? this.path : `${base}.${i - 1}.log`
      const to = `${base}.${i}.log`
      if (this.fs.existsSync(from)) this.fs.renameSync(from, to)
    }
  }
}

export function installConsoleLogging(log: RotatingLog): void {
  const wrap = (level: string, original: (...args: unknown[]) => void) => (...args: unknown[]): void => {
    original(...args)
    log.write(`${level} ${format(...args)}`)
  }
  console.log = wrap('INFO', console.log.bind(console))
  console.warn = wrap('WARN', console.warn.bind(console))
  console.error = wrap('ERROR', console.error.bind(console))
}
