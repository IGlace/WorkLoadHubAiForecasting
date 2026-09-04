import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

export default defineConfig({
  test: {
    projects: [
      {
        test: { name: 'main', environment: 'node', include: ['src/main/**/*.test.ts', 'src/shared/**/*.test.ts'] },
      },
      {
        plugins: [react()],
        test: {
          name: 'renderer',
          environment: 'jsdom',
          globals: true,
          setupFiles: ['src/renderer/src/test/setup.ts'],
          include: ['src/renderer/**/*.test.{ts,tsx}'],
        },
      },
    ],
  },
})
