import { useEffect, useRef, useState } from 'react';
import { Link, useNavigate, useOutletContext } from 'react-router';
import { useI18n } from '../../i18n/I18nProvider.js';
import {
  isProblem,
  useAddTripCandidate,
  useFeed,
  useTrips,
  useUpdatePreferences,
} from '../../shared/api/index.js';
import { formatTripPeriod } from '../../shared/i18n/trip-period.js';
import {
  CROWD_LEVEL_STEPS,
  FeedPostCard,
  IconCheck,
  IconChevronDown,
  IconSearch,
  Toast,
  TripPicker,
  type TripAddState,
} from '../../shared/ui/index.js';
import type { AppShellOutletContext } from '../AppShell.js';
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
//
// No author, heart count or reaction control (#163): FCR-024 in
// docs/design/FIGMA_CHANGE_REQUESTS.md is the record of that decision and its
// reasoning. This screen sends no feed feedback of any kind, which
// feed.test.tsx guards at the wire.

export function FeedScreen() {
  const { locale, t } = useI18n();
  const navigate = useNavigate();
  const { activeTripId, activeTripReady, setActiveTripId } =
    useOutletContext<AppShellOutletContext>();
  const trips = useTrips();
  const updatePreferences = useUpdatePreferences();

  // The feed follows the owner's representative trip rather than keeping a
  // screen-local filter. The same activeTripId drives the My Trip tab in
  // AppShell, so one selection cannot leave the banner, candidate state and
  // tab destination disagreeing with one another.
  const [showNoTripPrompt, setShowNoTripPrompt] = useState(true);
  const [showSearchNotice, setShowSearchNotice] = useState(false);
  const searchNoticeTimer = useRef<number | null>(null);
  const [isTripMenuOpen, setIsTripMenuOpen] = useState(false);
  const tripFilterRef = useRef<HTMLDivElement>(null);
  const tripTriggerRef = useRef<HTMLButtonElement>(null);
  const tripItems = trips.data?.items ?? [];
  const activeStillExists = tripItems.some((trip) => trip.id === activeTripId);
  // Never promote the first trip only because the owner has no representative.
  // That made the feed LOOK selected while the My Trip tab still held null.
  // `activeTripId` is the one source of truth; the selector below is how null
  // becomes a deliberate choice.
  const selectedTripId = activeStillExists ? activeTripId : null;
  // The place the sheet is choosing a trip for, or null when it is closed.
  const [pickerFor, setPickerFor] = useState<{
    placeId: string;
    postId: string;
    name: string;
  } | null>(null);
  // Waits for the trip list: the cursor the server mints is bound to the trip
  // selection, so asking before it is known spends a request on a selection
  // that is about to change.
  const feed = useFeed(selectedTripId, trips.isSuccess && activeTripReady);
  const loadMoreAnchor = useRef<HTMLDivElement>(null);
  // Bound to the selection, and the sheet sets the selection before it saves —
  // useAddTripCandidate takes its trip at hook level, so a save into a trip
  // other than the selected one is not expressible without changing that hook.
  const addCandidate = useAddTripCandidate(selectedTripId);

  // Which card is mid-save, and how each one ended. Per place rather than one
  // flag for the screen: the buttons are one per card, and a single flag would
  // put every card into the state of whichever was pressed last.
  const [addStates, setAddStates] = useState<Record<string, TripAddState>>({});
  // S03-C2 `399:843` / C3 `399:1011` / C4 `399:1179`: the result of a save is a
  // toast, not a sheet and not the button alone.
  //
  // The button cannot carry this by itself. `saved` and `duplicate` are
  // different facts — one made a candidate, one found it already there — and
  // both render the same ✓ glyph, so without the toast the two screens Figma
  // draws separately are indistinguishable. The toast is also where `보기`
  // lives, which is the only path from the feed to the trip the place landed
  // in.
  //
  // Secondary feedback, per Toast's own contract: the button keeps the
  // durable state after the toast is gone, so nothing that must stay
  // actionable lives only here.
  // `postId` and `tripId` ride along because 다시 시도 has to replay the SAME
  // save: the source provenance and the chosen trip are both part of it, and
  // re-deriving them from the selection would retry into whichever trip is
  // selected now rather than the one the user answered the sheet with.
  const [toast, setToast] = useState<{
    kind: 'saved' | 'duplicate' | 'error';
    placeId: string;
    postId: string;
    tripId: string;
    tripName: string;
  } | null>(null);
  const [toastPaused, setToastPaused] = useState(false);
  // One key per place, held across retries of that same save so a retry after
  // a lost response replays it instead of saving twice (invariant 6).
  const addKeys = useRef<Record<string, string>>({});

  function showSearchComingSoon() {
    setShowSearchNotice(true);
    if (searchNoticeTimer.current !== null) {
      window.clearTimeout(searchNoticeTimer.current);
    }
    searchNoticeTimer.current = window.setTimeout(() => {
      setShowSearchNotice(false);
      searchNoticeTimer.current = null;
    }, 1_000);
  }

  function saveCandidate(placeId: string, postId: string, tripId: string) {
    if (tripId === '') return;
    addKeys.current[placeId] ??= crypto.randomUUID();
    setAddStates((current) => ({ ...current, [placeId]: 'loading' }));
    addCandidate.mutate(
      {
        // The trip the user answered the sheet with, sent explicitly. It used
        // to ride on the hook's closure instead, which made this argument
        // inert: the save reached the right trip only because the state update
        // happened to re-render first, and the test written to prove the
        // choice was honoured passed with the choice deleted.
        tripId,
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
          setToast({
            kind: result.duplicate ? 'duplicate' : 'saved',
            placeId,
            postId,
            tripId,
            // Named, because the feed can collect into any of several trips
            // and "담았어요" alone does not say which one received it.
            tripName: tripItems.find((trip) => trip.id === tripId)?.title ?? '',
          });
        },
        onError: () => {
          // The card goes back to idle rather than to an error state, and the
          // toast carries the retry. That is `Action / TripAddButton`'s own
          // rule for this screen: "D-01 실패 화면(S03-C4)에서는 카드를
          // 원상(idle) 유지하고 다시 시도는 Toast가 담당한다" — Figma
          // `399:1179` draws the card with + and the error in the toast.
          //
          // It is also the honest state: nothing was saved, so a card that
          // still says 담기 describes the trip correctly. An error glyph on
          // the card would outlive the failure it refers to.
          setAddStates((current) => {
            const next = { ...current };
            delete next[placeId];
            return next;
          });
          // The key is kept, so pressing 다시 시도 replays this same save
          // rather than starting a second one (invariant 6).
          setToast({ kind: 'error', placeId, postId, tripId, tripName: '' });
        },
      },
    );
  }

  // A cursor lives 15 minutes. When one expires the contract's policy is to
  // start again from page one rather than retry (problem-policy.ts:103), so
  // the screen says what happened instead of showing a dead end.
  const [cursorReset, setCursorReset] = useState(false);
  // Whether page one has already been asked for on account of an expired
  // cursor. NOT a latch released when the refetch settles: the effect's deps
  // include the query object, which is new on every render, so a reset that
  // ALSO answers CURSOR_EXPIRED found the latch open again and fired
  // immediately — measured at ~14,900 requests in 600ms against a code the
  // contract marks retry: 'none'. Recovery is one attempt, and the screen
  // reports it rather than trying for ever.
  const resetAttempted = useRef(false);
  const error = feed.error;
  const expired =
    isProblem(error) &&
    (error.code === 'CURSOR_EXPIRED' || error.code === 'CURSOR_INVALID');
  const failed = feed.isError;
  // 503 SOURCE_UNAVAILABLE: the catalog is not published yet, so the request
  // arrived and was answered — there is simply nothing to recommend from.
  // `feed.error` would call that a load failure and read as a broken screen,
  // which is what a judge opening this before the gate opens would conclude.
  //
  // Only the feed query is read. A failing /trips is a real load failure and
  // keeps `feed.error`, because the trip list has nothing to do with whether
  // the catalog is published.
  const sourceUnavailable = isProblem(error) && error.code === 'SOURCE_UNAVAILABLE';

  useEffect(() => {
    return () => {
      if (searchNoticeTimer.current !== null) {
        window.clearTimeout(searchNoticeTimer.current);
      }
    };
  }, []);

  // Confirmations are deliberately brief secondary feedback. Errors keep
  // their retry action until the user acts, while successful saves disappear
  // after the product's 1.5s acknowledgement window. The cleanup prevents a
  // stale result from clearing a newer toast or updating an unmounted screen.
  useEffect(() => {
    if (toast === null || toast.kind === 'error' || toastPaused) return;
    const current = toast;
    const timer = window.setTimeout(() => {
      setToast((shown) => (shown === current ? null : shown));
    }, 1_500);
    return () => {
      window.clearTimeout(timer);
    };
  }, [toast, toastPaused]);

  useEffect(() => {
    if (!expired) {
      // A page that loads clears the mark, so a cursor that expires later in
      // the same session is recovered from again.
      resetAttempted.current = false;
      return;
    }
    if (resetAttempted.current) return;
    resetAttempted.current = true;
    setCursorReset(true);
    // Drops every accumulated page and refetches from the first one.
    void feed.refetch();
  }, [expired, feed]);

  useEffect(() => {
    if (!isTripMenuOpen) return;

    const closeFromOutside = (event: PointerEvent) => {
      if (tripFilterRef.current?.contains(event.target as Node)) return;
      setIsTripMenuOpen(false);
    };
    const closeWithEscape = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return;
      setIsTripMenuOpen(false);
      tripTriggerRef.current?.focus();
    };

    document.addEventListener('pointerdown', closeFromOutside);
    document.addEventListener('keydown', closeWithEscape);
    return () => {
      document.removeEventListener('pointerdown', closeFromOutside);
      document.removeEventListener('keydown', closeWithEscape);
    };
  }, [isTripMenuOpen]);

  useEffect(() => {
    const anchor = loadMoreAnchor.current;
    if (!anchor || !feed.hasNextPage || feed.isFetchingNextPage) return;

    const loadNextPage = () => {
      void feed.fetchNextPage();
    };
    if (typeof IntersectionObserver === 'undefined') {
      // Progressive fallback: older browsers still receive the full feed
      // instead of losing every page after the first one.
      loadNextPage();
      return;
    }

    const observer = new IntersectionObserver(
      (entries) => {
        if (!entries.some((entry) => entry.isIntersecting)) return;
        observer.disconnect();
        loadNextPage();
      },
      { rootMargin: '240px 0px' },
    );
    observer.observe(anchor);
    return () => {
      observer.disconnect();
    };
  }, [feed.fetchNextPage, feed.hasNextPage, feed.isFetchingNextPage]);

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
    crowdStages: {
      1: t('crowd.stage.1'),
      2: t('crowd.stage.2'),
      3: t('crowd.stage.3'),
      4: t('crowd.stage.4'),
      5: t('crowd.stage.5'),
    },
    licenseTerms: t('license.terms'),
  };

  const cards = feed.data?.pages.flatMap((page) => page.items) ?? [];
  const noTrip = trips.isSuccess && trips.data.items.length === 0;
  const selectedTrip = tripItems.find((trip) => trip.id === selectedTripId) ?? null;
  const needsRepresentativeTrip =
    activeTripReady && trips.isSuccess && tripItems.length > 0 && selectedTrip === null;
  const selectedTripPeriod = selectedTrip
    ? formatTripPeriod(selectedTrip.startDate, selectedTrip.endDate, locale, 'long')
    : '';

  const tripPeriod = (startDate: string, endDate: string) =>
    formatTripPeriod(startDate, endDate, locale, 'long');

  function selectRepresentativeTrip(tripId: string) {
    setIsTripMenuOpen(false);
    if (tripId === selectedTripId) return;
    updatePreferences.reset();
    updatePreferences.mutate(
      { activeTripId: tripId },
      {
        onSuccess: (owner) => {
          setActiveTripId(owner.activeTripId ?? null);
        },
      },
    );
  }

  return (
    <section aria-labelledby="feed-heading" className={styles.screen}>
      <header className={styles.header}>
        <h1 aria-label={t('feed.title')} className={styles.title} id="feed-heading">
          <Link
            aria-label={t('feed.home')}
            className={styles.logoLink}
            onClick={() => {
              window.scrollTo({ top: 0 });
            }}
            to="/feed"
          >
            <span aria-hidden="true">Nullnull</span>
          </Link>
        </h1>
        <button
          aria-label={t('feed.search')}
          className={styles.searchButton}
          onClick={showSearchComingSoon}
          type="button"
        >
          <IconSearch />
        </button>

        {showSearchNotice ? (
          <div className={styles.searchNotice}>
            <Toast message={t('feed.searchComingSoon')} />
          </div>
        ) : null}
      </header>

      {/* S03-F0: with no trip there is nothing to collect candidates into, so
          the screen offers the one action that changes that. */}
      {noTrip && showNoTripPrompt ? (
        <aside className={styles.prompt}>
          <span className={styles.promptBadge}>{t('feed.noTripBadge')}</span>
          <h2 className={styles.promptTitle}>{t('feed.noTripTitle')}</h2>
          <p className={styles.promptText}>{t('feed.emptyNoTrip')}</p>
          <div className={styles.promptActions}>
            <button
              className={styles.promptCta}
              onClick={() => {
                void navigate('/start');
              }}
              type="button"
            >
              {t('feed.createTrip')}
            </button>
            <button
              className={styles.promptSecondary}
              onClick={() => {
                setShowNoTripPrompt(false);
              }}
              type="button"
            >
              {t('feed.browse')}
            </button>
          </div>
        </aside>
      ) : needsRepresentativeTrip ? (
        <aside className={styles.prompt}>
          <span className={styles.promptBadge}>{t('feed.noRepresentativeBadge')}</span>
          <h2 className={styles.promptTitle}>{t('feed.noRepresentativeTitle')}</h2>
          <p className={styles.promptText}>{t('feed.noRepresentativeText')}</p>
          <div className={styles.promptActions}>
            <button
              className={styles.promptCta}
              onClick={() => {
                void navigate('/trips/select');
              }}
              type="button"
            >
              {t('feed.chooseRepresentative')}
            </button>
            <Link className={styles.authorLink} to="/posts/new">
              {t('author.entry')}
            </Link>
          </div>
        </aside>
      ) : selectedTrip ? (
        <div className={styles.tripFilter} ref={tripFilterRef}>
          <div className={styles.tripBar}>
            <span className={styles.activeTripPeriod}>{selectedTripPeriod}</span>
            <Link className={styles.authorLink} to="/posts/new">
              {t('author.entry')}
            </Link>
            <button
              aria-busy={updatePreferences.isPending}
              aria-controls="representative-trip-list"
              aria-expanded={isTripMenuOpen}
              aria-label={`${t('feed.representativeTrip')}: ${selectedTrip.title}`}
              className={styles.activeTrip}
              data-testid="active-trip-banner"
              disabled={updatePreferences.isPending}
              onClick={() => {
                setIsTripMenuOpen((open) => !open);
              }}
              ref={tripTriggerRef}
              type="button"
            >
              <span className={styles.activeTripChoice}>
                <span className={styles.activeTripTitle}>{selectedTrip.title}</span>
                <IconChevronDown
                  className={styles.activeTripChevron}
                  data-open={isTripMenuOpen}
                  size={18}
                />
              </span>
            </button>
          </div>

          {isTripMenuOpen ? (
            <ul
              aria-label={t('feed.representativeTrip')}
              className={styles.tripOptions}
              id="representative-trip-list"
            >
              {tripItems
                .filter((trip) => trip.id !== selectedTripId)
                .map((trip) => (
                  <li key={trip.id}>
                    <button
                      aria-label={trip.title}
                      aria-pressed={trip.id === selectedTripId}
                      className={styles.tripOption}
                      disabled={updatePreferences.isPending}
                      onClick={() => {
                        selectRepresentativeTrip(trip.id);
                      }}
                      type="button"
                    >
                      <span className={styles.activeTripPeriod}>
                        {tripPeriod(trip.startDate, trip.endDate)}
                      </span>
                      <span className={styles.activeTripChoice}>
                        <span className={styles.activeTripTitle}>{trip.title}</span>
                        {trip.id === selectedTripId ? <IconCheck size={18} /> : null}
                      </span>
                    </button>
                  </li>
                ))}
            </ul>
          ) : null}
        </div>
      ) : null}

      {updatePreferences.isError ? (
        <p
          aria-label={t('feed.representativeTripError')}
          className={styles.preferenceError}
          role="alert"
        >
          {t('feed.representativeTripError')}
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
          {sourceUnavailable ? t('feed.sourceUnavailable') : t('feed.error')}
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
                        // Ask which trip rather than assuming the first one.
                        // FR-CAN-01: the `+` opens the picker, and the save is
                        // what the user answers with.
                        setPickerFor({
                          placeId,
                          postId: card.post.id,
                          name: card.primaryPlace.name,
                        });
                      }
                    : undefined
                }
                addState={addStates[card.primaryPlace.id]}
                labels={{
                  ...cardLabels,
                  // Interpolated per card: the level is part of the sentence.
                  crowdLevel: t('crowd.level', {
                    steps: CROWD_LEVEL_STEPS,
                    level: Number(card.crowd?.ordinalLevel) || 0,
                  }),
                }}
              />
            </li>
          ))}
        </ul>
      ) : null}

      {feed.hasNextPage ? (
        <div className={styles.paginationAnchor} ref={loadMoreAnchor}>
          {feed.isFetchingNextPage ? (
            <div
              aria-label={t('feed.loadingMore')}
              aria-live="polite"
              className={styles.paginationStatus}
              role="status"
            >
              <span aria-hidden="true" className={styles.spinner} />
            </div>
          ) : null}
        </div>
      ) : null}

      {/* FR-CAN-01: which trip the place goes in. Mounted once for the screen
          rather than per card — twelve cards would otherwise mount twelve
          dialogs, which is the shape that made an earlier E2E measure the
          wrong one. */}
      <TripPicker
        failed={trips.isError}
        locale={locale}
        labels={{
          title: t('tripPicker.title'),
          cancel: t('tripPicker.cancel'),
          loading: t('tripPicker.loading'),
          empty: t('tripPicker.empty'),
          createTrip: t('feed.createTrip'),
          error: t('tripPicker.error'),
          retry: t('tripPicker.retry'),
        }}
        loading={trips.isPending}
        onCancel={() => {
          setPickerFor(null);
        }}
        onCreateTrip={() => {
          setPickerFor(null);
          void navigate('/start');
        }}
        onPick={(tripId) => {
          const target = pickerFor;
          setPickerFor(null);
          if (target) saveCandidate(target.placeId, target.postId, tripId);
        }}
        onRetry={() => {
          void trips.refetch();
        }}
        open={pickerFor !== null}
        placeName={pickerFor?.name ?? ''}
        selectedTripId={selectedTripId}
        trips={tripItems}
      />

      {/* S03-C2 `399:843` · C3 `399:1011` · C4 `399:1179`. Success and
          duplicate confirmations dismiss after 1.5s. The error remains because
          its retry action is the only recovery available in this context. */}
      {toast ? (
        <div
          className={styles.toastLayer}
          onBlurCapture={(event) => {
            if (!event.currentTarget.contains(event.relatedTarget)) setToastPaused(false);
          }}
          onFocusCapture={() => {
            setToastPaused(true);
          }}
          onMouseEnter={() => {
            setToastPaused(true);
          }}
          onMouseLeave={() => {
            setToastPaused(false);
          }}
        >
          <Toast
            actionLabel={
              toast.kind === 'error' ? t('tripAdd.toast.retry') : t('tripAdd.toast.view')
            }
            message={
              toast.kind === 'saved'
                ? t('tripAdd.toast.saved', { trip: toast.tripName })
                : toast.kind === 'duplicate'
                  ? t('tripAdd.toast.duplicate')
                  : t('tripAdd.toast.error')
            }
            onAction={() => {
              if (toast.kind === 'error') {
                setToast(null);
                saveCandidate(toast.placeId, toast.postId, toast.tripId);
                return;
              }
              // 보기 goes to the trip the place was saved into — the candidate
              // list, not the itinerary: a candidate is not a scheduled item
              // (invariant 1) and landing on the day view would suggest it was.
              setToast(null);
              void navigate(`/trip/${toast.tripId}/candidates`);
            }}
            tone={toast.kind === 'error' ? 'error' : 'info'}
          />
        </div>
      ) : null}

      {feed.isSuccess && !feed.hasNextPage && cards.length > 0 ? (
        <p className={styles.state}>{t('feed.end')}</p>
      ) : null}
    </section>
  );
}
