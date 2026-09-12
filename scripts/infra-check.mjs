#!/usr/bin/env node
// Root infra:check required by scripts/verify_target_stack.py. ARC-001 D3 has
// not decided what M0 runs here, so this reports honestly and never fakes a pass.
//
// The outcome is stated as a machine token because the exit code cannot carry it: infra/ is not
// scaffolded and BA-006 is blocked, so exiting 1 would red the default branch for a gap nobody
// can close yet, while exiting 0 is what made docker-integration count this as green. The token
// is judged by scripts/check_infra_report.py, which records blocked as blocked and refuses to
// treat it as a pass - and fails outright if this script ever stops stating an outcome.
import { existsSync } from 'node:fs';

if (existsSync(new URL('../infra', import.meta.url))) {
  // infra/ arriving without a synth/diff is the one case that must break the build: the
  // directory existing means the gap this token stands in for is supposed to be closed.
  console.error('infra_check=failed reason=infra-exists-without-synth-or-diff owner=BA-006');
  console.error('infra:check failed — infra/ exists but no synth/diff is wired (ARC-001 D3).');
  process.exitCode = 1;
} else {
  console.log('infra_check=blocked reason=infra-not-scaffolded owner=BA-006');
  console.log('infra:check did not run — infra/ is not scaffolded (INF-001). Not a passing check.');
}
