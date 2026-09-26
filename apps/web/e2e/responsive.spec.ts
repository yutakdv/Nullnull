import { expect, test, type Page } from '@playwright/test';
import { overflow } from './overflow.js';
import { SCREENS } from './screens.js';

const composedStack = Boolean(
  process.env.PLAYWRIGHT_BASE_URL ?? process.env.WEB_BASE_URL,
);

// The composed stack keeps optimization off, so its optimization route cannot
// prove FE-503-T3. Keep running the general responsive cases there, but attach
// the acceptance ID and READY-content guard only to the local MSW run that can
// render the real proposal and decision bar. Do not skip: the report gate
// rejects any skipped E2E testcase and would discard the whole suite.
function fe503T3AcceptanceId(screenName: string) {
  return !composedStack && screenName === 'optimization run' ? 'FE-503-T3 ' : '';
}

// FE-101 (A-1/A-2/A-3) and FE-105 (S14) own a screen each, so their layout and
// motion clauses ride on the cases that measure THAT screen and on no other.
// On a describe title, as FE-104-T3 is, an id reads as proven by every screen
// in SCREENS, and the aggregator cannot tell which case measured the card's
// own. T3 is keyboard 이동·접근성 이름과 360px·200% zoom; T5 is reduced motion.
const ONBOARDING = new Set(['splash', 'language', 'intro']);

function screenT3AcceptanceId(screenName: string) {
  if (ONBOARDING.has(screenName)) return 'FE-101-T3 ';
  return screenName === 'profile' ? 'FE-105-T3 ' : '';
}

// Not the splash. Under `reduce` it skips its hold and redirects as soon as the
// bootstrap lands, so by `networkidle` the case named "splash" is measuring
// /language (5/5 runs; without `reduce` the splash is still up in all 5 at
// 360px and at 180px, which is why the T3 cases keep it). The splash's reduced
// motion is that skipped hold, and the unit test in onboarding.test.tsx is what
// proves it.
function screenMotionAcceptanceId(screenName: string) {
  if (screenName === 'language' || screenName === 'intro') return 'FE-101-T5 ';
  return screenName === 'profile' ? 'FE-105-T5 ' : '';
}

async function expectOptimizationRunContent(page: Page, screenName: string) {
  if (composedStack || screenName !== 'optimization run') return;

  await expect(
    page.getByRole('heading', {
      level: 1,
      name: /대안을 확인해 주세요|Review the alternatives/,
    }),
  ).toBeVisible({ timeout: 10_000 });
  await expect(
    page.getByRole('group', { name: /최적화 결정|Optimization decision/ }),
  ).toBeVisible();
}

// FE-601: every built P0 screen must survive 360px, 200% zoom and long copy.
//
// These run in a real browser because the failures they look for are layout
// failures: jsdom computes no geometry, so a unit test cannot see an element
// spilling past the viewport or a line of text being clipped.
//
// 200% zoom is modelled as a 180px viewport. That is what doubling the text
// size does to the space available, and it is the condition that found the
// `body { min-width: 360px }` floor -- which forced a horizontal scrollbar at
// exactly the zoom level the accessibility rule requires us to support.

// FE-104-T3 and FE-203-T3 ride along on the three describes below, and only
// on those three. Both clauses were originally written as six things at once
// ("keyboard 이동·focus 복귀·접근성 이름과 360px·200% zoom·reduced motion"),
// and this spec proves three of them for every screen in SCREENS: 360px here,
// 200% zoom below, and the accessible name of whatever takes focus. The other
// remaining clauses are split honestly: FE-104-T4 and FE-203-T5 cover reduced
// motion in the per-screen block below, while FE-203-T4 covers focus return in
// e2e/focus-restore.spec.ts. The paste screen has no dialog or sheet, so it has
// no invented focus-return clause.
//
// The ids are split rather than attached whole because the aggregator only
// checks that an id APPEARS in a testcase name: one id covering six clauses is
// satisfied by a test proving any one of them, and the rest become invisible
// (AGENTS.md registration rule 3).
test.describe('FE-601-T1 FE-104-T3 FE-203-T3 at 360px, the narrowest designed width', () => {
  for (const screen of SCREENS) {
    const acceptanceId =
      fe503T3AcceptanceId(screen.name) + screenT3AcceptanceId(screen.name);
    test(`${acceptanceId}${screen.name} fits`, async ({ page }) => {
      await page.goto(screen.path);
      await page.waitForLoadState('networkidle');
      await expectOptimizationRunContent(page, screen.name);
      const result = await overflow(page);
      expect(result.spilling, `${screen.name} has content past the viewport`).toEqual([]);
      expect(result.clipped, `${screen.name} clips text`).toEqual([]);
      const requested = page.viewportSize()?.width ?? 0;
      expect(result.documentWidth).toBeLessThanOrEqual(requested);
    });
  }
});

