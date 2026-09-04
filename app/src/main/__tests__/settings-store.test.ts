import { mkdtempSync, readFileSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { DEFAULT_SETTINGS } from '../../shared/ipc'
import { SettingsStore } from '../settings-store'

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
})
