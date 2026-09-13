import { defineConfig } from '@playwright/test';

// One config for both modes. Locally it starts the dev server; inside the
// docker-integration gate the compose file sets PLAYWRIGHT_BASE_URL to the
// composed web service and no server is started.
const integration = process.env.PLAYWRIGHT_BASE_URL ?? process.env.WEB_BASE_URL;

export default defineConfig({
  testDir: './e2e',
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  // JUnit alongside the readable one in CI. check_test_reports.py reads JUnit and nothing
  // else, so an acceptance ID proven by a browser test was invisible to it - and moving such
  // an ID into the frontend plan makes it weaker, not visible, because validate_frontend_plan
  // compares a card's evidence to that card's own IDs and never opens a report (#208). The
  // path ends in e2e/ because the reader looks for <dir>/<suite>/*.xml, and
  // compose.integration.yml already binds playwright-report out of the container.
  reporter: process.env.CI
    ? [['line'], ['junit', { outputFile: 'playwright-report/e2e/results.xml' }]]
    : 'list',
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
        // --host binds 127.0.0.1 as well as ::1. Without it Vite listens on
        // IPv6 localhost only, Playwright's IPv4 baseURL never connects, and
        // the suite runs against a blank page -- assertions on absent elements
        // fail loudly, but any probe that only measures layout would "pass"
        // while measuring nothing.
        command: 'npm run dev -- --host 127.0.0.1',
        url: 'http://127.0.0.1:5173',
        reuseExistingServer: !process.env.CI,
      },
});
