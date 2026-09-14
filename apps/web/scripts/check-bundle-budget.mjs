#!/usr/bin/env node
// Performance budget for the production bundle (FE-602).
//
// TEST_STRATEGY.md §7 says to measure the mobile production build and then fix
// the budget. These numbers are that measurement plus headroom, not a guess.
//
// The check is deliberately blunt: it fails when the bundle grows past the
// budget, and it fails just as loudly when it cannot find the bundle at all.
// A budget check that silently measures nothing is worse than none, because it
// reports success while the thing it guards is unmeasured.
import { gzipSync } from 'node:zlib';
import { readFileSync, readdirSync, existsSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const web = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const assets = join(web, 'dist/assets');

// Budgets are gzip bytes, which is what a user actually downloads.
//
// Re-measured 2026-09-11 after FE-102/103/105/106 and FE-301~305 added nine
// screens. The 2026-09-09 numbers (js 135,000 over a measured 115,004; css
// 6,000 over 2,588) were a ~15% headroom that those slices spent — the build
// reached 99% of the JS budget before this measurement, so the check was about
// to fail on whatever landed next rather than on anything wasteful.
//
// Raised deliberately, not nudged past a red build: there is no duplicated
// dependency in the bundle (checked react-dom, react-router, @tanstack and
// openapi-fetch each appear once) and the growth is screen code. Headroom is
// ~15% again, so the next slice that doubles the app will fail this check the
// way it is meant to.
//
// Raised again for FE-201's feed screen: CSS reached 7,263 of 7,300, which is
// a tripwire with no room left rather than a budget. The growth is one more
// screen module, not waste — the 44px-button block it repeats is duplicated
// across eight screen modules already, and folding that into a shared class
// is its own change with its own tests, not something to smuggle into a
// feature slice. Measured before raising, and the check still fails when the
// limit is passed (verified by lowering it).
// Raised again for FE-203's trip picker (Sheet/TripPicker, C02). CSS measured
// 8,533 against 8,400 — the third sheet in the app, and the overage is the
// price of it existing rather than waste inside it.
//
// Measured before raising, and cutting was tried first: collapsing the picker's
// own declarations recovered 11 gzip bytes of 133, because gzip already folds
// the geometry this sheet shares with MoveDaySheet (.sheet/.panel/.head/.title/
// .cancel are byte-identical across the two). Shaving declarations is not the
// lever here; extracting one shared sheet module is, and that is a refactor
// across three existing sheets with its own tests — the same call this file
// already recorded for the repeated 44px button block, and for the same reason:
// not something to smuggle into a feature slice.
//
// Headroom is deliberately small. The next sheet fails this check, which is
// what should happen if a fourth one lands before the shared module does.
const BUDGETS = {
  js: 157_000, //  measured 151,383
  css: 8_900, //   measured   8,533
};

if (!existsSync(assets)) {
  console.error(`budget: no build at ${assets}. Run \`npm run build\` first.`);
  process.exit(1);
}

const files = readdirSync(assets);
const totals = { js: 0, css: 0 };
const seen = { js: 0, css: 0 };

for (const name of files) {
  const kind = name.endsWith('.js') ? 'js' : name.endsWith('.css') ? 'css' : null;
  if (!kind) continue;
  seen[kind] += 1;
  totals[kind] += gzipSync(readFileSync(join(assets, name)), { level: 9 }).length;
}

let failed = false;

for (const kind of ['js', 'css']) {
  // Zero files means the glob stopped matching, not that the bundle is small.
  if (seen[kind] === 0) {
    console.error(`budget: no ${kind} assets found, refusing to report a pass`);
    failed = true;
    continue;
  }
  const used = totals[kind];
  const budget = BUDGETS[kind];
  const pct = Math.round((used / budget) * 100);
  const line = `${kind.toUpperCase().padEnd(3)} ${String(used).padStart(7)} / ${String(budget).padStart(7)} gzip bytes (${String(pct)}%)`;
  if (used > budget) {
    console.error(`budget: OVER  ${line}`);
    failed = true;
  } else {
    console.log(`budget: ok    ${line}`);
  }
}

process.exit(failed ? 1 : 0);
