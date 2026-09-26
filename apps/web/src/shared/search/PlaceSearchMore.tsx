import { useEffect, useRef, useState, type RefObject } from 'react';
import type { UseInfiniteQueryResult } from '@tanstack/react-query';
import { useI18n } from '../../i18n/I18nProvider.js';
import {
  PROBLEM_POLICY,
  isProblem,
  problemPresentation,
  type PlaceSearchResults,
  type Problem,
} from '../api/index.js';
import { restoreFocusTo } from '../ui/components/focus-restore.js';
import styles from './PlaceSearchMore.module.css';

// The continuation under a place-search result list (#54 §4, FE-103-T5..T21).
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
//     and the reason is announced beside it. When the server refused the
//     cursor itself (CURSOR_EXPIRED, CURSOR_INVALID), sending it again cannot
//     work, so the retry becomes the contract's recovery instead: 처음부터 다시
//     보기, which asks again from page one (FE-103-T20). A press, not an
//     automatic refetch: PROBLEM_POLICY gives both codes `retry: 'none'`, and
//     the button is already where the reader is. The feed restarts on its own
//     because its scroll trigger has no control to put the recovery on.
//   - done: focus moves to the first new result, which is where the next Tab
//     from the old last result would have gone. Leaving it on the button
//     would put the new results behind it; and on the last page the button
//     unmounts, dropping focus on the document.

type PlaceSearch = UseInfiniteQueryResult<PlaceSearchResults, Problem | Error>;

/** A refusal of the cursor itself: resending it cannot succeed. */
function cursorRefused(error: unknown): error is Problem {
  return isProblem(error) && PROBLEM_POLICY[error.code].recovery === 'reset-cursor';
}

/**
 * Whether the search itself failed, which is the screen's own alert to show.
 *
 * Not a failed continuation: the control below the results reports that, and
 * the results stay. Nor the restart that follows a refused cursor: while page
 * one is asked for again the query still holds the cursor error, but no longer
 * as a next-page error, so `isFetchNextPageError` alone would call it a failed
 * search for as long as the restart takes (FE-103-T21). Page one carries no
 * cursor, so a cursor refusal is never the first page's own failure.
 */
export function searchFailed(search: PlaceSearch): boolean {
  return search.isError && !search.isFetchNextPageError && !cursorRefused(search.error);
}

export interface PlaceSearchMoreProps {
  search: PlaceSearch;
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

/** Focus is still where a press of `button` leaves it (FE-103-T22). */
function focusIsOnPress(button: HTMLButtonElement | null): boolean {
  const active = document.activeElement;
  return active === null || active === document.body || active === button;
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
  // The press that restarts from page one, until that refetch settles. Only
  // this press can say so: a refetch is not a next-page fetch, so the query's
  // own flags would read idle, and a second press would cancel the restart
  // for a next-page request with the refused cursor.
  const [restarting, setRestarting] = useState(false);
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

  const busy = search.isFetchingNextPage || restarting;
  const failed = search.isFetchNextPageError && !busy;
  const refused = failed && cursorRefused(search.error) ? search.error : null;

  async function restart(): Promise<PlaceSearch> {
    setRestarting(true);
    try {
      // Every page received is asked for again from page one, the first with
      // no cursor, each after it with the cursor the fresh page before it
      // returned. The results stay listed meanwhile. The cast: refetch is
      // typed with a plain query's result, but this observer is the infinite
      // one and answers with its own, hasNextPage included.
      return (await search.refetch()) as PlaceSearch;
    } finally {
      setRestarting(false);
    }
  }

  async function more() {
    if (busy || data === undefined) return;
    const pressed = { from: data.items.length, first: data.pages[0] ?? [] };
    const result = refused ? await restart() : await search.fetchNextPage();
    // fetchNextPage (and refetch) resolves with the observer's CURRENT result.
    // If the query changed while the page loaded (typed, or the panel closed),
    // that result is another search's, and nothing below is about the page
    // pressed for: acting on it moved focus out of the search box mid-typing
    // (FE-103-T15). The landing effect makes the same test, but the branch at
    // the end moves focus at once, not through that effect. After a restart
    // whose page one came back changed this also stops, erring towards moving
    // nothing: the button is still there unless the results shrank.
    if (result.data?.pages[0] !== pressed.first) return;
    // On failure focus stays on the button, which now offers the retry.
    if (result.isError) return;
    // The same search, but the reader may have gone elsewhere while the page
    // loaded: a click into the search box is enough, before a letter is typed
    // (FE-103-T22). Focus moves only while it is still where a press leaves
    // it: on this button, or on the document, where Safari leaves a clicked
    // button and where focus falls if the button unmounts. Read when the press
    // settles, because where the reader is now is what a move would take them
    // from. Both moves below depend on it; the landing one only waits for the
    // page to render.
    if (!focusIsOnPress(button.current)) return;
    const count = result.data?.items.length ?? 0;
    if (!refused && count > pressed.from) {
      setLanding(pressed);
      return;
    }
    // A restart adds no page, so the button stays and keeps the focus it has.
    if (refused && result.hasNextPage) return;
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
          : refused
            ? problemPresentation(refused, t).ctaLabel
            : failed
              ? t('placeSearch.retryMore')
              : t('placeSearch.more')}
      </button>
    </div>
  );
}
