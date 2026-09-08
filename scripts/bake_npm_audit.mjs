#!/usr/bin/env node
// Produces the npm audit report that scripts/check_npm_audit_report.py judges offline.
//
// security-scan runs on integration-internal (internal: true) and npm audit needs the
// registry's advisory endpoint, so the report is written here, at image build time, where the
// network still exists.
//
// The audit scope lives in this file on purpose. The report does not record which flags
// produced it — metadata.dependencies counts dev packages either way — so a checker cannot
// tell an --omit=dev report from a full one. Keeping the flags in the Dockerfile would make
// the gate's scope an unenforced convention: dropping --omit=dev silently widens the gate to
// dev-only advisories, which the audit gate has always excluded. Measured on PR #17's
// lockfile: --omit=dev reports high=1, without it high=2 and critical=1.
//
// Exit code is deliberately 0 whenever a report was written, including when npm audit found
// vulnerabilities or could not reach the registry. Judging is the checker's job, and it treats
// an empty, truncated or wrongly shaped report as a failure. This script fails only when it
// cannot write a report at all, which must break the build loudly.

import { spawnSync } from 'node:child_process';
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';

const DEFAULT_REPORT_PATH = '/workspace/.audit/npm-audit.json';
// --audit-level is not passed: it only changes npm's exit code, which this script ignores.
// The blocking threshold belongs to check_npm_audit_report.py, in one place.
const AUDIT_ARGS = ['audit', '--omit=dev', '--json'];

const reportPath = resolve(process.argv[2] ?? DEFAULT_REPORT_PATH);

const audit = spawnSync('npm', AUDIT_ARGS, {
  cwd: process.cwd(),
  encoding: 'utf8',
  maxBuffer: 64 * 1024 * 1024,
});

if (audit.error) {
  console.error(`bake-npm-audit error: cannot run npm ${AUDIT_ARGS.join(' ')}: ${audit.error.message}`);
  process.exit(1);
}

// npm exits non-zero as soon as it finds anything, and writes the report to stdout regardless.
// A registry failure leaves stdout empty; the report is still written so the checker sees it.
const body = audit.stdout ?? '';

try {
  mkdirSync(dirname(reportPath), { recursive: true });
  writeFileSync(reportPath, body, 'utf8');
} catch (error) {
  console.error(`bake-npm-audit error: cannot write ${reportPath}: ${error.message}`);
  process.exit(1);
}

console.error(`bake_npm_audit=written path=${reportPath} bytes=${body.length} npm_exit=${audit.status}`);