test.describe('FE-104-T3 FE-203-T3 at 200% zoom, where the viewport halves', () => {
  test.use({ viewport: { width: 180, height: 500 } });
  for (const screen of SCREENS) {
    const acceptanceId =
      fe503T3AcceptanceId(screen.name) + screenT3AcceptanceId(screen.name);
    test(`${acceptanceId}${screen.name} reflows instead of scrolling sideways`, async ({
      page,
    }) => {
      await page.goto(screen.path);
      await page.waitForLoadState('networkidle');
      await expectOptimizationRunContent(page, screen.name);
      const result = await overflow(page);
      // WCAG 1.4.10: content reflows rather than requiring two-axis scrolling.
      //
      // Compared against the viewport we asked for, not window.innerWidth: a
      // `min-width` on the page widens innerWidth to match, so comparing the
      // document to it would compare a number to itself and always pass. That
      // is exactly how the min-width floor hid from an earlier version here.
      const requested = page.viewportSize()?.width ?? 0;
      expect(
        result.documentWidth,
        // The widest elements are named because this failure depends on the
        // rendering environment: a container without the Figma font falls back
        // to metrics that differ from a developer machine, so "it passed
        // locally" is not evidence and the message has to say what was wide.
        `${screen.name} forces horizontal scrolling at 200% zoom: document is ` +
          `${String(result.documentWidth)}px in a ${String(requested)}px viewport. ` +
          `Widest: ${result.widest.join(' | ') || 'none measured'}`,
      ).toBeLessThanOrEqual(requested);
      expect(result.spilling).toEqual([]);
      expect(result.clipped).toEqual([]);
    });
  }
});

test.describe('FE-601-T2 with English copy, which runs longer than the Korean', () => {
  test.use({ locale: 'en-US' });
  for (const screen of SCREENS) {
    test(`${screen.name} holds the longer strings`, async ({ page }) => {
      await page.goto(screen.path);
      await page.waitForLoadState('networkidle');
      await expect(page.locator('html')).toHaveAttribute('lang', 'en-US');
      const result = await overflow(page);
      expect(result.spilling).toEqual([]);
      expect(result.clipped).toEqual([]);
    });
  }
});

