#!/usr/bin/env node
// infra:check — root script required by scripts/verify_target_stack.py and run
// inside the apps/web Dockerfile `tooling` stage.
//
// ARC-001 D3 has not decided whether this runs a real CDK synth/diff at M0.
// Until `infra/` exists and that decision lands, this fails closed when the
// directory is present but unusable, and reports "not yet scaffolded" when it
// is absent. It never reports success for work that did not happen.

import { access, readdir } from 'node:fs/promises';
import { join } from 'node:path';

const root = new URL('..', import.meta.url).pathname;
const infraDir = join(root, 'infra');

async function main() {
  try {
    await access(infraDir);
  } catch {
    console.log('infra:check skipped — infra/ is not scaffolded yet (INF-001).');
    console.log('This is not a passing infrastructure check.');
    return 0;
  }

  const entries = await readdir(infraDir);
  if (entries.length === 0) {
    console.error('infra:check failed — infra/ exists but is empty.');
    return 1;
  }

  console.error(
    'infra:check failed — infra/ exists but no synth/diff command is wired.',
  );
  console.error('Resolve ARC-001 D3 and replace this stub before M0 sign-off.');
  return 1;
}

process.exitCode = await main();
