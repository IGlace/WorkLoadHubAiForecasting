// Launches the built app once, headless, and checks that the sandboxed preload
// actually exposed `window.whf` (the C1 regression) and that the service
// reached a final state. Exit code 0 means the bridge is present, 1 otherwise.
//
// Launched as `electron .` from the app directory (not `electron out/main/index.js`)
// so that app.getAppPath() resolves to the app root, matching a real install: the
// dev serviceCommand fallback (`uv run whf serve` from `../service`) and iconPath
// both depend on that root being correct.
import { spawn } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { dirname } from 'node:path'

const appDir = dirname(dirname(fileURLToPath(import.meta.url)))

const child = spawn('npx', ['electron', '.'], {
  cwd: appDir,
  env: {
    ...process.env,
    ELECTRON_ENABLE_LOGGING: '1',
    WHF_SMOKE: '1',
    // Needed to launch Electron's sandboxed renderer while running as root
    // (e.g. in a container); has no effect on a packaged, non-root install.
    ELECTRON_DISABLE_SANDBOX: '1',
  },
  stdio: 'inherit',
})

child.on('exit', (code) => process.exit(code ?? 1))
child.on('error', (err) => {
  console.error('SMOKE failed to launch electron:', err)
  process.exit(1)
})