// Several cards share one clause here: FE-001-T2, FE-002-T2, FE-003-T2 and
// FE-004-T2 are word-for-word "keyboard 이동·focus 복귀·접근성 이름과
// 360px·200% zoom·reduced motion을 검증한다", and this block runs exactly that
// over every screen in SCREENS. One test proving a clause of several cards is
// normal (AGENTS.md registration rule 2); what is not allowed is a clause with
// no name the aggregator can read, which is what these four had.
//
// The scaffold cards own no screen of their own - FE-001 is the router shell,
// FE-002 the tokens, FE-003 the error mapper, FE-004 the offline shell - so
// their keyboard-and-reflow clause can only be shown across the whole set.
// FE-104-T3 / FE-203-T3 are here for the accessible-name half only: the
// per-screen test below walks eight Tab presses and requires that whatever
// takes focus is on screen, has a name, and shows a ring. That is the
// "접근성 이름" clause.
//
// They are NOT for reduced motion, which is why `honours prefers-reduced-motion`
// now lives in its own describe below rather than in this one: it visits
// /language alone and says nothing about the paste screen or the trip picker.
// The reduced-motion clause is FE-104-T4 / FE-203-T5, and it is proven by the
// `reduced motion, per screen` describe at the bottom of this file — that one
// carries the T4 ids because it walks SCREENS, so the paste screen and the
// saved-places screen are each measured rather than stood in for.
test.describe('FE-601-T3 FE-602-T2 FE-001-T2 FE-002-T2 FE-003-T2 FE-004-T2 FE-104-T3 FE-203-T3 keyboard and motion', () => {
  for (const screen of SCREENS) {
    // Not the splash: on success it renders no control at all, so its walk
    // finds nothing to judge and would carry FE-101-T3 without measuring it.
    const acceptanceId =
      fe503T3AcceptanceId(screen.name) +
      (screen.name === 'splash' ? '' : screenT3AcceptanceId(screen.name));
    test(`${acceptanceId}BA-070-T5 ${screen.name} puts focus on something visible`, async ({
      page,
    }) => {
      await page.goto(screen.path);
      await page.waitForLoadState('networkidle');
      await expectOptimizationRunContent(page, screen.name);

      // EIGHT presses, not one. One press only ever measured each screen's
      // first stop, and the defect this exists to catch was on the SECOND:
      // SearchField set `outline: none` with nothing put back, so the add-place
      // search box took focus while showing no ring at all, and this test was
      // green the whole time (measured, #279).
      //
      // Eight rather than "until it wraps": every screen in SCREENS reaches its
      // own wrap point within eight, and a fixed bound cannot hang on a screen
      // whose order never repeats.
      //
      // Landing on <body> is NOT a failure. Measured on normal code: nine of
      // these screens hand focus back to the document between cycles, and
      // splash has no interactive content at all, so requiring an element on
      // every press would reject correct code rather than find a defect. Each
      // press is judged only when something took focus.
      const stops: Array<{
        tag: string;
        name: string;
        onScreen: boolean;
        hasIndicator: boolean;
      }> = [];
      for (let press = 0; press < 8; press += 1) {
        await page.keyboard.press('Tab');
        const stop = await page.evaluate(() => {
          const el = document.activeElement as HTMLElement | null;
          if (!el || el === document.body) return null;
          const box = el.getBoundingClientRect();
          return {
            tag: el.tagName.toLowerCase(),
            // An <input> has no textContent, and its name usually comes from the
            // <label> around it or from aria-labelledby. Reading only aria-label
            // and textContent reported "no name" for a correctly labelled field
            // — which flagged the product for a gap in this check. The order
            // below follows the accessible-name computation as far as it matters
            // here: aria-label, then aria-labelledby, then the associated label,
            // then the element's own text.
            name: (() => {
              const aria = el.getAttribute('aria-label');
              if (aria?.trim()) return aria.trim().slice(0, 40);
              const labelledBy = el.getAttribute('aria-labelledby');
              if (labelledBy) {
                const text = labelledBy
                  .split(/\s+/)
                  .map((id) => document.getElementById(id)?.textContent ?? '')
                  .join(' ')
                  .trim();
                if (text) return text.slice(0, 40);
              }
              const labels = (el as HTMLInputElement).labels;
              if (labels?.length) {
                const text = Array.from(labels)
                  .map((l) => l.textContent ?? '')
                  .join(' ')
                  .trim();
                if (text) return text.slice(0, 40);
              }
              return (el.textContent ?? '').trim().slice(0, 40);
            })(),
            onScreen:
              box.width > 0 && box.height > 0 && box.right <= window.innerWidth + 1,
            // A focus ring the browser removed with nothing put back is a trap
            // for keyboard users even though the element is technically focused.
            //
            // What counts is a style that CHANGES when focus arrives, not any
            // outline or shadow present on the node. Ancestors have to be
            // considered, because the ring does not have to sit on the focused
            // element: SearchField draws it on the 48px pill with
            // `:focus-within`, since an outline on the transparent <input>
            // inside would trace the text box rather than the control the user
            // sees. But accepting any ancestor shadow is how the first version
            // of this check passed on the very defect it was written for — the
            // pill carries a decorative `--elevation-subtle` shadow at rest, so
            // "the label has a box-shadow" was true with the focus ring deleted
            // (measured: the mutation was live and all 16 screens stayed green).
            //
            // So each candidate is compared against its own resting style,
            // captured while focus is elsewhere. Bounded at four levels up so
            // this stays a local check.
            hasIndicator: (() => {
              const focusStyles: string[] = [];
              const nodes: HTMLElement[] = [];
              let node: HTMLElement | null = el;
              for (let up = 0; node && up < 4; up += 1) {
                const s = getComputedStyle(node);
                nodes.push(node);
                focusStyles.push(`${s.outlineStyle}|${s.outlineWidth}|${s.boxShadow}`);
                node = node.parentElement;
              }
              // Move focus away and re-read the same nodes. `blur()` is enough:
              // it drops :focus and :focus-within without scrolling the page or
              // disturbing the tab order the caller is walking.
              el.blur();
              const restStyles = nodes.map((n) => {
                const s = getComputedStyle(n);
                return `${s.outlineStyle}|${s.outlineWidth}|${s.boxShadow}`;
              });
              // Put focus back so the next Tab continues from here.
              el.focus();
              return focusStyles.some((f, i) => f !== restStyles[i]);
            })(),
          };
        });
        if (stop) stops.push(stop);
      }

      // A screen with no interactive content is allowed to have nothing to
      // focus; one that does must show where focus went, and name it.
      for (const [i, stop] of stops.entries()) {
        expect(stop.onScreen, `${screen.name}: focus is off-screen (stop ${i})`).toBe(
          true,
        );
        expect(
          stop.name,
          `${screen.name}: focused <${stop.tag}> has no name (stop ${i})`,
        ).not.toBe('');
        expect(
          stop.hasIndicator,
          `${screen.name}: focused <${stop.tag}> "${stop.name}" shows no focus ring (stop ${i})`,
        ).toBe(true);
      }
    });
  }
});

