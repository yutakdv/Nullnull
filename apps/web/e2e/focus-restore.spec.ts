import { expect, test, type Page } from '@playwright/test';
import { createSeededTrip } from './seeded-trip.js';

// 서울숲, from scripts/e2e/catalog-seed.sql. Deliberately NOT one of the two
// places createSeededTrip schedules: a candidate whose place is already on the
// itinerary renders as scheduled, and CandidatesScreen.tsx:342 draws no
// "Add to a day" button for one — the trigger this spec needs would be absent
// for the same reason the gate failure had no trigger at all.
const SEOUL_FOREST = '018f4b20-1a44-7e11-9c02-5d7e3f1a2b04';

/**
 * Saves one unscheduled candidate onto `tripPath`'s trip, the way the product does.
 *
 * createSeededTrip builds an itinerary through `seedItems`; it has no candidate
 * path and is not given one here, because two other specs already depend on it
 * and widening it would move what they measure. `addTripCandidate` is a
 * separate operation (openapi.yaml:3043) and a candidate carries no date, so
 * this cannot disturb the schedule the helper just created —
 * `tripScheduleChanged` is const false on both its answers.
 */
async function saveCandidate(page: Page, tripId: string): Promise<void> {
  const result = await page.evaluate(
    async ({ tripId, placeId }) => {
      const csrf = await fetch('/api/v1/session/csrf', {
        method: 'POST',
        credentials: 'same-origin',
      });
      if (!csrf.ok) return { status: csrf.status, step: 'csrf', body: await csrf.text() };
      const { csrfToken } = (await csrf.json()) as { csrfToken: string };
      const saved = await fetch(`/api/v1/trips/${tripId}/candidates`, {
        method: 'POST',
        credentials: 'same-origin',
        headers: {
          'Content-Type': 'application/json',
          'X-CSRF-Token': csrfToken,
          'Idempotency-Key': crypto.randomUUID(),
        },
        // SEARCH rather than POST: a POST source needs a postId, and this
        // candidate comes from no post.
        body: JSON.stringify({ placeId, source: { type: 'SEARCH' } }),
      });
      // 200 is "the same active candidate already existed", which is as good as
      // 201 for a test that only needs one to exist.
      if (saved.status !== 201 && saved.status !== 200) {
        return { status: saved.status, step: 'addCandidate', body: await saved.text() };
      }
      return { status: saved.status, step: 'addCandidate', body: '' };
    },
    { tripId, placeId: SEOUL_FOREST },
  );

  // Thrown rather than asserted so the failure names the request that did not
  // happen. A silent 4xx here would surface later as "no saved candidate offers
  // a day to add it to", which is the message the gate actually printed — it
  // describes the symptom, and this describes the cause.
  if (result.status !== 201 && result.status !== 200) {
    throw new Error(
      `could not save the candidate: ${result.step} answered ${String(result.status)} ${result.body}`,
    );
  }
}

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

