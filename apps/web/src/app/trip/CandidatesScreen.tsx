import { useQueryClient } from '@tanstack/react-query';
import { useRef, useState, type RefObject } from 'react';
import { useNavigate, useParams } from 'react-router';
import type { components } from '@nullnull/api-client';
import { useI18n } from '../../i18n/I18nProvider.js';
import type { MessageKey } from '../../i18n/messages.js';
import {
  isProblem,
  tripQueryKey,
  useAddTripItem,
  useCandidateMatches,
  useRemoveTripCandidate,
  useTrip,
  useTripCandidates,
} from '../../shared/api/index.js';
import {
  DataAttribution,
  NavBar,
  PlaceThumbnail,
} from '../../shared/ui/components/index.js';
import { restoreFocusTo } from '../../shared/ui/components/focus-restore.js';
import styles from './CandidatesScreen.module.css';
import { isScheduled, nextPosition, visibleCandidates } from './candidates.js';
import { ScheduleCandidateSheet } from './ScheduleCandidateSheet.js';

// Figma: S07-8 candidate panel `412:1912`
// (FR-CAN-05, FR-CAN-07, FR-ITM-02, FE-303).
//
// A saved place is not a scheduled item (invariant 1), and this screen is where
// the difference is visible: everything here is a candidate until the user
// picks a date, and picking one is a single request the server applies
// atomically — the 201 is "item added and candidate marked scheduled"
// (invariant 5). The client never adds the item and then patches the candidate,
// which could leave a scheduled item beside an ACTIVE candidate.
//
// Match states are rendered distinctly. CHECKING is not
// "no dates", UNKNOWN is not "no dates", and NONE is the only one that says so.
//
// MOCK DATA: listTripCandidates, getCandidateTripMatches and addTripItem have
// no approved example (BA-034, BA-042).
//
// The Figma card shows `ⓒ한국관광공사` under each place and a recommendation
// reason line.
//
// The CREDIT ships (FCR-031 closed it): BA-022 gave PlaceSummary
// `sourceAttribution`, and this screen renders the server's approved string
// verbatim below — `candidate.place.sourceAttribution`. This comment used to
// say the opposite ("PlaceSummary carries neither a source nor a provenance"),
// which stopped being true when `b85f09d` added the field and the render, and
// the note was left behind. A header that contradicts the code 200 lines under
// it is worse than no header: the next reader trusts it and re-raises a closed
// request, or deletes a render they think is invented.
//
// NOT BUILT: the recommendation reason line. Nothing in TripCandidate or
// PlaceSummary explains WHY a place is a candidate — the traveller saved it —
// so that line would be invented text (invariant 9).

type TripCandidate = components['schemas']['TripCandidate'];