// Its own describe, and deliberately so. A testcase's JUnit name is its
// describe title plus its own, so while this test lived in the block above it
// spelled every id in that title — including FE-104-T3 and FE-203-T3, which it
// does not earn: it emulates `reduce` on /language alone and never visits the
// paste screen or opens the trip picker. Measured after attaching those ids:
// the JUnit name came out `… FE-104-T3 FE-203-T3 keyboard and motion › honours
// prefers-reduced-motion`, and check_test_reports.py matches on the name, so a
// comment saying "not this one" changes nothing. Splitting the block is what
// actually keeps the claim off it.
//
// It keeps the FE-601/FE-602/FE-00x ids because those cards ask for
// reduced-motion as a property of the app, which one screen can show; FE-104
// and FE-203 ask for it on THEIR screen, which this does not measure.
test.describe('FE-601-T3 FE-602-T2 FE-001-T2 FE-002-T2 FE-003-T2 FE-004-T2 motion', () => {
  test('honours prefers-reduced-motion', async ({ page }) => {
    await page.emulateMedia({ reducedMotion: 'reduce' });
    await page.goto('/language');
    await page.waitForLoadState('networkidle');
    const durations = await page.evaluate(() =>
      [...document.querySelectorAll('*')]
        .map((el) => getComputedStyle(el as HTMLElement))
        .filter((s) => s.animationDuration !== '0s' || s.transitionDuration !== '0s')
        .map((s) => `${s.animationDuration}/${s.transitionDuration}`),
    );
    // The zero-count guard, added after measuring that this test passed with
    // the `prefers-reduced-motion` block deleted from styles.css. That rule is
    // what puts elements into `durations` at all — it sets every element to
    // 0.01ms with `!important`, and the app declares almost no motion at rest —
    // so removing it empties the list, skips the loop, and leaves this green on
    // the one defect it exists to catch. An empty list is now the failure.
    expect(
      durations.length,
      'no element reported a duration, so the collapse rule was never measured',
    ).toBeGreaterThan(0);
    // styles.css collapses both to 0.01ms under reduce; nothing should run longer.
    for (const pair of durations) {
      expect(pair).not.toMatch(/(?:^|\/)(?:[1-9]\d*|0\.[1-9])s/);
    }
  });
});

