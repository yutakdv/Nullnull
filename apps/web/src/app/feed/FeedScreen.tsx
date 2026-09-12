import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router';
import { useI18n } from '../../i18n/I18nProvider.js';
import {
  isProblem,
  useAddTripCandidate,
  useFeed,
  useTrips,
} from '../../shared/api/index.js';
import { FeedPostCard, type TripAddState } from '../../shared/ui/index.js';
import styles from './FeedScreen.module.css';

// Figma: S03-F0 `391:310` (no trip) and S03-F1 `396:2926` (active trip).
//
// FR-FED-01, FR-FED-02. Two variants of one screen, not two screens: the
// difference is whether a trip is selected, which the contract expresses as
// `tripId` on the request and `candidateState` on each card.
//
// Scope: listing, card states and pagination. The cover opens the post detail
// (FE-202), and the `+` saves the place as a trip candidate (FE-203).
//
// The `+` used to be rendered with no handler, which is the thing the comment
// here warned against: pressing 내 여행에 담기 sent nothing, changed nothing
// and said nothing — verified in a browser by wrapping fetch, zero requests
// and an unchanged aria-label. A screen-reader user had no way to learn the
// promise was empty.
//
// Saving a candidate is NOT scheduling. It writes /trips/:id/candidates,
// creates no TripItem and cannot move the itinerary's version — the contract
// makes `tripScheduleChanged` a `const: false`, which is invariant 2 written
// into the schema. Choosing WHICH trip is still FE-203's sheet (S06) and
// needs BA-034; until then the selected trip is the first one, as the rest of
// this screen already assumes.
//
// MOCK DATA: listFeed has no approved example, so the msw fixture behind it
// is a schema-valid guess (packages/contracts). The screen calls the real
// generated client, so BA-032 landing removes the fixture and handler only.

