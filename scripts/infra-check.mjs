#!/usr/bin/env node
// Root infra:check required by scripts/verify_target_stack.py. ARC-001 D3 has
// not decided what M0 runs here, so this reports honestly and never fakes a pass.
import { existsSync } from 'node:fs';

if (existsSync(new URL('../infra', import.meta.url))) {
  console.error('infra:check failed — infra/ exists but no synth/diff is wired (ARC-001 D3).');
  process.exitCode = 1;
} else {
  console.log('infra:check skipped — infra/ not scaffolded (INF-001). Not a passing check.');
}
