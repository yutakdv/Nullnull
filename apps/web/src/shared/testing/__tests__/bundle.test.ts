// MSW must not reach the production bundle.
//
// scripts/integration-test.sh runs Playwright against the runtime image (dist
// only) and a real api container. If the built app ever started the mock
// worker, those e2e tests would exercise fixtures instead of the API and the
// docker-integration gate would report success while proving nothing. That
// failure is silent, so this test makes it loud.
//
// The test builds its own bundle rather than reading whatever dist/ happens to
// hold. An earlier version skipped when dist/ was absent, which passed locally
// (a previous build had left one behind) and skipped in every container run,
// because verify:ci runs test before build and the image starts clean. A guard
// that disappears exactly where it is needed is worse than no guard: the suite
// still reported success, so nothing pointed at the hole.
import { execFileSync } from 'node:child_process';
import { mkdtempSync, readFileSync, readdirSync, rmSync, statSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';

const webRoot = resolve(process.cwd());
let outDir = '';
let files: string[] = [];

function bundleFiles(dir: string): string[] {
  return readdirSync(dir).flatMap((entry) => {
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) return bundleFiles(full);
    return full.endsWith('.js') || full.endsWith('.html') ? [full] : [];
  });
}

beforeAll(() => {
  // Build into a temp directory so this never races with, or clobbers, the
  // dist/ that verify:ci produces afterwards.
  outDir = mkdtempSync(join(tmpdir(), 'nullnull-bundle-'));
  execFileSync(
    'npx',
    ['vite', 'build', '--mode', 'production', '--outDir', outDir, '--emptyOutDir'],
    {
      cwd: webRoot,
      stdio: 'pipe',
    },
  );
  files = bundleFiles(outDir);
}, 180_000);

afterAll(() => {
  if (outDir) rmSync(outDir, { recursive: true, force: true });
});

describe('production bundle', () => {
  it('emits javascript to inspect', () => {
    // Without this the needle assertions below would pass vacuously on an
    // empty directory.
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
