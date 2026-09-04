import { mkdirSync, readFileSync, renameSync, rmSync, writeFileSync } from 'node:fs'
import { dirname } from 'node:path'
import { DEFAULT_SETTINGS, type Settings } from '../shared/ipc'

const LANGUAGES = new Set(['en', 'fr'])

/**
 * What a valid value looks like for each setting. Both users of these rules read them from here:
 * `sanitize` applies them to a file that may have been edited by hand, and `isValidSettingsPatch`
 * applies them at the IPC boundary, which used to keep a second copy that could drift.
 */
const VALIDATORS: Record<keyof Settings, (v: unknown) => boolean> = {
  language: (v) => typeof v === 'string' && LANGUAGES.has(v),
  model: (v) => typeof v === 'string' || v === null,
  launchAtLogin: (v) => typeof v === 'boolean',
  closeToTray: (v) => typeof v === 'boolean',
}

const KEYS = Object.keys(DEFAULT_SETTINGS) as (keyof Settings)[]

function sanitize(raw: unknown): Settings {
  const out: Settings = { ...DEFAULT_SETTINGS }
  if (typeof raw !== 'object' || raw === null) return out
  const r = raw as Record<string, unknown>
  for (const key of KEYS) {
    if (key in r && VALIDATORS[key](r[key])) (out as unknown as Record<string, unknown>)[key] = r[key]
  }
  return out
}

/** A patch is valid when every key it carries is a known setting with a value of the right shape. */
export function isValidSettingsPatch(value: unknown): value is Partial<Settings> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) return false
  return Object.entries(value).every(
    ([key, v]) => Object.prototype.hasOwnProperty.call(VALIDATORS, key) && VALIDATORS[key as keyof Settings](v),
  )
}

/** The file operations, injectable so a test can make the rename fail the way Windows does. */
export interface SettingsFs {
  mkdirSync: typeof mkdirSync
  readFileSync: typeof readFileSync
  writeFileSync: typeof writeFileSync
  renameSync: typeof renameSync
  rmSync: typeof rmSync
}

export class SettingsStore {
  private readonly fs: SettingsFs

  constructor(private readonly filePath: string, fs: Partial<SettingsFs> = {}) {
    this.fs = { mkdirSync, readFileSync, writeFileSync, renameSync, rmSync, ...fs }
  }

  get(): Settings {
    try { return sanitize(JSON.parse(this.fs.readFileSync(this.filePath, 'utf8') as string)) }
    catch { return { ...DEFAULT_SETTINGS } }
  }

  set(patch: Partial<Settings>): Settings {
    const next = sanitize({ ...this.get(), ...patch })
    this.fs.mkdirSync(dirname(this.filePath), { recursive: true })
    // A unique-per-write name so two overlapping writes (e.g. two calls racing before
    // either renameSync completes) never clobber each other's temp file.
    const tmp = `${this.filePath}.${process.pid}.${Date.now()}.tmp`
    this.fs.writeFileSync(tmp, JSON.stringify(next, null, 2))
    try {
      this.fs.renameSync(tmp, this.filePath)
    } catch (err) {
      // On Windows the rename loses to anything holding the target open. Take the temp file with
      // us rather than leave one behind per attempt, then let the caller see the real failure.
      this.fs.rmSync(tmp, { force: true })
      throw err
    }
    return next
  }
}
