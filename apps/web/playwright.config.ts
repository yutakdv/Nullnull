import { defineConfig, devices } from '@playwright/test';

// Local/dev runs. The integration gate uses playwright.integration.config.ts,
// which points at the composed web service instead of a dev server.
export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? 'line' : 'list',
  use: {
    baseURL: 'http://127.0.0.1:5173',
    trace: 'on-first-retry',
  },
  // 360px is the narrowest supported width (.claude/rules/frontend.md).
  projects: [
    {
      name: 'mobile-360',
      use: { ...devices['Pixel 5'], viewport: { width: 360, height: 800 } },
    },
  ],
  webServer: {
    command: 'npm run dev',
    url: 'http://127.0.0.1:5173',
    reuseExistingServer: !process.env.CI,
  },
});
