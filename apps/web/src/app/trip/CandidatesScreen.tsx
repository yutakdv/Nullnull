import { useState } from 'react';
import { useNavigate, useParams } from 'react-router';
import type { components } from '@nullnull/api-client';
import { useI18n } from '../../i18n/I18nProvider.js';
import type { MessageKey } from '../../i18n/messages.js';
import {
  isProblem,
  useAddTripItem,
  useCandidateMatches,
  useRemoveTripCandidate,
  useTrip,
  useTripCandidates,
} from '../../shared/api/index.js';
import { NavBar } from '../../shared/ui/components/index.js';
import styles from './CandidatesScreen.module.css';
import {
  blockedSlots,
  eligibleSlots,
  isScheduled,
  nextPosition,
  visibleCandidates,
} from './candidates.js';
import { formatTime } from './trip-view.js';

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
// The five match states are rendered as five different things. CHECKING is not
// "no dates", UNKNOWN is not "no dates", and NONE is the only one that says so.
//
// MOCK DATA: listTripCandidates, getCandidateTripMatches and addTripItem have
// no approved example (BA-034, BA-042).
//
// NOT BUILT, deliberately: the Figma card shows `ⓒ한국관광공사` under each place
// and a recommendation reason line. PlaceSummary carries neither a source nor a
// provenance, so both would be invented text — and an invented source is worse
// than none, because CMP-ATT-003 forbids implying an origin. Raised as FCR-031.

type TripCandidate = components['schemas']['TripCandidate'];

export function CandidatesScreen() {
  const { tripId } = useParams();
  const { t } = useI18n();
  const navigate = useNavigate();
  const trip = useTrip(tripId ?? null);
  const candidates = useTripCandidates(tripId ?? null);
  const [openId, setOpenId] = useState<string | null>(null);

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
      />

      <div className={styles.head}>
        {/* The count waits for the list. Rendering `items.length` while the
            request is in flight shows "0 saved places" to someone who has
            three, which reads as data loss rather than as loading. */}
        <h1 className={styles.title} id="candidates-heading">
          {candidates.isSuccess
            ? t('candidates.open', { count: items.length })
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
  candidate: TripCandidate;
  tripId: string | null;
  etag: string | null;
  open: boolean;
  onToggle: () => void;
}

function CandidateCardRow({ candidate, tripId, etag, open, onToggle }: RowProps) {
  const { locale, t } = useI18n();
  const scheduled = isScheduled(candidate);
  // Only fetched once the user opens this card: asking the server for slots on
  // every candidate at once would be a burst of requests for answers nobody
  // has looked at yet.
  const matches = useCandidateMatches(tripId, open && !scheduled ? candidate.id : null);
  const trip = useTrip(tripId);
  const add = useAddTripItem(tripId);
  const remove = useRemoveTripCandidate(tripId);
  const [failed, setFailed] = useState<string | null>(null);

  const match = matches.data;
  const eligible = match ? eligibleSlots(match) : [];
  const blocked = match ? blockedSlots(match) : [];

  function schedule(date: string, suggestedTime: string | null | undefined) {
    setFailed(null);
    add.mutate(
      {
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
          onToggle();
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
            the accessible name, so an empty alt avoids announcing it twice. */}
        {candidate.place.thumbnailUrl ? (
          <img
            alt=""
            className={styles.thumb}
            height={56}
            src={candidate.place.thumbnailUrl}
            width={56}
          />
        ) : (
          <span aria-hidden="true" className={styles.thumbEmpty} />
        )}
        <div className={styles.cardText}>
          <h2 className={styles.name}>{candidate.place.name}</h2>
          {candidate.place.address === null ||
          candidate.place.address === undefined ? null : (
            <p className={styles.meta}>{candidate.place.address}</p>
          )}
        </div>
      </div>

      {scheduled ? (
        // Already on the schedule: no add action, because adding it twice is
        // the bug this state exists to prevent.
        <p className={styles.badge}>{t('candidates.scheduled')}</p>
      ) : (
        <>
          <button
            aria-expanded={open}
            className={styles.primary}
            onClick={onToggle}
            type="button"
          >
            {open ? t('candidates.cancel') : t('candidates.add')}
          </button>

          {open ? (
            <div className={styles.slots}>
              {matches.isPending ? (
                <p className={styles.state} role="status">
                  {t('candidates.match.CHECKING')}
                </p>
              ) : null}

              {matches.isError ? (
                <p className={styles.state} role="alert">
                  {t('candidates.match.error')}
                </p>
              ) : null}

              {/* Each state says its own thing. CHECKING and UNKNOWN are not
                  "no dates available" — one is unfinished, the other is
                  unevidenced. */}
              {match && match.state !== 'EXACT' ? (
                <p className={styles.state} role="status">
                  {t(`candidates.match.${match.state}` as MessageKey)}
                </p>
              ) : null}

              {eligible.length > 0 ? (
                <ul className={styles.dates} aria-label={t('candidates.pickDate')}>
                  {eligible.map((slot) => (
                    <li key={slot.date}>
                      <button
                        className={styles.date}
                        disabled={add.isPending || etag === null}
                        onClick={() => {
                          schedule(slot.date, slot.suggestedTime);
                        }}
                        type="button"
                      >
                        {new Intl.DateTimeFormat(locale, {
                          month: 'numeric',
                          day: 'numeric',
                          weekday: 'short',
                          timeZone: 'UTC',
                        }).format(new Date(`${slot.date}T00:00:00Z`))}
                        {slot.suggestedTime
                          ? ` · ${formatTime(slot.suggestedTime, locale) ?? ''}`
                          : ''}
                      </button>
                    </li>
                  ))}
                </ul>
              ) : null}

              {/* Blocked dates are shown with their reason rather than hidden:
                  a date that simply is not there reads as a bug. */}
              {blocked.length > 0 ? (
                <ul className={styles.dates}>
                  {blocked.map((slot) => (
                    <li className={styles.blocked} key={slot.date}>
                      {new Intl.DateTimeFormat(locale, {
                        month: 'numeric',
                        day: 'numeric',
                        timeZone: 'UTC',
                      }).format(new Date(`${slot.date}T00:00:00Z`))}
                      {' · '}
                      {t('candidates.blocked')}
                    </li>
                  ))}
                </ul>
              ) : null}

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
            </div>
          ) : null}
        </>
      )}

      {/* FR-CAN-06. Dismissing a candidate never touches the schedule
          (invariant 1), so it stays available even for one already scheduled —
          the item it produced is removed separately (FE-305). */}
      <div className={styles.rowActions}>
        <button
          // Visible text stays short as the frame has it; the accessible name
          // carries the place, because three identical "제거" buttons in a list
          // tell a screen reader nothing about which one they act on.
          aria-label={t('candidates.removeNamed', { name: candidate.place.name })}
          className={styles.remove}
          disabled={remove.isPending}
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
          {t('candidates.removeFailed')}
        </p>
      ) : null}
    </article>
  );
}
