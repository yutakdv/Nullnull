import { defineConfig, devices } from '@playwright/test';

// Used by the docker-integration gate. The web service is already running in
// the compose network, so no webServer is started here.
export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  forbidOnly: true,
  retries: 1,
  reporter: [['line'], ['html', { outputFolder: 'playwright-report', open: 'never' }]],
  use: {
    baseURL: process.env.WEB_BASE_URL ?? 'http://web:4173',
    trace: 'retain-on-failure',
  },
  projects: [
    {
      name: 'mobile-360',
      use: { ...devices['Pixel 5'], viewport: { width: 360, height: 800 } },
    },
  ],
});