// FE-104-T4 / FE-203-T5 / FE-503-T4: reduced motion on EVERY screen, not just
// one. FE-503-T4 is attached only to the optimization-run case below.
//
// The describe above measures reduced motion as a property of the app, which
// one screen can show. FE-104 (paste import) and FE-203 (saved places) ask for
// it on THEIR screen, and a card's clause is only proven on the screen it
// names, so this walks all of SCREENS.
//
// T3 keeps keyboard 이동, 접근성 이름, 360px and 200% zoom. FE-104-T4 owns
// paste-screen motion; FE-203-T4 owns the sheet focus restore; FE-203-T5 owns
// the saved-place screen's reduced motion.
//
// FE-503's READY preview is now implemented and SCREENS uses MOCK_RUN_ID, so
// the local MSW run reaches the real proposal and decision bar. The screen has
// no dialog or sheet, making the focus-return half of T4 inapplicable; this
// case proves its remaining reduced-motion half. The former FE-505 applied
// panel is no longer mounted on trip detail by product decision, so its route
// boundary is covered by applied-panel.spec.ts instead.
//
// It does NOT reuse the assertion above, because that assertion cannot fail.
// Measured: delete the `prefers-reduced-motion` block from styles.css and
// `honours prefers-reduced-motion` still passes. The reason is the filter —
// it keeps only elements whose duration is not '0s', and this app declares
// almost no motion at rest (one `pulse`, in OptimizationRunScreen.module.css).
// So the ONLY thing that puts elements into that list is the collapse rule
// itself, which sets every element to 0.01ms with `!important`. Remove the
// rule and the list is empty, the loop body never runs, and the test is green
// on the exact defect it names. The rule was manufacturing its own targets.
//
// So this measures the collapse against a target the page is made to declare:
// a node with an explicit 600ms transition and a 900ms animation, appended to
// the live document so it inherits the same cascade and media state as the
// screen around it. Under `reduce` the app's rule must flatten it. That target
// exists on every screen regardless of what the screen rendered, which is what
// makes the per-screen claim honest — and it is why the check does not depend
// on a screen reaching its real content.
//
test.describe('FE-104-T4 FE-203-T5 reduced motion, per screen', () => {
  for (const screen of SCREENS) {
    const acceptanceId =
      (screen.name === 'optimization run' ? 'FE-503-T4 ' : '') +
      screenMotionAcceptanceId(screen.name);
    test(`${acceptanceId}${screen.name} collapses motion under reduce`, async ({
      page,
    }) => {
      await page.emulateMedia({ reducedMotion: 'reduce' });
      await page.goto(screen.path);
      await page.waitForLoadState('networkidle');

      if (screen.name === 'optimization run' && !composedStack) {
        // The local MSW state machine reaches READY on its third read. Guard
        // the real FE-503 content before crediting its ID: otherwise this case
        // could pass on the route's not-found screen while measuring only the
        // global stylesheet. The composed gate keeps the optimization
        // capability off, so there the probe below is the honest boundary.
        await expect(
          page.getByRole('heading', {
            level: 1,
            name: /대안을 확인해 주세요|Review the alternatives/,
          }),
        ).toBeVisible({ timeout: 10_000 });
        await expect(
          page.getByRole('group', { name: /최적화 결정|Optimization decision/ }),
        ).toBeVisible();
        await expect(page.locator('dialog')).toHaveCount(0);
      }

      const measured = await page.evaluate(() => {
        // The probe declares motion the reduce rule has to override. Inline
        // styles are used so the declaration cannot be lost to a selector that
        // happens not to match on this screen; `!important` in styles.css
        // outranks an inline declaration, which is precisely what is measured.
        const probe = document.createElement('div');
        probe.setAttribute('data-nn-motion-probe', '');
        probe.style.transitionProperty = 'opacity';
        probe.style.transitionDuration = '600ms';
        probe.style.animationName = 'nn-motion-probe';
        probe.style.animationDuration = '900ms';
        document.body.appendChild(probe);
        const probed = getComputedStyle(probe);
        const probeResult = {
          transitionDuration: probed.transitionDuration,
          animationDuration: probed.animationDuration,
        };
        probe.remove();

        // Anything the screen itself declares is measured too, so a future
        // animation added to a real component is covered without editing this.
        const own = [...document.querySelectorAll('*')]
          .map((el) => getComputedStyle(el as HTMLElement))
          .filter((s) => s.animationDuration !== '0s' || s.transitionDuration !== '0s')
          .map((s) => `${s.animationDuration}/${s.transitionDuration}`);

        return { probeResult, own };
      });

      // The zero-count guard. Not "did the screen have animations" — it had
      // none, and requiring some would reject correct code — but "did the
      // thing this test measures actually get measured". If the probe never
      // landed, every assertion below is a statement about nothing.
      // attribution-coverage.test.ts ('has targets to measure at all') is the
      // precedent: 100% of nothing is the shape of a compliance claim that
      // passes while the rule goes unchecked.
      expect(
        measured.probeResult.transitionDuration,
        `${screen.name}: the motion probe did not render, so nothing was measured`,
      ).not.toBe('');
      expect(
        measured.probeResult.animationDuration,
        `${screen.name}: the motion probe did not render, so nothing was measured`,
      ).not.toBe('');

      // A duration is acceptable only if it is instant. styles.css collapses to
      // 0.01ms, which computes as `1e-05s`; a plain `0s` would be fine too. The
      // 600ms and 900ms the probe asked for must not survive.
      const instant = /^(?:0s|1e-05s|0\.00001s)$/;
      expect(
        measured.probeResult.transitionDuration,
        `${screen.name}: a 600ms transition survived prefers-reduced-motion`,
      ).toMatch(instant);
      expect(
        measured.probeResult.animationDuration,
        `${screen.name}: a 900ms animation survived prefers-reduced-motion`,
      ).toMatch(instant);

      // And nothing the screen declares on its own may run either.
      for (const pair of measured.own) {
        expect(
          pair,
          `${screen.name}: an element runs ${pair} under prefers-reduced-motion`,
        ).not.toMatch(/(?:^|\/)(?:[1-9]\d*|0\.[1-9])s/);
      }
    });
  }
});

