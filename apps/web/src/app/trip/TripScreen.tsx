import { useMemo, useRef, useState } from 'react';
import { Link, useParams } from 'react-router';
import type { components } from '@nullnull/api-client';
import { useI18n } from '../../i18n/I18nProvider.js';
import { isProblem, useTrip } from '../../shared/api/index.js';
import { Chip } from '../../shared/ui/components/index.js';
import { ItemMoveControls } from './ItemMoveControls.js';
import { LockRow } from './LockRow.js';
import { TripEditForm } from './TripEditForm.js';
import styles from './TripScreen.module.css';
import {
  daysUntil,
  formatTime,
  isEmptySchedule,
  itemCount,
  orderedItems,
  todayIn,
  tripLength,
  visibleDays,
} from './trip-view.js';

// Figma: S07-1 trip view `410:1738` (FR-TRP-01, FE-301).
//
// Read-only by design. Editing is FE-302, the candidate panel is FE-303 and
// the lock controls are FE-304; this slice is the day/item/candidate counts and
// the empty state. Locks are therefore *shown* but not operable — a lock that
// looked pressable and did nothing would be worse than one that reads as
// status.
//
// MOCK DATA: getTrip has no approved example yet, so the fixture behind it is a
// schema-valid guess (BA-030/BA-031). The screen calls the real generated
// client; when those land only the handler and the fixture go.
//
// Two things in the Figma frame are deliberately absent:
//
//   - The weather glyph beside each day (`10.4/일 ☀`). No weather field exists
//     anywhere in the contract and no provider is agreed, so rendering one
//     would be an invented value with no provenance (invariant 8). Raised as
//     FCR-030 rather than filled in, following FCR-029's precedent.
//   - Route-based distance/time text, already removed from the frame by
//     FCR-005 because P0 has no route provider.
//
// Crowd, by contrast, *is* in the contract on TripItem, with a required
// provenance, so it renders through CrowdLevel/DataAttribution which carry the
// source and the observation time.

type TripDetail = components['schemas']['TripDetail'];
type TripItem = TripDetail['days'][number]['items'][number];

/** "10.4/일" — the day heading, in the trip's own timezone. */
function dayLabel(date: string, locale: string, timeZone: string): string {
  return new Intl.DateTimeFormat(locale, {
    month: 'numeric',
    day: 'numeric',
    weekday: 'short',
    timeZone,
  }).format(new Date(`${date}T00:00:00Z`));
}

