import { defineConfig } from '@playwright/test';

// One config for both modes. Locally it starts the dev server; inside the
// docker-integration gate the compose file sets PLAYWRIGHT_BASE_URL to the
// composed web service and no server is started. PLAYWRIGHT_MOCK_BASE_URL is
// the explicit escape hatch for an already-running mock server (for example,
// when the developer's real-API Vite server already owns port 5173).
const mockBaseURL = process.env.PLAYWRIGHT_MOCK_BASE_URL;
const integration = mockBaseURL
  ? undefined
  : (process.env.PLAYWRIGHT_BASE_URL ?? process.env.WEB_BASE_URL);

export default defineConfig({
  testDir: './e2e',
  // Specs whose assertions depend on the deterministic MSW catalogue belong
  // to the local visual-regression suite, not to the composed API gate. The
  // latter intentionally starts a fresh anonymous session and reads whatever
  // the integration seed exposes, so fixed post counts, fixture trip names,
  // and fixture UUIDs are not contract assertions there.
  //
  // Exclude the files at collection time instead of calling test.skip(): the
  // report gate rejects skipped JUnit cases. The real-API suite still runs all
  // non-mock specs, including session, trip creation, keyboard, responsive,
  // and API-backed seeded-trip journeys.
  testIgnore: integration ? ['**/*.mock.spec.ts'] : [],
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  // JUnit alongside the readable one in CI. check_test_reports.py reads JUnit and nothing
  // else, so an acceptance ID proven by a browser test was invisible to it - and moving such
  // an ID into the frontend plan makes it weaker, not visible, because validate_frontend_plan
  // compares a card's evidence to that card's own IDs and never opens a report (#208). The
  // path ends in e2e/ because the reader looks for <dir>/<suite>/*.xml, and
  // compose.integration.yml already binds playwright-report out of the container.
  reporter: process.env.CI
    ? // The json reporter rides alongside because JUnit cannot express a retry. Measured with
      // this Playwright (1.56): a test that fails once and passes on the retry is written as a
      // plain <testcase> with no <failure>, inside a <testsuites failures="0">, and the run
      // exits 0 - indistinguishable from a clean pass, which is exactly what
      // check_test_reports.py reads. The same run's json carries status: 'flaky' and
      // stats.flaky, which is what scripts/check_e2e_flaky.py records.
      [
        ['line'],
        ['junit', { outputFile: 'playwright-report/e2e/results.xml' }],
        ['json', { outputFile: 'playwright-report/e2e/results.json' }],
      ]
    : 'list',
  use: {
    baseURL: mockBaseURL ?? integration ?? 'http://127.0.0.1:5173',
    trace: 'on-first-retry',
    // 360px is the narrowest supported width (.claude/rules/frontend.md).
    viewport: { width: 360, height: 800 },
    isMobile: true,
    hasTouch: true,
  },
  webServer:
    integration || mockBaseURL
      ? undefined
      : {
          // --host binds 127.0.0.1 as well as ::1. Without it Vite listens on
          // IPv6 localhost only, Playwright's IPv4 baseURL never connects, and
          // the suite runs against a blank page -- assertions on absent elements
          // fail loudly, but any probe that only measures layout would "pass"
          // while measuring nothing.
          command: 'npm run dev:mock -- --host 127.0.0.1',
          url: 'http://127.0.0.1:5173',
          reuseExistingServer: !process.env.CI,
        },
});
