import { defineConfig } from 'vitest/config'

export default defineConfig({
  test: {
    include: ['qa/rules/**/*.test.ts'],
    testTimeout: 15_000,
    hookTimeout: 30_000,
  },
})
