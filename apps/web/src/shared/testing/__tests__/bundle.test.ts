// MSW must not reach the production bundle.
//
// scripts/integration-test.sh runs Playwright against the runtime image (dist
// only) and a real api container. If the built app ever started the mock
// worker, those e2e tests would exercise fixtures instead of the API and the
// docker-integration gate would report success while proving nothing. That
// failure is silent, so this test makes it loud.
//
// It reads dist/ if a build is present and skips otherwise, so `npm run test`
// stays fast on a clean checkout. verify:ci runs test before build, so the
// assertion inspects the previous build there and CI keeps a dist around.
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const distDir = resolve(process.cwd(), 'dist');

function bundleFiles(dir: string): string[] {
  return readdirSync(dir).flatMap((entry) => {
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) return bundleFiles(full);
    return full.endsWith('.js') || full.endsWith('.html') ? [full] : [];
  });
}

describe.skipIf(!existsSync(distDir))('production bundle', () => {
  const files = existsSync(distDir) ? bundleFiles(distDir) : [];

  it('emits javascript to inspect', () => {
    expect(files.length).toBeGreaterThan(0);
  });

  it.each(['msw', 'mockServiceWorker', 'setupWorker'])(
    'contains no reference to %s',
    (needle) => {
      const offenders = files.filter((file) =>
        readFileSync(file, 'utf8').includes(needle),
      );
      expect(offenders).toEqual([]);
    },
  );
});
