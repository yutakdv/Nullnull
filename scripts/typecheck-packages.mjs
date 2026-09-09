#!/usr/bin/env node
// Type-check the workspace packages that apps/web consumes.
//
// apps/web's own tsc reaches into these packages today only because they export
// raw .ts from "exports". That coverage is a side effect: the day either package
// exports built .d.ts instead, web's tsc stops seeing the sources and the check
// disappears with no error. This script checks them directly so the gate does not
// depend on that accident.
//
// It also refuses to pass vacuously. tsc prints its help text and exits non-zero
// when it cannot find a project, and an empty "include" would let a real run check
// nothing at all, so both are treated as failures rather than as silence.
import { spawnSync } from 'node:child_process';
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const packages = ['packages/api-client', 'packages/contracts'];

function countTypeScriptSources(directory) {
  let total = 0;
  for (const entry of readdirSync(directory)) {
    const path = join(directory, entry);
    if (statSync(path).isDirectory()) {
      total += countTypeScriptSources(path);
    } else if (entry.endsWith('.ts') || entry.endsWith('.tsx')) {
      total += 1;
    }
  }
  return total;
}

let failed = false;
for (const relative of packages) {
  const directory = join(root, relative);
  const config = join(directory, 'tsconfig.json');
  if (!existsSync(config)) {
    console.error(`${relative}: tsconfig.json is missing, so tsc would check nothing`);
    failed = true;
    continue;
  }

  // A tsconfig that resolves to no files makes tsc exit 0 without checking anything.
  const include = JSON.parse(readFileSync(config, 'utf8')).include ?? [];
  const sources = include
    .map((pattern) => join(directory, pattern))
    .filter((path) => existsSync(path))
    .reduce((total, path) => total + (statSync(path).isDirectory() ? countTypeScriptSources(path) : 1), 0);
  if (sources === 0) {
    console.error(`${relative}: no TypeScript sources matched, refusing to report a pass`);
    failed = true;
    continue;
  }

  const result = spawnSync('npx', ['tsc', '--noEmit', '--project', config], {
    cwd: directory,
    stdio: 'inherit',
    shell: process.platform === 'win32',
  });
  if (result.status !== 0) {
    failed = true;
    continue;
  }
  console.log(`${relative}: ${sources} TypeScript source(s) type-checked`);
}

process.exit(failed ? 1 : 0);