export function FeedScreen() {
  const { t } = useI18n();
  const navigate = useNavigate();
  const trips = useTrips();

  // The first trip is the selected one until FE-203 introduces a real
  // selector. Null while the list is still loading, which is the difference
  // between "no trip" and "not known yet" — asking with a tripId we do not
  // have yet would bind the cursor to the wrong selection.
  const selectedTripId = trips.data?.items[0]?.id ?? null;
  // Waits for the trip list: the cursor the server mints is bound to the trip
  // selection, so asking before it is known spends a request on a selection
  // that is about to change.
  const feed = useFeed(selectedTripId, trips.isSuccess);
  const addCandidate = useAddTripCandidate(selectedTripId);

  // Which card is mid-save, and how each one ended. Per place rather than one
  // flag for the screen: the buttons are one per card, and a single flag would
  // put every card into the state of whichever was pressed last.
  const [addStates, setAddStates] = useState<Record<string, TripAddState>>({});
  // One key per place, held across retries of that same save so a retry after
  // a lost response replays it instead of saving twice (invariant 6).
  const addKeys = useRef<Record<string, string>>({});

  function saveCandidate(placeId: string, postId: string) {
    if (selectedTripId === null) return;
    addKeys.current[placeId] ??= crypto.randomUUID();
    setAddStates((current) => ({ ...current, [placeId]: 'loading' }));
    addCandidate.mutate(
      {
        // POST with the post it came from: the contract's source records
        // where a candidate was found, and the feed knows the answer
        // exactly. Inventing a FEED type would not compile — the enum is
        // POST/SEARCH/LIVE/IMPORT — and dropping postId would throw away
        // provenance the screen already has.
        request: { placeId, source: { type: 'POST', postId } },
        idempotencyKey: addKeys.current[placeId],
      },
      {
        onSuccess: (result) => {
          // The contract answers 200 with duplicate:true when the candidate
          // already existed and 201 when it is new. Both mean saved, and the
          // user is told which rather than shown a plain success for a no-op.
          delete addKeys.current[placeId];
          setAddStates((current) => ({
            ...current,
            [placeId]: result.duplicate ? 'duplicate' : 'saved',
          }));
        },
        onError: () => {
          // The key is kept, so pressing again replays this same save.
          setAddStates((current) => ({ ...current, [placeId]: 'error' }));
        },
      },
    );
  }

  // A cursor lives 15 minutes. When one expires the contract's policy is to
  // start again from page one rather than retry (problem-policy.ts:103), so
  // the screen says what happened instead of showing a dead end.
  const [cursorReset, setCursorReset] = useState(false);
  const resetting = useRef(false);
  const error = feed.error;
  const expired =
    isProblem(error) &&
    (error.code === 'CURSOR_EXPIRED' || error.code === 'CURSOR_INVALID');
  const failed = feed.isError;

  useEffect(() => {
    if (!expired || resetting.current) return;
    resetting.current = true;
    setCursorReset(true);
    // Drops every accumulated page and refetches from the first one.
    void feed.refetch().finally(() => {
      resetting.current = false;
    });
  }, [expired, feed]);

  // The shared card components keep Korean defaults so Storybook can mount
  // them without a provider; inside the app the selected locale's words are
  // passed in. Built once rather than per card.
  const cardLabels = {
    add: {
      idle: t('tripAdd.idle'),
      saved: t('tripAdd.saved'),
      duplicate: t('tripAdd.duplicate'),
      'no-trip': t('tripAdd.no-trip'),
      loading: t('tripAdd.loading'),
      error: t('tripAdd.error'),
    },
    state: {
      LIVE: t('state.LIVE'),
      FORECAST: t('state.FORECAST'),
      QUALITATIVE: t('state.QUALITATIVE'),
      STALE: t('state.STALE'),
      UNAVAILABLE: t('state.UNAVAILABLE'),
      REPLAY: t('state.REPLAY'),
    },
    licenseTerms: t('license.terms'),
  };

  const cards = feed.data?.pages.flatMap((page) => page.items) ?? [];
  const noTrip = trips.isSuccess && trips.data.items.length === 0;

  return (
    <section aria-labelledby="feed-heading" className={styles.screen}>
      <h1 className={styles.title} id="feed-heading">
        {t('feed.title')}
      </h1>

      {/* S03-F0: with no trip there is nothing to collect candidates into, so
          the screen offers the one action that changes that. */}
      {noTrip ? (
        <p className={styles.prompt}>
          <span className={styles.promptText}>{t('feed.emptyNoTrip')}</span>
          <button
            className={styles.promptCta}
            onClick={() => {
              void navigate('/start');
            }}
            type="button"
          >
            {t('feed.createTrip')}
          </button>
        </p>
      ) : null}

      {feed.isPending && !trips.isError ? (
        <p className={styles.state} role="status">
          {t('feed.loading')}
        </p>
      ) : null}

      {/* An expired cursor is recovered from, not reported as a failure: the
          refetch above already started. Any other error is a real one.

          trips.isError counts as one. The feed query is gated on
          trips.isSuccess — the cursor the server mints is bound to the trip
          selection — so a failed trip list leaves the feed query disabled and
          permanently pending. Reading only feed.isError meant the screen sat
          on 불러오는 중 for ever with no error and no retry. */}
      {(failed || trips.isError) && !expired ? (
        <p className={styles.state} role="alert">
          {t('feed.error')}
          <button
            className={styles.retry}
            onClick={() => {
              // Both, in the order that repairs the gate: refetching only the
              // feed would leave it disabled behind a trip list that is still
              // in error, and the screen would go straight back to stuck.
              if (trips.isError) void trips.refetch();
              void feed.refetch();
            }}
            type="button"
          >
            {t('feed.retry')}
          </button>
        </p>
      ) : null}

      {cursorReset ? (
        <p aria-live="polite" className={styles.notice}>
          {t('feed.cursorExpired')}
        </p>
      ) : null}

      {feed.isSuccess && cards.length === 0 && !noTrip ? (
        <p className={styles.state}>{t('feed.empty')}</p>
      ) : null}

      {cards.length > 0 ? (
        <ul aria-labelledby="feed-heading" className={styles.list}>
          {cards.map((card) => (
            <li key={card.post.id}>
              <FeedPostCard
                card={card}
                // Live since FE-202: the cover button opens the post. Before
                // that this callback was unbound and pressing the cover did
                // nothing at all.
                onOpenPost={(postId) => {
                  void navigate(`/posts/${postId}`);
                }}
                // Bound only where there is something to do. A card already in
                // the trip has nothing to add, and NO_TRIP_SELECTED has
                // nowhere to add it to — pressing either must not reach the
                // server with a guessed trip id. The button keeps saying which
                // state it is in either way.
                onAddCandidate={
                  card.candidateState === 'NOT_SAVED' && selectedTripId !== null
                    ? (placeId) => {
                        saveCandidate(placeId, card.post.id);
                      }
                    : undefined
                }
                addState={addStates[card.primaryPlace.id]}
                labels={{
                  ...cardLabels,
                  // Interpolated per card: the level is part of the sentence.
                  crowdLevel: t('crowd.level', {
                    steps: 4,
                    level: Number(card.crowd?.ordinalLevel) || 0,
                  }),
                }}
              />
            </li>
          ))}
        </ul>
      ) : null}

      {/* A button, not an infinite scroll: a scroll handler that loads more
          has no keyboard equivalent and no announced end, and the frontend
          rules require both a keyboard path and a stated result. */}
      {feed.hasNextPage ? (
        <button
          className={styles.more}
          disabled={feed.isFetchingNextPage}
          onClick={() => {
            void feed.fetchNextPage();
          }}
          type="button"
        >
          {feed.isFetchingNextPage ? t('feed.loadingMore') : t('feed.more')}
        </button>
      ) : null}

      {feed.isSuccess && !feed.hasNextPage && cards.length > 0 ? (
        <p className={styles.state}>{t('feed.end')}</p>
      ) : null}
    </section>
  );
}
