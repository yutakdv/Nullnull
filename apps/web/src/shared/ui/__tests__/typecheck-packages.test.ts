import { execFileSync } from 'node:child_process';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';

// FE-005-T1: planting a type error in either package fails the gate, and a scan
// that matches no files fails too.
//
// The gate runs this script on the real packages in verify:ci, which answers
// "do they type-check today". FE-005-T1 asks the other question - whether the
// gate would SAY so if they did not - and the second half of the clause is the
// one that matters most: `tsc --noEmit` on a tsconfig whose `include` resolves
// to nothing exits 0, so a gate without the file count would report a pass for
// a package it never opened. That is the failure this script exists to prevent
// and it is invisible from a green CI run.
//
// Runs the real script against a temporary workspace rather than importing it:
// what is under test is the exit code CI reads.
//
// SCOPE: the two guards below, not the compile itself. A temporary workspace
// has no local TypeScript, so `npx tsc` there resolves to an unrelated system
// binary ("This is not the tsc command you are looking for") and a case built
// on it would be testing the sandbox. The real compile is covered by the gate
// running this script on the real packages every verify:ci; what the gate
// cannot show is that the script refuses to pass when there is nothing to
// compile, which is exactly what these two cases pin.

const script = resolve(__dirname, '../../../../../../scripts/typecheck-packages.mjs');

let root: string | null = null;

/** A workspace with the two packages the script checks. */
function workspace(
  files: { apiClient?: Record<string, string>; contracts?: Record<string, string> },
  include: string[] = ['src'],
): string {
  root = mkdtempSync(join(tmpdir(), 'nullnull-tsc-'));
  for (const [key, rel] of [
    ['apiClient', 'packages/api-client'],
    ['contracts', 'packages/contracts'],
  ] as const) {
    const dir = join(root, rel);
    mkdirSync(join(dir, 'src'), { recursive: true });
    writeFileSync(
      join(dir, 'tsconfig.json'),
      JSON.stringify({
        compilerOptions: { strict: true, noEmit: true, skipLibCheck: true },
        include,
      }),
    );
    const sources = files[key] ?? { 'index.ts': 'export const ok: number = 1;\n' };
    for (const [name, body] of Object.entries(sources)) {
      writeFileSync(join(dir, 'src', name), body);
    }
  }
  return root;
}

function run(workspaceRoot: string): { code: number; out: string } {
  try {
    const out = execFileSync('node', [script], {
      env: { ...process.env, NULLNULL_WORKSPACE_ROOT: workspaceRoot },
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

afterEach(() => {
  if (root) rmSync(root, { recursive: true, force: true });
  root = null;
});

describe('FE-005-T1 the packages typecheck gate refuses an empty scan', () => {
  it('fails when the tsconfig matches no files, rather than reporting a pass', () => {
    // The silent case: tsc exits 0 here, so without the file count the gate
    // would print a pass for a package nothing was checked in.
    const result = run(workspace({}, ['does-not-exist']));
    expect(result.code).not.toBe(0);
    expect(result.out).toMatch(
      /no TypeScript sources matched|refusing to report a pass/i,
    );
  });

  it('fails when a package has no tsconfig at all', () => {
    const dir = workspace({});
    rmSync(join(dir, 'packages/contracts/tsconfig.json'));
    const result = run(dir);
    expect(result.code).not.toBe(0);
    expect(result.out).toMatch(/tsconfig\.json is missing/i);
  });
});
