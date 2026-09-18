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
// The real build by default. Overridable so the guard itself can be tested:
// FE-602-T1 asks that an overage is REPORTED as a failure, and the only way to
// see that without shipping an over-budget bundle is to point the check at a
// directory built for the purpose. The gate never sets this.
const assets = process.env.NULLNULL_BUNDLE_DIR
  ? resolve(process.env.NULLNULL_BUNDLE_DIR)
  : join(web, 'dist/assets');

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
//
// The shared module landed (shared/ui/styles/sheet.module.css), and this file's
// prediction was right about the lever and wrong about the size: it recovered
// 86 gzip bytes, not the ~1,348 a naive measurement suggested. The mistake is
// worth keeping because it is easy to repeat — `cat a b c | gzip` measures
// "compressed apart vs together", but Vite already emits one CSS file, so the
// duplicate bytes were being folded before the refactor started. What the
// extraction actually removed was three ::backdrop rules becoming one.
//
// Raised for FE-503's optimization preview. CSS measured 8,810 against 8,900
// with 90 bytes of headroom, and the screen needs the two components it has
// never bundled - MetricDelta and DecisionBar are built but no screen imports
// them, so their rules are not in the output at all (verified: `.value` and
// `.unavailable` appear nowhere in the built CSS). Adding them measured ~462
// gzip bytes before the screen's own module, which comparable screens put at
// 719 (OptimizationRunScreen) to 920 (OptimizeSetupScreen).
//
// Cutting was tried first, again, and this time there was nothing left to cut.
// The remaining duplication is the 44px touch-target declaration this file has
// pointed at twice. Deleting ALL SIXTY of them - which would break the
// accessibility rule they exist to satisfy - recovers 91 gzip bytes of 9,141.
// gzip folds that repetition already. There is no waste inside the largest
// modules either; they are screen content (TripScreen 1,890, TripWizardScreen
// 1,630). So the overage is the price of the preview existing, and the honest
// move is to raise the number rather than to shave something load-bearing.
//
// JS is raised in the same edit and for the same slice. It sat at 155,007 of
// 157,000 - 1,993 bytes - while MetricDelta and DecisionBar are 4,114 bytes of
// source that nothing imports yet, so the preview would have failed this check
// on the JS line immediately after clearing the CSS one. Raising one of the two
// would have bought a second late failure rather than a working slice.
//
// Headroom is ~15% again on both, the same fraction the 2026-09-11 re-measure
// chose, so the next screen that doubles this app still fails the way it should.
//
// CSS raised again, and this entry exists because the entry above it planned a
// slice that then did not fit. That plan was 8,810 + ~462 + 719~920 ≈ 10,200.
// CSS is now 9,978 before any of it: FE-103's two steps (ManualStopsStep 4,256
// and ConfirmStopsStep 3,215 raw), ScheduleCandidateSheet and edits to Feed and
// Profile spent the 1,168 the preview was holding. Nobody overspent — the plan
// reserved room in a number that later slices could not see was reserved.
//
// Two measurements, both by building rather than estimating:
//
//   MetricDelta + DecisionBar cost 153 gzip bytes, not the ~462 this file
//   predicted. The prediction was made the way this file has twice warned
//   against, and it was wrong by 3x in the direction that looks safe.
//   Measuring them needs a real reference, not a side-effect import: Vite
//   tree-shakes `import './x.module.css'` and the first attempt reported a
//   delta of 0, which reads exactly like "already bundled". What separated the
//   two was checking for `.unavailable` in the output alongside the number.
//
//   Cutting was tried first and there is still nothing to cut. The two FE-103
//   steps share 1,105 raw bytes of byte-identical rule bodies (.dot, .stop,
//   .day, .dayHead, .dayName, .dayDate, .stops, .stop::before — they draw the
//   same day-by-day stop list). Deleting all of them from one file recovers
//   43 gzip bytes. That is the same 26x-over lesson as the sheet module above,
//   now measured a fourth time, so this file should stop being asked.
//
// 12,900 covers the preview (153 + ~700 for its screen module, sized against
// OptimizationRunScreen's 2,012 raw) and /sign-in (~400, two inputs and a
// button, simpler than any screen here), and leaves ~15% on top of that.
// JS is NOT raised: it sits at 160,022 of 178,000 with 17,978 free, and the
// two components are 4,114 bytes of source before minify. The entry above
// raised both because both were near the line; only one is now.
const BUDGETS = {
  js: 178_000, //  measured 160,022
  css: 12_900, //  measured   9,978
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
