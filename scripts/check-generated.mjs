#!/usr/bin/env node
// Fails when a generator's output differs from the committed file.
//
// Usage: node scripts/check-generated.mjs <file> -- <generator command...>
//
// Both `tokens:check` and `api:check` used `git diff --exit-code`, which works
// on a developer machine and fails with exit 127 inside the integration
// containers: node:bookworm-slim ships no git, and the build context carries no
// .git directory. The question those checks ask — "does regenerating change the
// committed file?" — needs no git at all. Snapshot, regenerate, compare bytes.
import { spawnSync } from "node:child_process";
import { readFileSync } from "node:fs";

const sep = process.argv.indexOf("--");
const file = process.argv[2];
const command = process.argv.slice(sep + 1);
if (!file || sep === -1 || command.length === 0) {
  console.error("usage: check-generated.mjs <file> -- <generator command...>");
  process.exit(2);
}

const before = readFileSync(file);
const run = spawnSync(command[0], command.slice(1), {
  stdio: "inherit",
  shell: false,
});
if (run.status !== 0) process.exit(run.status ?? 1);

const after = readFileSync(file);
if (!before.equals(after)) {
  console.error(`${file} is out of date: regenerate and commit it.`);
  process.exit(1);
}
console.log(`${file} matches its generator.`);
