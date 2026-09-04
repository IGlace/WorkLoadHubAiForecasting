import { existsSync, mkdtempSync, readFileSync, readdirSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { describe, expect, it, vi } from 'vitest'
import { RotatingLog, installConsoleLogging } from '../logger'

describe('RotatingLog', () => {
  it('creates the directory, timestamps lines and rotates past maxBytes keeping N files', () => {
    const dir = join(mkdtempSync(join(tmpdir(), 'whf-log-')), 'logs')
    const log = new RotatingLog({ dir, maxBytes: 120, keep: 3 })
    for (let i = 0; i < 12; i++) log.write(`line ${i} ${'x'.repeat(20)}`)
    const files = readdirSync(dir).sort()
    expect(files).toEqual(['app.1.log', 'app.2.log', 'app.log'])
    expect(readFileSync(join(dir, 'app.log'), 'utf8')).toMatch(/^\d{4}-\d{2}-\d{2}T[^ ]+ line \d+/)
    expect(existsSync(join(dir, 'app.3.log'))).toBe(false)
  })
  it('never throws when the directory cannot be written', () => {
    const log = new RotatingLog({ dir: '/proc/definitely/not/writable' })
    expect(() => log.write('x')).not.toThrow()
  })
  it('mirrors console output with a level prefix', () => {
    const dir = mkdtempSync(join(tmpdir(), 'whf-log-'))
    const log = new RotatingLog({ dir })
    const original = { log: console.log, warn: console.warn, error: console.error }
    const stdoutSpy = vi.spyOn(process.stdout, 'write').mockImplementation(() => true)
    const stderrSpy = vi.spyOn(process.stderr, 'write').mockImplementation(() => true)
    try {
      installConsoleLogging(log)
      console.warn('careful', 42)
      console.error(new Error('boom'))
    } finally {
      console.log = original.log; console.warn = original.warn; console.error = original.error
      stdoutSpy.mockRestore(); stderrSpy.mockRestore()
    }
    const text = readFileSync(log.path, 'utf8')
    expect(text).toContain('WARN careful 42')
    expect(text).toContain('ERROR Error: boom')
  })
})