test.describe('touch targets', () => {
  // TEST_STRATEGY.md §3: 44px minimum, and enough space between neighbours.
  // Enforced in a browser because the size comes from computed layout, not
  // from any single declaration a lint rule could read.
  const MIN = 44;

  for (const screen of SCREENS) {
    test(`${screen.name} keeps every control tappable`, async ({ page }) => {
      await page.goto(screen.path);
      await page.waitForLoadState('networkidle');

      const undersized = await page.evaluate((min) => {
        const found: string[] = [];
        const controls = 'a, button, [role="button"], input, select, textarea';
        for (const el of document.querySelectorAll(controls)) {
          const node = el as HTMLElement;
          // Disabled controls are not tap targets; `준비 중` rows are inert by
          // design and must not be dragged up to 44px to satisfy a rule.
          if ((node as HTMLButtonElement).disabled) continue;
          // A link inside a run of text is measured by the line it sits on,
          // not by a box of its own, and WCAG 2.5.5/2.5.8 exempt it for that
          // reason. The KTO credit is exactly this: required caption text
          // (CMP-ATT-001) whose provider link wraps with the sentence. Giving
          // it a 44px box would either inflate the caption or detach the link
          // from the words around it. `display: inline` is the test: a control
          // laid out as a block or flex item is a tap target and is measured.
          if (getComputedStyle(node).display === 'inline') continue;
          // A checkbox or radio wrapped in its <label> is tapped by the label:
          // every point in it toggles the control, so the label IS the target
          // and WCAG measures the region that activates it. Inflating the box
          // itself to 44px would draw a checkbox the size of a button. The
          // label is measured in its place — and only when it genuinely wraps
          // the input, so a detached <label for> still fails here.
          const kind = (node as HTMLInputElement).type;
          const wrapper =
            kind === 'checkbox' || kind === 'radio' ? node.closest('label') : null;
          const box = (wrapper ?? node).getBoundingClientRect();
          if (box.width === 0 || box.height === 0) continue;
          if (box.height < min || box.width < min) {
            found.push(
              `<${node.tagName.toLowerCase()}> ${Math.round(box.width)}×${Math.round(box.height)} ` +
                `"${(node.textContent ?? '').trim().slice(0, 24)}"`,
            );
          }
        }
        return found;
      }, MIN);

      expect(
        undersized,
        `${screen.name} has controls below ${String(MIN)}px: ${undersized.join(' | ')}`,
      ).toEqual([]);
    });
  }
});

