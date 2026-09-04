import { mkdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs'
import { dirname } from 'node:path'
import { DEFAULT_SETTINGS, type Settings } from '../shared/ipc'

const LANGUAGES = new Set(['en', 'fr'])

function sanitize(raw: unknown): Settings {
  const out: Settings = { ...DEFAULT_SETTINGS }
  if (typeof raw !== 'object' || raw === null) return out
  const r = raw as Record<string, unknown>
  if (typeof r['language'] === 'string' && LANGUAGES.has(r['language'])) out.language = r['language'] as Settings['language']
  if (typeof r['model'] === 'string' || r['model'] === null) out.model = r['model'] as string | null
  if (typeof r['launchAtLogin'] === 'boolean') out.launchAtLogin = r['launchAtLogin']
  if (typeof r['closeToTray'] === 'boolean') out.closeToTray = r['closeToTray']
  return out
}

export class SettingsStore {
  constructor(private readonly filePath: string) {}

  get(): Settings {
    try { return sanitize(JSON.parse(readFileSync(this.filePath, 'utf8'))) }
    catch { return { ...DEFAULT_SETTINGS } }
  }

  set(patch: Partial<Settings>): Settings {
    const next = sanitize({ ...this.get(), ...patch })
    mkdirSync(dirname(this.filePath), { recursive: true })
    // A unique-per-write name so two overlapping writes (e.g. two calls racing before
    // either renameSync completes) never clobber each other's temp file.
    const tmp = `${this.filePath}.${process.pid}.${Date.now()}.tmp`
    writeFileSync(tmp, JSON.stringify(next, null, 2))
    renameSync(tmp, this.filePath)
    return next
  }
}
