import { mkdtempSync, readdirSync, readFileSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { DEFAULT_SETTINGS } from '../../shared/ipc'
import { isValidSettingsPatch, SettingsStore } from '../settings-store'

describe('SettingsStore', () => {
  it('returns defaults when the file is missing or invalid', () => {
    const dir = mkdtempSync(join(tmpdir(), 'whf-'))
    expect(new SettingsStore(join(dir, 'settings.json')).get()).toEqual(DEFAULT_SETTINGS)
    writeFileSync(join(dir, 'bad.json'), '{not json')
    expect(new SettingsStore(join(dir, 'bad.json')).get()).toEqual(DEFAULT_SETTINGS)
  })
  it('merges patches, persists them and ignores unknown keys', () => {
    const dir = mkdtempSync(join(tmpdir(), 'whf-'))
    const file = join(dir, 'settings.json')
    const store = new SettingsStore(file)
    expect(store.set({ language: 'fr', model: 'gpt-5' })).toEqual({ ...DEFAULT_SETTINGS, language: 'fr', model: 'gpt-5' })
    writeFileSync(file, JSON.stringify({ ...JSON.parse(readFileSync(file, 'utf8')), junk: 1, language: 'de' }))
    expect(new SettingsStore(file).get()).toEqual({ ...DEFAULT_SETTINGS, model: 'gpt-5' })
  })
  it('leaves no .tmp file behind after a write, using a unique name per write', () => {
    const dir = mkdtempSync(join(tmpdir(), 'whf-'))
    const file = join(dir, 'settings.json')
    new SettingsStore(file).set({ language: 'fr' })
    expect(readdirSync(dir).some((f) => f.endsWith('.tmp'))).toBe(false)
  })
  it('cleans up the temp file when the rename fails, and still reports the failure', () => {
    // On Windows the rename loses to anything holding settings.json open; without this the
    // orphaned .tmp files would accumulate on every attempt.
    const dir = mkdtempSync(join(tmpdir(), 'whf-'))
    const file = join(dir, 'settings.json')
    const store = new SettingsStore(file, { renameSync: () => { throw new Error('EPERM') } })
    expect(() => store.set({ language: 'fr' })).toThrow('EPERM')
    expect(readdirSync(dir)).toEqual([])
  })
  it('validates a patch with the same rules it sanitizes a file with', () => {
    // One source of truth: the IPC boundary used to keep a second copy of these rules.
    expect(isValidSettingsPatch({ language: 'fr', closeToTray: false })).toBe(true)
    expect(isValidSettingsPatch({ language: 'de' })).toBe(false)
    expect(isValidSettingsPatch({ model: 12 })).toBe(false)
    expect(isValidSettingsPatch({ unknown: 1 })).toBe(false)
    expect(isValidSettingsPatch([{ language: 'fr' }])).toBe(false)
    expect(isValidSettingsPatch(null)).toBe(false)
  })
})
