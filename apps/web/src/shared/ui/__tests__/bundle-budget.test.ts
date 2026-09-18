import { execFileSync } from 'node:child_process';
import { gzipSync } from 'node:zlib';
import { mkdtempSync, writeFileSync, rmSync, mkdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';

// FE-602-T1: a performance budget overage is REPORTED as a failure and is
// never substituted with a pass.
//
// The check itself runs in verify:ci on the real build, which answers "is the
// bundle within budget today". It does not answer "would this check say so if
// it were not" - and that second question is the one FE-602-T1 asks, because a
// budget that cannot fail is a number nobody is measuring.
//
// The repo has hit that shape twice in this file's own history: the check was
// written to refuse a pass when it finds no build and when it finds no assets
// of a kind, both after a version that would have reported success on an empty
// directory. Those branches are exercised here too.
//
// Runs the real script against a purpose-built directory rather than reaching
// into its internals: the thing under test is the exit code and the message a
// human reads in CI, and neither is observable from an import.

const script = resolve(__dirname, '../../../../scripts/check-bundle-budget.mjs');

let dir: string | null = null;

function budgetDir(files: Record<string, string>): string {
  dir = mkdtempSync(join(tmpdir(), 'nullnull-budget-'));
  const assets = join(dir, 'assets');
  mkdirSync(assets);
  for (const [name, body] of Object.entries(files)) {
    writeFileSync(join(assets, name), body);
  }
  return assets;
}

/** Runs the check, returning its exit code and combined output. */
function run(assets: string): { code: number; out: string } {
  try {
    const out = execFileSync('node', [script], {
      env: { ...process.env, NULLNULL_BUNDLE_DIR: assets },
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    return { code: 0, out };
  } catch (error) {
    const failure = error as { status?: number; stdout?: string; stderr?: string };
    return {
      code: failure.status ?? -1,
      out: `${failure.stdout ?? ''}${failure.stderr ?? ''}`,
    };
  }
}

/**
 * Enough incompressible JS to pass the budget, and a multiple of it to fail.
 *
 * Random-looking text rather than repeated characters: gzip folds a repeated
 * byte to almost nothing, so a "huge" file of one character stays under any
 * budget and the over-budget case would quietly become an under-budget one.
 */
function js(targetGzipBytes: number): string {
  // Built in one pass and then checked, not by appending until gzip agrees:
  // the loop form re-compressed a growing string every iteration and took 38
  // seconds for the over-budget case.
  //
  // The ratio is measured, not assumed. Base36 identifiers were guessed to be
  // near-incompressible and are not - 240KB of them gzip to 115KB, about 48%,
  // because the alphabet is small and `const a…=0.…;` repeats. Overshooting by
  // 2.5x covers that with room, and the caller asserts the real gzip size so a
  // wrong guess fails loudly instead of quietly producing an under-budget file.
  let body = '';
  while (body.length < targetGzipBytes * 2.5) {
    body += `const a${Math.random().toString(36).slice(2)}=${Math.random()};\n`;
  }
  return body;
}

afterEach(() => {
  if (dir) rmSync(dir, { recursive: true, force: true });
  dir = null;
});

describe('FE-602-T1 the bundle budget reports an overage as a failure', () => {
  it('passes a bundle that fits', () => {
    const assets = budgetDir({ 'app.js': js(1_000), 'app.css': 'a{color:red}' });
    const result = run(assets);
    expect(result.out).toMatch(/budget: ok\s+JS/);
    expect(result.code).toBe(0);
  });

  it('fails, rather than warns, when the JS budget is passed', () => {
    // Comfortably past the 178,000 gzip-byte limit.
    const source = js(200_000);
    // The generator has to actually clear the budget, or this case would pass
    // for the wrong reason - a file that merely LOOKS big proves nothing.
    expect(gzipSync(Buffer.from(source)).length).toBeGreaterThan(178_000);
    const assets = budgetDir({ 'app.js': source, 'app.css': 'a{color:red}' });
    const result = run(assets);
    expect(result.out).toMatch(/budget: OVER\s+JS/);
    // The part that matters: a non-zero exit is what stops verify:ci. A check
    // that printed OVER and exited 0 would read as a pass in the gate.
    expect(result.code).not.toBe(0);
  });

  it('refuses to report a pass when there is no build to measure', () => {
    const result = run(join(tmpdir(), 'nullnull-budget-does-not-exist'));
    expect(result.code).not.toBe(0);
    expect(result.out).toMatch(/no build/i);
  });

  it('refuses to report a pass when a kind of asset is missing', () => {
    // A directory with JS and no CSS. Reporting "CSS 0 / 10200 (0%)" here would
    // be the exact failure this check was written against: a green line for
    // something that was never measured.
    const assets = budgetDir({ 'app.js': js(1_000) });
    const result = run(assets);
    expect(result.code).not.toBe(0);
    expect(result.out).toMatch(/no css assets found|refusing to report a pass/i);
  });
});
