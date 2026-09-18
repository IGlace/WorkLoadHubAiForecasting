import { defineConfig } from 'vitest/config'

// Pure-function tests only (no DOM): the API client's error mapping, the chart scales, the highlighter, the endpoint table.
export default defineConfig({
  test: { include: ['src/**/*.test.ts'] },
})
