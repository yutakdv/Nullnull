import { expect, test } from '@playwright/test';
import { overflow } from './overflow.js';
import { SCREENS } from './screens.js';

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

test.describe('FE-601-T1 at 360px, the narrowest designed width', () => {
  for (const screen of SCREENS) {
    test(`${screen.name} fits`, async ({ page }) => {
      await page.goto(screen.path);
      await page.waitForLoadState('networkidle');
      const result = await overflow(page);
      expect(result.spilling, `${screen.name} has content past the viewport`).toEqual([]);
      expect(result.clipped, `${screen.name} clips text`).toEqual([]);
      const requested = page.viewportSize()?.width ?? 0;
      expect(result.documentWidth).toBeLessThanOrEqual(requested);
    });
  }
});

test.describe('at 200% zoom, where the viewport halves', () => {
  test.use({ viewport: { width: 180, height: 500 } });
  for (const screen of SCREENS) {
    test(`${screen.name} reflows instead of scrolling sideways`, async ({ page }) => {
      await page.goto(screen.path);
      await page.waitForLoadState('networkidle');
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
test.describe('FE-601-T3 FE-602-T2 FE-001-T2 FE-002-T2 FE-003-T2 FE-004-T2 keyboard and motion', () => {
  for (const screen of SCREENS) {
    test(`${screen.name} puts focus on something visible`, async ({ page }) => {
      await page.goto(screen.path);
      await page.waitForLoadState('networkidle');
      await page.keyboard.press('Tab');

      const focused = await page.evaluate(() => {
        const el = document.activeElement as HTMLElement | null;
        if (!el || el === document.body) return null;
        const box = el.getBoundingClientRect();
        const style = getComputedStyle(el);
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
          onScreen: box.width > 0 && box.height > 0 && box.right <= window.innerWidth + 1,
          // A focus ring the browser removed with nothing put back is a trap
          // for keyboard users even though the element is technically focused.
          hasIndicator: style.outlineStyle !== 'none' || style.boxShadow !== 'none',
        };
      });

      // A screen with no interactive content is allowed to have nothing to
      // focus; one that does must show where focus went, and name it.
      if (focused) {
        expect(focused.onScreen, `${screen.name}: focus is off-screen`).toBe(true);
        expect(focused.name, `${screen.name}: focused element has no name`).not.toBe('');
      }
    });
  }

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
    // styles.css collapses both to 0.01ms under reduce; nothing should run longer.
    for (const pair of durations) {
      expect(pair).not.toMatch(/(?:^|\/)(?:[1-9]\d*|0\.[1-9])s/);
    }
  });
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
