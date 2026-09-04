import nodeFs from 'node:fs'
import { isAbsolute, join, sep } from 'node:path'
import { format } from 'node:util'

// Creates `dir` one path segment at a time instead of relying on Node's
// `mkdirSync(dir, { recursive: true })`: on some restricted/virtual
// filesystems (e.g. a sandboxed /proc) that built-in recursive walk can spin
// forever retrying a segment that keeps reporting ENOENT even though its
// parent already exists. Doing it manually makes exactly one attempt per
// segment and surfaces any non-EEXIST error immediately instead of looping.
function mkdirRecursive(fs: typeof nodeFs, dir: string): void {
  const segments = dir.split(sep)
  let current = isAbsolute(dir) ? sep : ''
  for (const segment of segments) {
    if (!segment) continue
    current = current ? join(current, segment) : segment
    try {
      fs.mkdirSync(current)
    } catch (err) {
      if ((err as NodeJS.ErrnoException).code !== 'EEXIST') throw err
    }
  }
}

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
    try { mkdirRecursive(this.fs, opts.dir) } catch { this.failed = true }
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
