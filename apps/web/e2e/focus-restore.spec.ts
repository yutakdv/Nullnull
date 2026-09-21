import { expect, test } from '@playwright/test';
import { createRepresentativeTrip } from './seeded-trip.js';

// The "focus 복귀" clause on the saved-places sheet: where focus lands after
// it closes. Carries no acceptance ID -- see WHY THIS TEST CARRIES NO
// ACCEPTANCE ID below.
//
// WHY THIS SCREEN AND NOT THE OTHER THREE the clause names. Of the four screens
// (FE-104 paste import, FE-203 saved places, FE-503/FE-505 optimize setup and
// run), only this one mounts a dialog at all: grep for `showModal`, `<dialog>`
// or `inert` over ImportPasteScreen.tsx, OptimizeSetupScreen.tsx and
// OptimizationRunScreen.tsx answers 0, and ProposalCard.tsx's single hit is a
// comment naming ReplaceSheet, not a dialog of its own. On those three there is
// nothing to close, so there is no focus to return -- the clause is vacuously
// satisfied there and asserting it would measure nothing.
//
// WHY THIS TEST CARRIES NO ACCEPTANCE ID, deliberately rather than by
// oversight (#195: there is no place to give a test ID to a unit of work that
// is not a card).
//
// The screen is FE-303 -- CandidatesScreen.tsx:24 declares `S07-8 candidate
// panel 412:1912`, and IMPLEMENTATION_PLAN.md:268 gives that node to FE-303
// ("S07-8 후보 panel과 일정화 flow"). It is NOT FE-305, which an earlier
// version of this file claimed: FE-305 is "검색/추가/교체/날짜·시간 이동
// variant" (:270) and merely LISTS this sheet's node among nine others. The
// node `527:4695` is cited by both cards, so a node alone does not decide a
// card -- how many cards cite it has to be counted too.
//
// And the `T4` clause it was tagged with (focus restore, split off from `T3`
// by the owner) exists in the issue body but was never written into the
// frontend plan, which is what validate_frontend_plan.py:179 compares a card's
// `evidence.testIds` against. An ID that no card declares is matched by
// nothing, so tagging this test would read as coverage while being checked by
// no one. The behaviour below is measured either way; only the label is
// missing, and an absent label is the honest form of that.
//
// Also worth the next reader's time: this file's subject cites a node that the
// canon does not have. ScheduleCandidateSheet.tsx:7 and CandidatesScreen.tsx:315
// both say `527:4732`, which appears nowhere in docs/; FIGMA_HANDOFF.md:188
// calls this sheet `527:4695`. Trusting that source comment is what sent the
// first version of this header to the wrong card. Raised for an owner ruling.

// WHY A BROWSER. ConfirmDialog.tsx:306 records the measurement: in happy-dom
// every candidate accepts focus (hidden and `inert` elements both became
// activeElement), and `.focus()` inside a CLOSED <dialog> succeeds there while
// the spec makes it a no-op. A unit test cannot tell a restored focus from a
// lost one on this code.
//
// WHY THE COMPLETED SCHEDULE AND NOT Escape OR Cancel. Measured, by deleting
// ScheduleCandidateSheet's `target.focus()` (:89) and re-running: on Escape, on
// Cancel, and on a programmatic close, focus still returned to "Add to a day"
// -- the browser's own <dialog> restore does it, so every assertion on those
// three paths stays green with the subject deleted. keyboard-flow.spec.ts:176
// reached the same wall on MoveDaySheet and left the clause unasserted rather
// than restate it in a form that cannot fail.
//
// The completed schedule is the path where our code is the only thing that can
// work, because the trigger is GONE: a scheduled candidate stops rendering its
// "Add to a day" button (CandidatesScreen.tsx:342 `{scheduled ? null : ...}`),
// so the node the browser would restore to has left the document and the
// restore falls to `afterScheduleRef` (:235) instead. Measured both ways --
// intact: focus on "Remove ... from saved"; with that one line removed: focus
// on <body>, which is what makes this assertion able to fail.

