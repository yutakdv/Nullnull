import type { Page } from '@playwright/test';

// Shared overflow probe for 360px / 200%-zoom checks.
//
// Extracted from responsive.spec.ts when a second caller appeared (the sheet
// checks in sheet-responsive.spec.ts). Copying it would have been the smaller
// edit and the wrong one: the two callers must agree on what counts as an
// overflow, and a second implementation drifts the moment one side is
// tightened. The same reason validate_frontend_plan.py imports resolve_link
// from validate_backend_plan rather than reimplementing it.
//
// Unchanged in the move - every rule below was already load-bearing, and the
// comments say which measurement each one came from.

/** Elements wider than the viewport, and text clipped by its own box. */
export async function overflow(page: Page) {
  return page.evaluate(() => {
    const spilling: string[] = [];
    const clipped: string[] = [];
    /**
     * Whether some ancestor is a row that deliberately scrolls sideways.
     *
     * Such a row satisfies WCAG 1.4.10: the PAGE still does not scroll, and
     * the user reaches the rest of the row without a second axis of page
     * scrolling. The trip day selector is built that way on purpose
     * (TripScreen.module.css `.dayNav`), so its chips sit past the viewport
     * edge by design. The `clipped` check below already made this
     * distinction; `spilling` did not, so every scrolling row read as an
     * overflow failure once the screens had data to render.
     *
     * A container that only asked for `overflow-y: auto` does NOT count.
     * CSS computes the other axis to `auto` as well, so the scroll container
     * wrapping the whole app (AppShell's `.main`) reports `overflow-x: auto`
     * without anyone writing it — and accepting that would suppress every
     * genuine overflow on every screen, leaving the assertion unable to fail.
     * The marker below is therefore opt-in: a row that means to scroll
     * sideways says so with `data-scrolls-x`.
     */
    const insideScroller = (node: HTMLElement) => {
      for (let p = node.parentElement; p; p = p.parentElement) {
        if (p.dataset.scrollsX !== undefined) return true;
      }
      return false;
    };
    for (const el of document.querySelectorAll('*')) {
      const node = el as HTMLElement;
      const box = node.getBoundingClientRect();
      if (box.right > window.innerWidth + 1 && !insideScroller(node)) {
        spilling.push(
          `<${node.tagName.toLowerCase()}> "${(node.textContent ?? '')
            .trim()
            .slice(0, 24)}" reaches ${Math.round(box.right)}px`,
        );
      }
      // Visually hidden text is clipped on purpose; it is not on screen. The
      // clipping can live on its screen-reader-only wrapper rather than the
      // text leaf itself, so inspect ancestors too. Otherwise a live-region
      // message inside a 1px clipped wrapper is reported as ordinary cut-off
      // text even though it is intentionally absent from the visual layout.
      const hidden = (() => {
        for (
          let current: HTMLElement | null = node;
          current;
          current = current.parentElement
        ) {
          if (getComputedStyle(current).clipPath.startsWith('inset(50%')) return true;
        }
        return false;
      })();
      const scrolls = getComputedStyle(node).overflowX !== 'visible';
      if (
        !hidden &&
        !scrolls &&
        node.children.length === 0 &&
        node.scrollWidth > node.clientWidth + 1
      ) {
        clipped.push(`"${(node.textContent ?? '').trim().slice(0, 24)}" is cut off`);
      }
    }
    // Named so a failure says which element was wide, not just that one was.
    const widest = [...document.querySelectorAll('*')]
      .map((el) => {
        const node = el as HTMLElement;
        return {
          label: `<${node.tagName.toLowerCase()}> "${(node.textContent ?? '').trim().slice(0, 20)}"`,
          width: Math.max(
            node.scrollWidth,
            Math.round(node.getBoundingClientRect().width),
          ),
        };
      })
      // Compared against the document's own client width, not innerWidth:
      // when the page is wider than the viewport innerWidth grows with it, and
      // then nothing measures as too wide even though something is.
      .filter((entry) => entry.width > document.documentElement.clientWidth)
      .sort((a, b) => b.width - a.width)
      .slice(0, 3)
      .map((entry) => `${entry.label} ${String(entry.width)}px`);

    return {
      documentWidth: document.documentElement.scrollWidth,
      viewportWidth: window.innerWidth,
      spilling,
      clipped,
      widest,
    };
  });
}
