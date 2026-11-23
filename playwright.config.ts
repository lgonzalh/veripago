import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: './qa/e2e',
  fullyParallel: false,
  reporter: 'list',
  use: {
    baseURL: 'http://127.0.0.1:4174/VeriPago/',
    browserName: 'chromium',
    headless: true,
    launchOptions: {
      executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE
        ?? 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
    },
    viewport: { width: 1365, height: 900 },
    screenshot: 'only-on-failure',
    trace: 'retain-on-failure',
  },
  webServer: {
    command: 'pnpm build && pnpm preview --host 127.0.0.1 --port 4174',
    url: 'http://127.0.0.1:4174/VeriPago/',
    reuseExistingServer: !process.env.CI,
    timeout: 30_000,
  },
})