export function CandidatesScreen() {
  const { tripId } = useParams();
  const { t } = useI18n();
  const navigate = useNavigate();
  const trip = useTrip(tripId ?? null);
  const candidates = useTripCandidates(tripId ?? null);
  const [openId, setOpenId] = useState<string | null>(null);
  const headingRef = useRef<HTMLHeadingElement>(null);

  const items = visibleCandidates(candidates.data?.items ?? []);

  return (
    <section className={styles.screen} aria-labelledby="candidates-heading">
      {/* A named destination, not history.go(-1): this screen is reachable by
          deep link and by reload, where "back" belongs to another site. */}
      {/* Back control only: the h1 below is the title, and repeating it in the
          bar shows the same words twice on a 360px screen. */}
      <NavBar
        backLabel={t('candidates.back')}
        onBack={() => {
          void navigate(`/trip/${tripId ?? ''}`);
        }}
        title={
          trip.isSuccess
            ? t('candidates.open', { count: trip.data.trip.candidateCount })
            : t('candidates.title')
        }
      />

      <div className={styles.head}>
        {/* The count waits for the list. Rendering `items.length` while the
            request is in flight shows "0 saved places" to someone who has
            saved places, which reads as data loss rather than as loading. */}
        {/* The total comes from `candidateCount`, the contract's own field —
            NOT from `items.length`, which is one PAGE of the candidates.
            TripScreen already says this in as many words (:150) and links here
            with that number, so counting the page made the two screens
            disagree about the same set one tap apart: the trip total became
            the current page length in otherwise identical wording.

            `trip` is already fetched above for the title, so this costs no
            extra request. */}
        <h1
          className={styles.srOnly}
          id="candidates-heading"
          ref={headingRef}
          tabIndex={-1}
        >
          {trip.isSuccess
            ? t('candidates.open', { count: trip.data.trip.candidateCount })
            : t('candidates.title')}
        </h1>
        <p className={styles.note}>{t('candidates.note')}</p>
      </div>

      {candidates.isPending ? (
        <p className={styles.state} role="status">
          {t('candidates.loading')}
        </p>
      ) : null}

      {candidates.isError ? (
        <p className={styles.state} role="alert">
          {t('candidates.error')}
          <button
            className={styles.secondary}
            onClick={() => {
              void candidates.refetch();
            }}
            type="button"
          >
            {t('trip.retry')}
          </button>
        </p>
      ) : null}

      {candidates.isSuccess && items.length === 0 ? (
        <div className={styles.empty}>
          <p className={styles.emptyTitle}>{t('candidates.empty')}</p>
          <p className={styles.state}>{t('candidates.emptyNote')}</p>
        </div>
      ) : null}

      {items.length > 0 ? (
        <ul className={styles.list}>
          {items.map((candidate) => (
            <li key={candidate.id}>
              <CandidateCardRow
                candidate={candidate}
                afterRefreshRef={headingRef}
                etag={trip.data?.etag ?? null}
                onToggle={() => {
                  setOpenId((current) =>
                    current === candidate.id ? null : candidate.id,
                  );
                }}
                open={openId === candidate.id}
                tripId={tripId ?? null}
              />
            </li>
          ))}
        </ul>
      ) : null}
    </section>
  );
}

interface RowProps {
  afterRefreshRef: RefObject<HTMLHeadingElement | null>;
  candidate: TripCandidate;
  tripId: string | null;
  etag: string | null;
  open: boolean;
  onToggle: () => void;
}

