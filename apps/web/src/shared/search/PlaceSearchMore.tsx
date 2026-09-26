import { useEffect, useRef, useState, type RefObject } from 'react';
import type { UseInfiniteQueryResult } from '@tanstack/react-query';
import { useI18n } from '../../i18n/I18nProvider.js';
import type { PlaceSearchResults, Problem } from '../api/index.js';
import { restoreFocusTo } from '../ui/components/focus-restore.js';
import styles from './PlaceSearchMore.module.css';

// The continuation under a place-search result list (#54 §4, FE-103-T5..T14).
//
// A button rather than the feed's scroll trigger. The feed loads as the anchor
// nears the viewport; here the results sit above the screen's own controls
// (the kept list, the day chips, the CTA), and loading on scroll would keep
// pushing those away from someone scrolling down to reach them. A press also
// gives a keyboard user a place to be while the page loads.
//
// Every search box shares it, so the three states read the same everywhere:
//
//   - loading: the press is taken and the button says so. It stays enabled,
//     only marked busy — `disabled` would drop the focus it holds (#272 ④),
//     and fetchNextPage cancels and re-sends a page already in flight by
//     default, so a second press is ignored here instead.
//   - failed: the results already received stay; the button becomes the retry
//     and the reason is announced beside it.
//   - done: focus moves to the first new result, which is where the next Tab
//     from the old last result would have gone. Leaving it on the button
//     would put the new results behind it; and on the last page the button
//     unmounts, dropping focus on the document.

export interface PlaceSearchMoreProps {
  search: UseInfiniteQueryResult<PlaceSearchResults, Problem | Error>;
  /** The list the results render into, one child element per result, in order. */
  list: RefObject<HTMLElement | null>;
}

/** Focus the result at `index` itself: a card is the unit a reader moves between. */
function focusResult(list: HTMLElement | null, index: number): HTMLElement | null {
  const item = list?.children.item(index);
  if (!(item instanceof HTMLElement)) return null;
  if (!item.hasAttribute('tabindex')) {
    // Focusable for this one move only, so the card does not become an extra
    // Tab stop. Removed on blur rather than at once, for the reason
    // restoreFocusTo gives about <main>.
    item.setAttribute('tabindex', '-1');
    item.addEventListener('blur', () => item.removeAttribute('tabindex'), { once: true });
  }
  return item;
}

export function PlaceSearchMore({ search, list }: PlaceSearchMoreProps) {
  const { t } = useI18n();
  const button = useRef<HTMLButtonElement>(null);
  // A completed press waiting for its results to render. `first` is the first
  // page as it was pressed: when the query changes underneath, that page is
  // gone, and focus must not jump into a different search's results.
  const [landing, setLanding] = useState<{
    from: number;
    first: PlaceSearchResults['pages'][number];
  } | null>(null);
  const data = search.data;

  useEffect(() => {
    if (landing === null) return;
    if (data?.pages[0] !== landing.first) {
      setLanding(null);
      return;
    }
    // fetchNextPage settles before React renders the page it fetched: the
    // query notifies its observers on a later tick. Measured — at the first
    // render after the press the list still held the old count, and focusing
    // then landed on the old last result.
    if (data.items.length <= landing.from) return;
    setLanding(null);
    const added = focusResult(list.current, landing.from);
    restoreFocusTo(added ?? button.current);
    // focus() scrolls only as far as the nearest edge, which on MustVisit left
    // the card mostly under the fixed 이대로 채우기 bar (390px, seen in a
    // screenshot). Centred, it clears any bar a screen pins to its bottom.
    // 'auto' rather than 'smooth', so a reduced-motion preference is not
    // overridden.
    added?.scrollIntoView({ block: 'center', behavior: 'auto' });
  }, [landing, data, list]);

  if (!search.hasNextPage || data === undefined) return null;

  const busy = search.isFetchingNextPage;
  const failed = search.isFetchNextPageError && !busy;

  async function more() {
    if (busy || data === undefined) return;
    const pressed = { from: data.items.length, first: data.pages[0] ?? [] };
    const result = await search.fetchNextPage();
    // fetchNextPage resolves with the observer's CURRENT result. If the query
    // changed while the page loaded (typed, or the panel closed), that result
    // is another search's, and nothing below is about the page pressed for:
    // acting on it moved focus out of the search box mid-typing (FE-103-T15).
    // The landing effect makes the same test, but the branch at the end moves
    // focus at once, not through that effect.
    if (result.data?.pages[0] !== pressed.first) return;
    // On failure focus stays on the button, which now offers the retry.
    if (result.isError) return;
    const count = result.data?.items.length ?? 0;
    if (count > pressed.from) {
      setLanding(pressed);
      return;
    }
    // A last page with nothing on it: the button is about to go, and the old
    // last result is the nearest place to leave the reader.
    restoreFocusTo(focusResult(list.current, count - 1) ?? button.current);
  }

  return (
    <div className={styles.more}>
      {failed ? (
        <p className={styles.error} role="alert">
          {t('placeSearch.moreFailed')}
        </p>
      ) : null}
      <button
        aria-busy={busy || undefined}
        className={styles.button}
        onClick={() => {
          void more();
        }}
        ref={button}
        type="button"
      >
        {busy
          ? t('placeSearch.loadingMore')
          : failed
            ? t('placeSearch.retryMore')
            : t('placeSearch.more')}
      </button>
    </div>
  );
}
