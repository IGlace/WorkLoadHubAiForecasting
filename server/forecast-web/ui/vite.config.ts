import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// The dev server proxies /api to the Spring application on 8080; the built files are served by it from ui/dist.
// The unit tests are configured in vitest.config.ts (vitest bundles its own Vite, whose plugin types differ).
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: { '/api': { target: process.env.FORECAST_WEB_API ?? 'http://localhost:8080', changeOrigin: false } },
  },
  build: { outDir: 'dist', emptyOutDir: true },
})