test.describe('closing the saved-places sheet leaves focus somewhere usable', () => {
  // FIXME(gate data): this cannot pass in docker-integration until that
  // environment has verified opening hours for the candidate's place. It is
  // `fixme` rather than deleted or quietly narrowed because the clause is real
  // and unproven, and an empty gap is the honest record of that.
  //
  // WHAT BLOCKS IT, as a chain rather than a guess. scripts/e2e/catalog-seed.sql
  // writes places but no `place_hours` row (grep: zero). CandidateMatchService
  // .openingHours() therefore hands the evaluator an empty map, and
  // apps/ai/src/nullnull_ai/item/filters.py:143 answers an absent window with
  // `Eligibility.unknown(OPENING_HOURS_UNKNOWN)`. slot/evaluator.py:111-112
  // then emits a Slot for EVERY day regardless — `Slot(day, None, False,
  // reason)` when the verdict is not eligible — so the sheet does draw a row
  // per day, and ScheduleCandidateSheet.tsx:156 sets `disabled` on each one.
  //
  // So the button is PRESENT AND DISABLED, not missing. That distinction is why
  // the gate log reads `locator.click: Test timeout … waiting for locator(…)`
  // rather than a "not found": getByRole matches a disabled button, the locator
  // resolves, and `.click()` then waits for an actionability that never comes.
  // Read as "the button is absent" it sends the next reader hunting the sheet's
  // markup, which is the wrong file.
  //
  // WHY NOT FIXED IN THIS SPEC. Picking whichever day is enabled, or skipping
  // when none is, makes this green without scheduling anything — and the clause
  // is that a COMPLETED schedule moves focus to the row. The completion is the
  // subject: CandidatesScreen.tsx:342 stops rendering the trigger once the
  // candidate is scheduled, which is what makes `afterScheduleRef` the only
  // thing that can restore focus. No schedule, nothing measured.
  //
  // WHY NOT SEEDED. `place_hours_observations` (V025) requires `evidence_url`,
  // which its own comment defines as the page a reviewer actually read, and
  // guards the windows table with a trigger besides. Seeding it here would
  // manufacture review evidence for a place nobody reviewed, in the one table
  // whose purpose is that values carry provenance. Its production path is
  // BA-025's curated import (CuratedHoursImportIT), which is where such rows
  // belong.
  //
  // WHAT OPENS IT: verified opening hours existing for this place in the gate
  // environment. Delete this `fixme` that day — the body below needs no other
  // change, because everything up to the click already worked in the gate (the
  // sheet opened, which is what the previous failure could not reach).
  //
  // LOCALLY THIS PASSES, which is the trap. MSW answers the match request with
  // one eligible slot (measured: `Day 3`, `disabled=false`), so the click
  // succeeds and the whole test is green on a dev server. A green run here is
  // not evidence about the gate, in either direction.
  test.fixme(
    'a completed schedule moves focus to the row, not the document',
    async ({ page }) => {
      // A trip of this session's own, then a candidate saved onto it.
      //
      // The hardcoded /trip/018f4a10-… this used to open is the MSW FIXTURE's id.
      // It works against the dev server, where the mock worker answers for it,
      // and is nobody's trip against the real API the docker gate runs: every run
      // starts a fresh anonymous session and a trip belongs to the session that
      // created it, so the answer is 404 by design (invariant 11, BA-070-T1).
      // The screen then had no saved candidate, no "Add to a day" button, and
      // this spec died on the precondition below rather than on its assertion —
      // seeded-trip.ts:3-14 records the same failure from #253, which is where
      // createSeededTrip came from. This spec did not use it.
      const tripPath = await createSeededTrip(page);
      const tripId = tripPath.replace('/trip/', '');
      await saveCandidate(page, tripId);

      await page.goto(`${tripPath}/candidates`);
      await page.waitForLoadState('networkidle');

      // The seeding landed, asserted before the trigger is looked for. Without
      // this the next expectation still fails when the save 4xx'd, but it fails
      // saying "no saved candidate offers a day" — which reads as a product
      // defect on the screen rather than a setup that never ran. The two are
      // different repairs, and the gate failure this spec is fixing was misread
      // that way once already.
      // The row's own heading, not `getByText`: the name also appears in a
      // context line elsewhere on the card, and matching both is a strict-mode
      // violation that fails as though the candidate were missing. Measured —
      // the first version of this guard did exactly that while the seeding had
      // in fact worked.
      await expect(
        page.getByRole('heading', { name: '서울숲' }),
        'the seeded candidate is not on the screen, so the setup did not take',
      ).toBeVisible();

      const trigger = page.locator('button[aria-expanded]').first();
      await expect(
        trigger,
        'no saved candidate offers a day to add it to: the sheet under test never opens',
      ).toBeVisible();
      await trigger.focus();
      await page.keyboard.press('Enter');

      // THE SHEET REALLY OPENED, asserted before anything about focus. "Focus is
      // on the trigger" is trivially true of a sheet that never opened, so
      // without this line the test below passes on a screen where nothing
      // happens at all.
      const sheet = page.locator('dialog[open]');
      await expect(
        sheet,
        'the sheet did not open, so there is nothing to close',
      ).toHaveCount(1);
      await expect(sheet).toContainText('Which day should it go on?');
      // ...and it took focus with it. A modal that opens without moving focus
      // leaves a keyboard user tabbing the page underneath.
      const landedInside = await page.evaluate(() => {
        const open = [...document.querySelectorAll('dialog')].find((d) => d.open);
        return open?.contains(document.activeElement) ?? false;
      });
      expect(landedInside, 'focus should move into the open sheet').toBe(true);

      // Complete the schedule: this is the close that unmounts the trigger.
      await sheet
        .getByRole('button', { name: /Day \d/ })
        .first()
        .click();
      await expect(sheet, 'the sheet should close once the day is picked').toHaveCount(
        0,
        {
          timeout: 10_000,
        },
      );

      // The row re-renders without its "Add to a day" button, and the restore is
      // queued behind that render (a setTimeout at CandidatesScreen.tsx:235), so
      // the landing place is read after it, not during it.
      await expect
        .poll(
          () =>
            page.evaluate(() => {
              const el = document.activeElement as HTMLElement | null;
              return {
                onBody: el === document.body,
                connected: el?.isConnected ?? false,
                name: (el?.getAttribute('aria-label') ?? el?.textContent ?? '').trim(),
              };
            }),
          {
            message:
              'focus fell to <body> after the sheet closed: the next Tab restarts at the top of the page',
            timeout: 5_000,
          },
        )
        .toMatchObject({ onBody: false, connected: true });

      // Not merely "off <body>": on the row the traveller was just acting on.
      // Without this a focus parked on any surviving node would pass, including
      // one in a different card.
      const name = await page.evaluate(() =>
        (
          (document.activeElement as HTMLElement | null)?.getAttribute('aria-label') ??
          document.activeElement?.textContent ??
          ''
        ).trim(),
      );
      expect(
        name,
        'focus should land on a control of the row that was scheduled',
      ).toMatch(/Remove .+ from saved/);
    },
  );
});

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
// So this test is expected to FAIL until the restore verifies where focus
// actually landed. It is written now, with the diagnosis above, so the fix has
// something that can tell it worked -- and so the clause is not recorded as
// satisfied by a test that cannot fail.
test.describe('FE-203-T4 closing the 담기 sheet leaves focus somewhere usable', () => {
  test('FE-203-T4 picking a trip leaves focus on a control, not the document', async ({
    page,
  }) => {
    // The 담기 sheet asks WHICH trip, so it needs at least one to offer. On the
    // dev server MSW supplies one; against the real API a fresh anonymous
    // session owns none, and TripPicker had nothing to list — which is why the
    // gate failed here on the precondition rather than on the focus assertion.
    // createSeededTrip also settles the session bootstrap, so the goto below
    // does not race it.
    await createSeededTrip(page);

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
