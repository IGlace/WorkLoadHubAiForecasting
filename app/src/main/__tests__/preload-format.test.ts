import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

// Sandboxed preloads cannot use ESM (a top-level `import` throws), so the preload
// build must be forced to CommonJS and index.ts must load the `.cjs` output.
// This is a text-level regression guard: it fails the moment either side drifts,
// without needing to load Electron (main-process tests must not import it).

describe('sandboxed preload build format', () => {
  const configSrc = readFileSync(join(__dirname, '../../../electron.vite.config.ts'), 'utf8')
  const indexSrc = readFileSync(join(__dirname, '../index.ts'), 'utf8')

  it('forces the preload build output to CommonJS', () => {
    expect(configSrc).toContain("format: 'cjs'")
    expect(configSrc).toContain("entryFileNames: '[name].cjs'")
  })

  it('loads the CommonJS preload artifact from the main process', () => {
    expect(indexSrc).toContain('../preload/index.cjs')
    expect(indexSrc).not.toContain('../preload/index.mjs')
  })
})