export function TripScreen() {
  const { tripId } = useParams();
  const { locale, t } = useI18n();
  const query = useTrip(tripId ?? null);
  const [selectedDay, setSelectedDay] = useState<string | null>(null);
  const [editing, setEditing] = useState(false);
  // The button that opened edit mode, so focus can come back to it (FR-TRP-03).
  const editButtonRef = useRef<HTMLButtonElement>(null);

  const trip = query.data?.trip;
  const days = trip?.days ?? [];
  const shown = useMemo(() => visibleDays(days, selectedDay), [days, selectedDay]);

  if (query.isPending) {
    return (
      <section className={styles.screen} aria-labelledby="trip-heading">
        <h1 className={styles.title} id="trip-heading">
          {t('trip.loading')}
        </h1>
        <p className={styles.state} role="status">
          {t('trip.loading')}
        </p>
      </section>
    );
  }

  if (query.isError || !trip) {
    // A missing trip and a failed request are different things and say so: one
    // is worth retrying, the other is not.
    const missing = isProblem(query.error) && query.error.code === 'NOT_FOUND';
    return (
      <section className={styles.screen} aria-labelledby="trip-heading">
        <h1 className={styles.title} id="trip-heading">
          {missing ? t('trip.notFound') : t('trip.error')}
        </h1>
        <p className={styles.state} role="alert">
          {missing ? t('trip.notFound') : t('trip.error')}
        </p>
        {missing ? null : (
          <button
            className={styles.retry}
            onClick={() => {
              void query.refetch();
            }}
            type="button"
          >
            {t('trip.retry')}
          </button>
        )}
      </section>
    );
  }

  const { nights, days: dayCount } = tripLength(trip.startDate, trip.endDate);
  const countdown = daysUntil(trip.startDate, todayIn(trip.timezone));
  const total = itemCount(days);

  return (
    <section className={styles.screen} aria-labelledby="trip-heading">
      <header className={styles.header}>
        <div className={styles.headRow}>
          <h1 className={styles.title} id="trip-heading">
            {trip.title}
          </h1>
          {/* Once the trip is under way there is no countdown to show, so it
              says which it is rather than falling back to D-0. */}
          <span className={styles.dday}>
            {countdown === null ? t('trip.started') : t('trip.dday', { days: countdown })}
          </span>
        </div>

        <p className={styles.meta}>
          <span>
            {new Intl.DateTimeFormat(locale, {
              dateStyle: 'medium',
              timeZone: trip.timezone,
            }).format(new Date(`${trip.startDate}T00:00:00Z`))}
            {' – '}
            {new Intl.DateTimeFormat(locale, {
              dateStyle: 'medium',
              timeZone: trip.timezone,
            }).format(new Date(`${trip.endDate}T00:00:00Z`))}
          </span>
          <span>{t('trip.length', { nights, days: dayCount })}</span>
          {/* candidateCount is the contract's own field, not a length taken
              from the `candidates` array: that array is a page of the
              candidates, so counting it would under-report the total. */}
          {/* The count is the way into the candidate panel (S07-8), so it is a
              link rather than a label. */}
          {/* 521:3989: the frame's mode bar pairs "+ 장소 추가" with the saved
              places count. */}
          <Link className={styles.addPlace} to={`/trip/${trip.id}/add-place`}>
            {t('trip.addPlace')}
          </Link>
          <Link className={styles.candidates} to={`/trip/${trip.id}/candidates`}>
            {t('trip.candidates', { count: trip.candidateCount })}
          </Link>
        </p>

        {/* 462:3401: a hairline between the meta row and the actions. */}
        <span className={styles.divider} />

        <p className={styles.actions}>
          {/* Optimization is FE-501. Inert text with a `준비 중` badge rather
              than a disabled button, which would still invite a press. */}
          {/* The label and its `준비 중` marker stack rather than sitting side by
              side: together they are wider than half of 360px, and the frame
              draws this as one line of text in a pill. */}
          <span className={styles.actionLabel}>
            <span className={styles.actionText}>{t('trip.optimize')}</span>
            <span className={styles.badge}>{t('trip.comingSoon')}</span>
          </span>
          {editing ? null : (
            <button
              className={styles.action}
              onClick={() => {
                setEditing(true);
              }}
              ref={editButtonRef}
              type="button"
            >
              {t('trip.editStart')}
            </button>
          )}
        </p>
      </header>

      {editing ? (
        <TripEditForm
          etag={query.data.etag}
          onClose={() => {
            setEditing(false);
            // Focus returns to the control that opened the form, after the
            // button is back in the tree.
            queueMicrotask(() => editButtonRef.current?.focus());
          }}
          trip={trip}
        />
      ) : null}

      <nav aria-label={t('trip.allDays')} className={styles.dayNav}>
        <ul className={styles.dayChips}>
          <li>
            <Chip
              label={t('trip.allDays')}
              selected={selectedDay === null}
              size="sm"
              onClick={() => {
                setSelectedDay(null);
              }}
            />
          </li>
          {days.map((day, index) => (
            <li key={day.date}>
              <Chip
                label={t('trip.day', { n: index + 1 })}
                selected={selectedDay === day.date}
                size="sm"
                onClick={() => {
                  setSelectedDay(day.date);
                }}
              />
            </li>
          ))}
        </ul>
      </nav>

      {/* The whole trip is empty: distinct from a single day being empty, and
          it explains what turns a saved place into a scheduled one. */}
      {isEmptySchedule(days) ? (
        <div className={styles.empty}>
          <p className={styles.emptyTitle}>{t('trip.empty')}</p>
          <p className={styles.state}>{t('trip.emptyNote')}</p>
        </div>
      ) : (
        <p className={styles.state} role="status">
          {t('trip.itemCount', { count: total })}
        </p>
      )}

      {shown.map((day, index) => (
        <section
          aria-labelledby={`day-${day.date}`}
          className={styles.day}
          key={day.date}
        >
          <h2 className={styles.dayTitle} id={`day-${day.date}`}>
            {t('trip.day', {
              n: (selectedDay === null ? index : days.indexOf(day)) + 1,
            })}
            <span className={styles.dayDate}>
              {dayLabel(day.date, locale, trip.timezone)}
            </span>
          </h2>

          {day.items.length === 0 ? (
            <p className={styles.state}>{t('trip.dayEmpty')}</p>
          ) : (
            <ul className={styles.items}>
              {orderedItems(day).map((item) => (
                <li key={item.id}>
                  <TripItemRow
                    days={days}
                    etag={query.data.etag}
                    item={item}
                    tripId={tripId ?? null}
                  />
                </li>
              ))}
            </ul>
          )}
        </section>
      ))}
    </section>
  );
}

/**
 * One scheduled item.
 *
 * Local to this screen rather than the shared TripItemCard (C38): that
 * component offers operable lock controls, which belong to FE-304. Showing
 * pressable locks that do nothing would be a worse lie than showing them as
 * state.
 */
function TripItemRow({
  item,
  days,
  tripId,
  etag,
}: {
  item: TripItem;
  days: readonly TripDetail['days'][number][];
  tripId: string | null;
  etag: string | null;
}) {
  const { locale, t } = useI18n();

  return (
    <article className={styles.item}>
      <div className={styles.itemHead}>
        <h3 className={styles.itemName}>{item.place.name}</h3>
        <span className={styles.itemTime}>
          {formatTime(item.startTime, locale) ?? t('trip.timeUnset')}
        </span>
      </div>

      <p className={styles.itemMeta}>
        {/* Address, not categoryCode. The contract types categoryCode as a free
            string with no enum and no display name, so there is nothing to
            translate it against — rendering it shows the user machine text like
            "ATTRACTION". Asked BE for the vocabulary and its labels on #34
            (BA-022); until then the row shows only what is already human. */}
        {item.place.address === null || item.place.address === undefined ? null : (
          <span>{item.place.address}</span>
        )}
        {item.durationMinutes === null || item.durationMinutes === undefined ? null : (
          <span>{formatDuration(item.durationMinutes, t)}</span>
        )}
      </p>

      {/* Operable as of FE-304: each lock releases on its own request, and the
          two with confirm frames ask first. */}
      <LockRow etag={etag} item={item} tripId={tripId} />

      {/* FR-ITM-03 / FR-ITM-05: reorder within the day and move to another,
          each one atomic reorder request. */}
      <ItemMoveControls days={days} etag={etag} item={item} tripId={tripId} />
    </article>
  );
}

function formatDuration(
  minutes: number,
  t: (
    key: 'trip.duration' | 'trip.durationMinutes' | 'trip.durationHoursMinutes',
    values?: Record<string, string | number>,
  ) => string,
): string {
  const hours = Math.floor(minutes / 60);
  const rest = minutes % 60;
  if (hours === 0) return t('trip.durationMinutes', { minutes: rest });
  if (rest === 0) return t('trip.duration', { hours });
  return t('trip.durationHoursMinutes', { hours, minutes: rest });
}