// THE SAVED-PLACES SHEET'S CLAUSE IS NOT HERE, AND THAT IS A GAP, NOT A PASS.
//
// This file held a second case: the saved-places (후보) sheet, asserting that a
// COMPLETED schedule moves focus to the row rather than dropping it on the
// document. It is gone rather than skipped, and the reason it is gone is worth
// more than the code was.
//
// WHY IT CANNOT RUN IN THE GATE, as a chain rather than a guess:
//   scripts/e2e/catalog-seed.sql writes places but no `place_hours` row
//     -> CandidateMatchService.openingHours() hands the evaluator an empty map
//     -> apps/ai .../item/filters.py:143 answers an absent window with
//        `Eligibility.unknown(OPENING_HOURS_UNKNOWN)`
//     -> .../slot/evaluator.py:111-112 still emits a Slot for EVERY day, as
//        `Slot(day, None, False, reason)` when not eligible
//     -> ScheduleCandidateSheet.tsx:156 sets `disabled` on each day row.
// So the `Day N` button is PRESENT AND DISABLED, not missing. The gate log read
// `locator.click: Test timeout ... waiting for locator(...)`, which is
// actionability timing out, not a locator failing to resolve -- getByRole
// matches a disabled button. Read as "the button is absent" it sends the next
// reader into the sheet's markup, which is the wrong file.
//
// WHY IT WAS NOT REWRITTEN TO PASS. Picking whichever day happens to be
// enabled, or bailing when none is, makes a green test that never schedules
// anything -- and the clause is that a COMPLETED schedule restores focus.
// CandidatesScreen.tsx:342 stops rendering the trigger once the candidate is
// scheduled, and that removal is precisely what makes `afterScheduleRef` the
// only thing that can put focus anywhere. No schedule, nothing measured.
//
// WHY THE FIXTURE WAS NOT SEEDED. `place_hours_observations` (V025) requires
// `evidence_url`, which its own comment defines as the page a reviewer actually
// read, and a trigger guards the windows table besides. Seeding it here would
// manufacture review evidence for a place nobody reviewed, in the one table
// whose entire purpose is that values carry provenance. Those rows have a
// production path -- BA-025's curated import (CuratedHoursImportIT).
//
// WHY NOT `test.fixme`, which is the obvious answer and is wrong. MEASURED, by
// generating this file's JUnit with the case marked fixme and feeding it to
// scripts/check_test_reports.py's own `read_junit`: Playwright writes
// `skipped="1"` and a `<skipped/>` child, and that function raises twice --
// `skipped=1, expected 0` (:81-83) and the per-case `testcase skipped` (:98-100).
// A rejected file then counts as ZERO testcases for the suite, so one fixme
// would have discarded the passing FE-203-T4 case below along with it and the
// gate would have read "E2E ran nothing". Do not try it again.
//
// WHAT RESTORES IT: verified opening hours for the candidate's place in the
// gate environment. Everything up to the click already worked there -- the
// sheet opened -- so the case can come back close to as it was, with
// `createSeededTrip` plus a candidate save. Its `saveCandidate` helper and the
// 서울숲 place id went with it and are in this file's history.
//
// LOCALLY IT PASSED, which is the trap that hid this for a whole gate run. MSW
// answers the match request with one eligible slot (measured: `Day 3`,
// `disabled=false`), so the click succeeded on a dev server. A green local run
// says nothing about the gate here, in either direction.