function CandidateCardRow({
  afterRefreshRef,
  candidate,
  tripId,
  etag,
  open,
  onToggle,
}: RowProps) {
  const { locale, t } = useI18n();
  const scheduled = isScheduled(candidate);
  const queryClient = useQueryClient();
  const [refreshing, setRefreshing] = useState(false);
  const refreshButtonRef = useRef<HTMLButtonElement>(null);
  // Only fetched once the user opens this card: asking the server for slots on
  // every candidate at once would be a burst of requests for answers nobody
  // has looked at yet.
  // Fetched for every unscheduled card, not only the expanded one: the frame
  // shows the relation on each card, and a badge gated on expansion could never
  // appear. The cost is one request per candidate, which is what a panel that
  // states each card's relation up front necessarily costs — asked BE on #44
  // whether a batch endpoint is preferred.
  const matches = useCandidateMatches(tripId, scheduled ? null : candidate.id);
  const trip = useTrip(tripId);
  const add = useAddTripItem(tripId);
  const remove = useRemoveTripCandidate(tripId);
  const [failed, setFailed] = useState<string | null>(null);
  // The key for the date this card is scheduling, held across retries of that
  // date. Pressing the same date again after a failure replays the first
  // attempt; picking a different date is a different command and gets its own
  // key. One minted per press would let a retry after a lost response put the
  // place on the day twice (invariant 6).
  const scheduleKey = useRef<{ for: string; key: string } | null>(null);
  // Where focus goes once a date is chosen.
  //
  // Scheduling removes the date button the user was standing on AND the
  // 담기 toggle beside it — the card becomes `scheduled`, and that branch
  // renders neither. Focus fell to document.body, so the next Tab restarted
  // from the top of a list that can run to twenty cards. The remove control
  // is the one button this card keeps in every state, and it already names
  // the place it acts on.
  const afterScheduleRef = useRef<HTMLButtonElement>(null);

  const place = candidate.place;
  const meta = [place.categoryName, place.regionName, place.address]
    .filter((part): part is string => typeof part === 'string' && part.length > 0)
    .join(' · ');
  const match = matches.data;
  const notActive = match?.state === 'NOT_ACTIVE';

  function refresh() {
    if (!tripId || refreshing) return;
    setRefreshing(true);
    // Refresh the trip, list and matches together: a previous ACTIVE row may
    // now be scheduled, dismissed, or saved again. No state is guessed locally.
    void queryClient.invalidateQueries({ queryKey: tripQueryKey(tripId) }).finally(() => {
      setRefreshing(false);
      setTimeout(() => {
        restoreFocusTo(
          refreshButtonRef.current ?? afterScheduleRef.current ?? afterRefreshRef.current,
        );
      }, 0);
    });
  }
  const tripDates = (trip.data?.trip.days ?? []).map((day) => day.date).sort();

  /**
   * Which day of the trip a date is, so the sheet can say `day 2` the way the
   * itinerary does rather than a bare date the user has to map themselves.
   *
   * -1 when the trip has not loaded or the date is outside it; the sheet falls
   * back to the date alone rather than printing `day 0`.
   */
  function dayIndexOf(date: string): number {
    return (trip.data?.trip.days ?? []).findIndex((day) => day.date === date);
  }

  function schedule(date: string, suggestedTime: string | null | undefined) {
    setFailed(null);
    if (scheduleKey.current?.for !== date) {
      scheduleKey.current = { for: date, key: crypto.randomUUID() };
    }
    add.mutate(
      {
        idempotencyKey: scheduleKey.current.key,
        item: {
          placeId: candidate.place.id,
          // Named so the server performs the candidate transition in the same
          // transaction as the insert (invariant 5).
          candidateId: candidate.id,
          date,
          position: nextPosition(trip.data?.trip.days ?? [], date),
          startTime: suggestedTime ?? null,
        },
        etag,
      },
      {
        onSuccess: () => {
          scheduleKey.current = null;
          onToggle();
          // Queued so it runs after React has re-rendered the scheduled
          // state; focusing during the same tick would target the node that
          // is about to be replaced.
          //
          // NOT A DEFECT TODAY, unlike MoveDaySheet's restore: this is the
          // same guard for a case nothing currently reaches. The target is the
          // Remove button, which is `disabled={remove.isPending}` (below), and
          // a removal cannot be in flight at the moment a schedule succeeds —
          // so the bare `.focus()` this replaces did land. It carried no guard
          // at all, though, not even `isConnected`, so the day those two
          // mutations can overlap it becomes a silent no-op and focus goes to
          // <body>. Changed now because the helper costs nothing and the
          // reasoning is cheaper to write down than to rediscover; no test
          // fails if it is reverted, and none should be written to force one.
          setTimeout(() => {
            restoreFocusTo(afterScheduleRef.current);
          }, 0);
        },
        onError: (error) => {
          if (isProblem(error) && error.code === 'TRIP_CHANGED') {
            // The schedule moved under us. Refetch so the next attempt uses a
            // current ETag, and say so rather than silently failing.
            void trip.refetch();
            setFailed(t('candidates.conflict'));
            return;
          }
          setFailed(t('candidates.addFailed'));
        },
      },
    );
  }

  return (
    <article className={styles.card}>
      <div className={styles.cardHead}>
        {/* thumbnailUrl is a real contract field, unlike the source text the
            frame also shows (FCR-031). Decorative: the place name beside it is
            the accessible name, so an empty alt avoids announcing it twice.

            48 matches .thumbEmpty beside it. The old markup passed 56 as an
            attribute while .thumb forced 48px in CSS, and CSS wins, so 48 is
            the size this row has always rendered. PlaceThumbnail sizes inline
            with no CSS rule to override it, which makes the attribute value the
            real one — passing 56 here would silently grow the row. */}
        {candidate.place.thumbnailUrl && candidate.place.thumbnailAttribution ? (
          <PlaceThumbnail place={candidate.place} size={48} />
        ) : (
          <span aria-hidden="true" className={styles.thumbEmpty} />
        )}
        <div className={styles.cardText}>
          <h2 className={styles.name}>{candidate.place.name}</h2>
          {/* categoryName, not categoryCode: BA-022 made the code explicitly
              non-display, and a null name means "show no category". */}
          {meta === '' ? null : <p className={styles.meta}>{meta}</p>}
          {/* FCR-031 / CMP-ATT-001: the server's approved credit, verbatim. */}
          {candidate.place.sourceAttribution ? (
            <DataAttribution compact provenance={candidate.place.sourceAttribution} />
          ) : null}
        </div>
      </div>

      {/* relation-badge (379:371): the card's state in a pill, above the
          actions and visible without expanding — the frame shows it on every
          card. SIMILAR is tinted; everything else is neutral. */}
      {scheduled ? (
        <p className={styles.badge}>{t('candidates.scheduled')}</p>
      ) : (
        <>
          {/* Every state gets a badge, EXACT included — the frame says
              "현재 일정과 겹치지 않아요" there rather than leaving it blank. */}
          {match ? (
            <p
              role={notActive ? 'status' : undefined}
              className={
                match.state === 'SIMILAR'
                  ? `${styles.badge} ${styles.badgeRelated}`
                  : styles.badge
              }
            >
              {t(`candidates.match.${match.state}` as MessageKey)}
            </p>
          ) : null}

          {/* S07-10 `527:4732`: the date choice is a bottom sheet, not an
              inline list. The frame draws a dim + sheet with its own title,
              context row and 취소; an expanding card gave the dates no focus
              trap, no Escape and no accessible title of their own. */}
          <ScheduleCandidateSheet
            busy={add.isPending || etag === null}
            dayIndexOf={dayIndexOf}
            failed={matches.isError}
            loading={matches.isPending}
            locale={locale}
            match={match ?? null}
            onCancel={onToggle}
            onPick={(slot) => {
              schedule(slot.date, slot.suggestedTime);
            }}
            open={open}
            placeId={candidate.place.id}
            placeName={candidate.place.name}
            startDate={tripDates[0] ?? null}
            endDate={tripDates.at(-1) ?? null}
          />

          {/* Kept on the card rather than inside the sheet: the sheet closes on
              success, and a message that closes with it would never be read.
              A failure leaves the sheet open, so both are reachable. */}
          {add.isPending ? (
            <p className={styles.state} role="status">
              {t('candidates.adding')}
            </p>
          ) : null}

          {failed === null ? null : (
            <p className={styles.state} role="alert">
              {failed}
            </p>
          )}
        </>
      )}

      {/* actions (379:374): one row of text buttons. Removing is always
          available — dismissing a candidate never touches the schedule
          (invariant 1), so it stays offered for one already scheduled; the item
          it produced is removed separately (FE-305). */}
      <div className={styles.rowActions}>
        {scheduled ? null : (
          <button
            aria-expanded={notActive ? undefined : open}
            className={styles.primary}
            disabled={refreshing}
            onClick={notActive ? refresh : onToggle}
            ref={refreshButtonRef}
            type="button"
          >
            {notActive
              ? t('candidates.refresh')
              : open
                ? t('candidates.cancel')
                : t('candidates.add')}
          </button>
        )}
        <button
          // Visible text stays short as the frame has it; the accessible name
          // carries the place, because identical "제거" buttons in a list
          // tell a screen reader nothing about which one they act on.
          aria-label={t('candidates.removeNamed', { name: candidate.place.name })}
          className={styles.remove}
          disabled={remove.isPending}
          ref={afterScheduleRef}
          onClick={() => {
            remove.mutate({ candidateId: candidate.id });
          }}
          type="button"
        >
          {t('candidates.remove')}
        </button>
      </div>

      {remove.isError ? (
        <p className={styles.state} role="alert">
          {/* The server refuses to dismiss a candidate that is already on the
              itinerary (BA-034: "A scheduled candidate is removed through its
              trip item, not dismissed"), so a generic failure here would leave
              the user pressing a button that can never succeed. This names the
              place to remove it from instead. */}
          {isProblem(remove.error) && remove.error.code === 'LOCK_CONFLICT'
            ? t('candidates.removeScheduled')
            : t('candidates.removeFailed')}
        </p>
      ) : null}
    </article>
  );
}