test.describe('the app shell fits the screen', () => {
  // The tab bar drifted off a phone screen because `main` carried
  // `min-height: 100dvh`: the content alone was a full viewport tall, so the
  // shell came to twice that and the bar sat ~750px below the fold. Desktop
  // Chrome hid it — `position: sticky` still clamped the bar into view — which
  // is why this measures the document rather than the bar's own rectangle.
  for (const path of [
    '/profile',
    '/trip/018f4a10-2c31-7d42-9a55-6b1f0c3e8a01',
    '/live',
    '/feed',
  ]) {
    test(`${path} does not scroll the page itself`, async ({ page }) => {
      await page.goto(path);
      await page.waitForLoadState('networkidle');
      const { docHeight, viewportHeight } = await page.evaluate(() => ({
        docHeight: document.documentElement.scrollHeight,
        viewportHeight: window.innerHeight,
      }));
      // The shell owns the viewport; scrolling belongs to the region inside it.
      // A document taller than the screen means the bar is below the fold.
      expect(docHeight).toBeLessThanOrEqual(viewportHeight + 1);
    });
  }

  test('the tab bar sits on the bottom edge and stays there', async ({ page }) => {
    await page.goto('/profile');
    await page.waitForLoadState('networkidle');
    const bar = page.locator('nav').last();
    const read = async () => {
      const box = await bar.boundingBox();
      const height = await page.evaluate(() => window.innerHeight);
      return { bottom: Math.round(box?.y ?? 0) + Math.round(box?.height ?? 0), height };
    };
    const before = await read();
    expect(before.bottom).toBeLessThanOrEqual(before.height + 1);

    await page.evaluate(() => {
      const main = document.querySelector('main');
      if (main) main.scrollTop = main.scrollHeight;
    });
    const after = await read();
    expect(after.bottom).toBe(before.bottom);
  });
});

test.describe('post detail keeps its primary action reachable', () => {
  for (const [label, width] of [
    ['360px phone', 360],
    ['200% zoom', 180],
  ] as const) {
    test(`the save action stays inside the ${label} viewport while the post scrolls`, async ({
      page,
    }) => {
      await page.setViewportSize({ width, height: 800 });
      await page.goto('/posts/018f5b00-0000-7000-8000-000000000001');
      await page.waitForLoadState('networkidle');

      const save = page.getByRole('button', { name: /이 글 저장|Save this post/ });
      await expect(save).toBeVisible();
      await expect(save).toBeInViewport();

      await page.evaluate(() => {
        const main = document.querySelector('main');
        if (main) main.scrollTop = main.scrollHeight;
      });

      await expect(save).toBeInViewport();
      const bounds = await save.boundingBox();
      const viewport = page.viewportSize();
      expect(bounds).not.toBeNull();
      expect(viewport).not.toBeNull();
      expect((bounds?.x ?? 0) + (bounds?.width ?? 0)).toBeLessThanOrEqual(
        viewport?.width ?? 0,
      );
      expect((bounds?.y ?? 0) + (bounds?.height ?? 0)).toBeLessThanOrEqual(
        viewport?.height ?? 0,
      );
    });
  }
});