// FE-203 `T4`, the same clause on the OTHER sheet the app has: the 담기 sheet
// (`399:658` S03-C1, `409:1595` S06-1 -- TripPicker.tsx, mounted once by
// FeedScreen.tsx:362).
//
// NOTHING covered this before: no e2e opens TripPicker (the `담기` in
// trip-create.spec.ts:148 is the add-place result row, and keyboard-flow's
// "Escape closes a sheet" opens the itinerary editor's MoveDaySheet), and
// trip-picker.test.tsx:158 asserts only where focus lands when it OPENS.
//
// WHY THE PICK PATH AND NOT Escape OR Cancel, measured the same way as above:
// on Escape and on Cancel focus returns to "Add to my trip" even with
// TripPicker's `target.focus()` (:92) deleted -- the browser's own <dialog>
// restore does it, so an assertion there passes with its subject removed.
//
// The pick path is different, and it is different in the direction that
// matters: it is BROKEN on unmodified code, which is why this test is written
// as a regression and not as a pin.
//
// WHAT WAS MEASURED (3/3 runs, focus sampled at 0/100/300/600/1200ms):
// after picking a trip, focus settles on <body> and stays there, while the
// trigger is still in the document ("Add to my trip" present). A focusin/
// focusout trace ends at `OUT BUTTON:` with NO focusin after the dialog closes
// -- neither our restore nor the browser's fires a landing.
//
// WHY, since the restore code looks right and does run: instrumenting
// HTMLElement.focus shows exactly one call after the pick --
// `focus() on BUTTON:Adding connected=true`. The target is connected, so
// TripPicker's `target?.isConnected` guard (:92) passes and it calls focus().
// But by then FeedScreen has started the save, the trigger has re-rendered as
// TripAddButton state `loading`, and that state is `disabled`
// (TripAddButton.tsx:53). Focusing a disabled button is a no-op, so the call
// succeeds silently and focus is left on <body>. `isConnected` is the wrong
// question here: the node survived, its ABILITY to take focus did not.
//
// This is the failure mode ConfirmDialog.tsx:292 names -- "an element that is
// detached, `display: none`, or inside an `inert` subtree simply does not take
// focus" -- with a fourth cause, `disabled`, on a path nothing was watching.
// ConfirmDialog verifies the landing and falls back to <main>; TripPicker does
// not verify it at all.
//
// The restore now verifies where focus actually landed and falls back to the
// next usable control. This regression keeps that behavior from returning to
// the previously observed <body> focus loss.
test.describe('FE-203-T4 closing the 담기 sheet leaves focus somewhere usable', () => {
  test('FE-203-T4 picking a trip leaves focus on a control, not the document', async ({
    page,
  }) => {
    // The 담기 sheet asks WHICH trip, so it needs at least one to offer. On the
    // dev server MSW supplies one; against the real API a fresh anonymous
    // session owns none, and TripPicker had nothing to list — which is why the
    // gate failed here on the precondition rather than on the focus assertion.
    // createRepresentativeTrip also settles the session bootstrap and selects
    // a real target, so the button is actionable in both API modes.
    await createRepresentativeTrip(page);

    await page.goto('/feed');
    await page.waitForLoadState('networkidle');

    // Two preconditions, separated on purpose: the feed has cards at all, and
    // one of them offers the 담기 control. A feed that rendered empty and a
    // card that lost its button are different failures, and the message below
    // named only the second.
    await expect(
      page.locator('article').first(),
      'the feed drew no cards, so there is nothing to add from',
    ).toBeVisible();

    const trigger = page.getByRole('button', { name: /Add to my trip/ }).first();
    await expect(
      trigger,
      'no feed card offers a trip to add to: the sheet under test never opens',
    ).toBeVisible();
    await trigger.focus();
    await page.keyboard.press('Enter');

    // THE SHEET REALLY OPENED, before anything about focus is asked. Without
    // this, "focus is not on <body>" is trivially true of a screen where
    // nothing happened -- focus would still be sitting on the trigger.
    const sheet = page.locator('dialog[open]');
    await expect(
      sheet,
      'the sheet did not open, so there is nothing to close',
    ).toHaveCount(1);
    await expect(sheet).toContainText('Which trip should it go in?');
    const landedInside = await page.evaluate(() => {
      const open = [...document.querySelectorAll('dialog')].find((d) => d.open);
      return open?.contains(document.activeElement) ?? false;
    });
    expect(landedInside, 'focus should move into the open sheet').toBe(true);

    // Pick a trip: the close that runs the save and re-renders the trigger.
    await sheet
      .getByRole('button')
      .filter({ hasNotText: /Cancel/ })
      .first()
      .click();
    await expect(sheet, 'the sheet should close once a trip is picked').toHaveCount(0, {
      timeout: 10_000,
    });

    // WAITED OUT, not polled, and the difference is the whole assertion.
    // `expect.poll` succeeds on the FIRST sample that matches, and on this path
    // an early sample does match: measured, focus is on a <button> immediately
    // after the dialog closes and only falls to <body> afterwards, once the
    // save's re-render lands. A poll therefore catches the transient and goes
    // green on a screen that ends up with focus nowhere -- it did exactly that
    // here before this was changed. That is the same coin-flip the itinerary
    // test documents at keyboard-flow.spec.ts:217 (24 samples, 12/12).
    //
    // What the clause is about is where focus COMES TO REST, so the re-render
    // is waited out first and the landing read once, after it.
    await page.waitForTimeout(1_500);
    const landed = await page.evaluate(() => {
      const el = document.activeElement as HTMLElement | null;
      return {
        onBody: el === document.body,
        connected: el?.isConnected ?? false,
        label: (el?.getAttribute('aria-label') ?? el?.textContent ?? '')
          .trim()
          .slice(0, 40),
      };
    });
    expect(
      landed.onBody,
      `focus fell to <body> after the sheet closed: the next Tab restarts at the top of the page (landed on ${landed.label || '<body>'})`,
    ).toBe(false);
    expect(landed.connected, 'focus is on a node that is no longer in the page').toBe(
      true,
    );
  });
});
