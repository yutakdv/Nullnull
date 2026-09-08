import { defineConfig } from '@playwright/test';

// One config for both modes. Locally it starts the dev server; inside the
// docker-integration gate the compose file sets PLAYWRIGHT_BASE_URL to the
// composed web service and no server is started.
const integration = process.env.PLAYWRIGHT_BASE_URL ?? process.env.WEB_BASE_URL;

export default defineConfig({
  testDir: './e2e',
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? 'line' : 'list',
  use: {
    baseURL: integration ?? 'http://127.0.0.1:5173',
    trace: 'on-first-retry',
    // 360px is the narrowest supported width (.claude/rules/frontend.md).
    viewport: { width: 360, height: 800 },
    isMobile: true,
    hasTouch: true,
  },
  webServer: integration
    ? undefined
    : {
        command: 'npm run dev',
        url: 'http://127.0.0.1:5173',
        reuseExistingServer: !process.env.CI,
      },
});
