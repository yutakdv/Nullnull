import { useEffect, useMemo, useRef, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import type { components } from '@nullnull/api-client';
import { useI18n } from '../../i18n/I18nProvider.js';
import { isProblem, useTrip, useUpdateTrip } from '../../shared/api/index.js';
import { Chip, CrowdLevel, DataAttribution } from '../../shared/ui/components/index.js';
import {
  IconDateLock,
  IconCheck,
  IconClose,
  IconEdit,
  IconPinVisitFilled,
  IconReservation,
  IconTimeLock,
} from '../../shared/ui/icons/index.js';
import { ItemMoveControls } from './ItemMoveControls.js';
import { RemoveItemControl } from './RemoveItemControl.js';
import { LockRow } from './LockRow.js';
import { TripEditForm } from './TripEditForm.js';
import { draftError, draftFrom, toPatch } from './trip-edit.js';
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
// Crowd, by contrast, *is* in the contract on TripItem with a required
// provenance. The place's own credit renders through DataAttribution below;
// the crowd reading itself is not shown on this row yet, and this comment used
// to claim both reached the screen when neither did.

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

/** Date-only contract values formatted without letting a device timezone move them. */
function tripDateRange(startDate: string, endDate: string, locale: string): string {
  if (locale === 'ko-KR') {
    return `${startDate.replaceAll('-', '.')} – ${endDate.slice(5).replace('-', '.')}`;
  }
  const format = new Intl.DateTimeFormat(locale, {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    timeZone: 'UTC',
  });
  return `${format.format(new Date(`${startDate}T00:00:00Z`))} – ${format.format(
    new Date(`${endDate}T00:00:00Z`),
  )}`;
}

export interface TripScreenProps {
  mode?: 'view' | 'edit' | 'details';
  surface?: 'default' | 'subtle';
}

export function TripScreen({ mode = 'view', surface = 'default' }: TripScreenProps) {
  const { tripId } = useParams();
  const navigate = useNavigate();
  const { locale, t } = useI18n();
  const query = useTrip(tripId ?? null);
  const updateTitle = useUpdateTrip(tripId ?? null);
  const [selectedDay, setSelectedDay] = useState<string | null>(null);
  const [editingTitle, setEditingTitle] = useState(false);
  const [titleDraft, setTitleDraft] = useState('');
  const [titleError, setTitleError] = useState<string | null>(null);
  const titleEditButton = useRef<HTMLButtonElement>(null);
  const restoreTitleFocus = useRef(false);
  const editing = mode === 'edit';
  // The outcome of a removal, held HERE rather than in the control that sent
  // it: a successful remove unmounts the row, so a message owned by the row
  // would be destroyed by the action it reports.
  const [removed, setRemoved] = useState<string | null>(null);
  const trip = query.data?.trip;
  const days = trip?.days ?? [];
  const shown = useMemo(() => visibleDays(days, selectedDay), [days, selectedDay]);

  useEffect(() => {
    if (editingTitle || !restoreTitleFocus.current) return;
    restoreTitleFocus.current = false;
    titleEditButton.current?.focus();
  }, [editingTitle]);

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
  const loadedTrip = trip;
  const loadedEtag = query.data?.etag ?? null;
  function beginTitleEdit() {
    updateTitle.reset();
    setTitleDraft(loadedTrip.title);
    setTitleError(null);
    setEditingTitle(true);
  }

  function cancelTitleEdit() {
    updateTitle.reset();
    setTitleError(null);
    restoreTitleFocus.current = true;
    setEditingTitle(false);
  }

  function saveTitle() {
    const draft = { ...draftFrom(loadedTrip), title: titleDraft };
    const localError = draftError(draft);
    if (localError === 'title-empty' || localError === 'title-too-long') {
      setTitleError(t(`trip.error.${localError}`));
      return;
    }
    const patch = toPatch(draft, loadedTrip);
    if (patch === null) {
      cancelTitleEdit();
      return;
    }
    updateTitle.mutate(
      { patch, etag: loadedEtag },
      {
        onSuccess: () => {
          setTitleError(null);
          restoreTitleFocus.current = true;
          setEditingTitle(false);
        },
        onError: () => {
          setTitleError(t('trip.titleSaveFailed'));
        },
      },
    );
  }

  return (
    <section
      className={`${styles.screen} ${editing ? styles.editing : ''} ${
        surface === 'subtle' ? styles.subtleSurface : ''
      }`}
      aria-labelledby="trip-heading"
    >
      <header className={styles.header}>
        <div className={styles.headRow}>
          <h1 className={editingTitle ? styles.srOnly : styles.title} id="trip-heading">
            {trip.title}
          </h1>
          {editingTitle ? (
            <div className={styles.titleEditor}>
              <label className={styles.srOnly} htmlFor="trip-title-inline">
                {t('trip.field.title')}
              </label>
              <input
                aria-describedby={titleError ? 'trip-title-inline-error' : undefined}
                aria-invalid={titleError ? true : undefined}
                autoFocus
                className={styles.titleInput}
                id="trip-title-inline"
                maxLength={100}
                onChange={(event) => {
                  setTitleDraft(event.target.value);
                  setTitleError(null);
                }}
                onKeyDown={(event) => {
                  if (event.key === 'Enter') {
                    event.preventDefault();
                    saveTitle();
                  }
                  if (event.key === 'Escape') cancelTitleEdit();
                }}
                value={titleDraft}
              />
              <button
                aria-label={t('trip.editSave')}
                className={styles.titleIconButton}
                disabled={updateTitle.isPending}
                onClick={saveTitle}
                type="button"
              >
                <IconCheck size={18} />
              </button>
              <button
                aria-label={t('trip.editCancel')}
                className={styles.titleIconButton}
                disabled={updateTitle.isPending}
                onClick={cancelTitleEdit}
                type="button"
              >
                <IconClose size={18} />
              </button>
            </div>
          ) : mode === 'view' ? (
            <button
              aria-label={t('trip.titleEdit')}
              className={styles.titleIconButton}
              onClick={beginTitleEdit}
              ref={titleEditButton}
              type="button"
            >
              <IconEdit size={18} />
            </button>
          ) : null}
          {/* Once the trip is under way there is no countdown to show, so it
              says which it is rather than falling back to D-0. */}
          <span className={styles.dday}>
            {countdown === null ? t('trip.started') : t('trip.dday', { days: countdown })}
          </span>
        </div>
        {titleError ? (
          <p className={styles.titleError} id="trip-title-inline-error" role="alert">
            {titleError}
          </p>
        ) : null}

        <div className={styles.meta}>
          <p className={styles.metaText}>
            <span>{tripDateRange(trip.startDate, trip.endDate, locale)}</span>
            <span aria-hidden="true"> · </span>
            <span>{t('trip.length', { nights, days: dayCount })}</span>
          </p>
          {/* candidateCount is the contract's own field, not a length taken
              from the `candidates` array: that array is a page of the
              candidates, so counting it would under-report the total. */}
          {/* The count is the way into the candidate panel (S07-8), so it is a
              link rather than a label. */}
          {editing ? null : (
            <Link className={styles.candidates} to={`/trip/${trip.id}/candidates`}>
              {t('trip.candidates', { count: trip.candidateCount })}
            </Link>
          )}
        </div>

        {/* 462:3401: a hairline between the meta row and the actions. */}
        <span className={styles.divider} />

        {editing ? (
          <div className={styles.modeBar}>
            <strong className={styles.modeLabel}>{t('trip.editMode')}</strong>
            <span className={styles.modeSpacer} />
            <Link className={styles.addPlace} to={`/trip/${trip.id}/add-place`}>
              <span aria-hidden="true">+</span> {t('trip.addPlace')}
            </Link>
            <Link
              className={`${styles.candidates} ${styles.candidatesEditing}`}
              to={`/trip/${trip.id}/candidates`}
            >
              {t('trip.candidates', { count: trip.candidateCount })}
            </Link>
          </div>
        ) : (
          <p className={styles.actions}>
            {/* Operable as of FE-501: this opens the setup where the user picks
              the stop to change. It was inert `준비 중` text until then. */}
            <Link
              className={`${styles.action} ${styles.optimize}`}
              to={`/trip/${trip.id}/optimize`}
            >
              {t('trip.optimize')}
            </Link>
            <button
              className={styles.action}
              onClick={() => {
                void navigate(`/trip/${trip.id}/edit`);
              }}
              type="button"
            >
              {t('trip.editStart')}
            </button>
          </p>
        )}
      </header>

      {mode === 'details' ? (
        <TripEditForm
          etag={query.data.etag}
          onClose={() => {
            void navigate(`/trip/${trip.id}`);
          }}
          trip={trip}
        />
      ) : null}

      {/* data-scrolls-x: this row scrolls itself rather than widening the
          page, so its chips reach past the viewport edge on purpose. The
          reflow check in e2e/responsive.spec.ts reads the marker to tell that
          apart from a screen that genuinely overflows. */}
      {/* The removal outcome. One region for the screen, outside every row, so
          it survives the row that triggered it. */}
      {removed === null ? null : (
        <p aria-live="polite" className={styles.state} role="status">
          {removed}
        </p>
      )}

      <nav aria-label={t('trip.allDays')} className={styles.dayNav} data-scrolls-x>
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
        <p className={`${styles.state} ${styles.srOnly}`} role="status">
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
            <div className={styles.emptyDay}>
              <p className={styles.state}>{t('trip.dayEmpty')}</p>
            </div>
          ) : (
            <ul className={styles.items}>
              {orderedItems(day).map((item) => (
                <li key={item.id}>
                  <TripItemRow
                    days={days}
                    editing={editing}
                    etag={query.data.etag}
                    item={item}
                    onAnnounce={setRemoved}
                    tripId={tripId ?? null}
                  />
                </li>
              ))}
            </ul>
          )}
        </section>
      ))}

      {editing ? (
        <div className={styles.editActions}>
          <button
            className={styles.editCancel}
            onClick={() => {
              void navigate(`/trip/${trip.id}`);
            }}
            type="button"
          >
            {t('trip.editCancel')}
          </button>
          <button
            className={styles.editSave}
            onClick={() => {
              void navigate(`/trip/${trip.id}`);
            }}
            type="button"
          >
            {t('trip.editSave')}
          </button>
        </div>
      ) : null}
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
  editing,
  tripId,
  etag,
  onAnnounce,
}: {
  item: TripItem;
  days: readonly TripDetail['days'][number][];
  editing: boolean;
  tripId: string | null;
  etag: string | null;
  onAnnounce: (message: string) => void;
}) {
  const { locale, t } = useI18n();
  const mustVisit = item.constraints.some(
    (constraint) => constraint.type === 'MUST_VISIT',
  );
  const viewMeta = [item.place.categoryName, item.place.regionName].filter(
    (value): value is string => Boolean(value),
  );
  if (item.durationMinutes !== null && item.durationMinutes !== undefined) {
    viewMeta.push(formatDuration(item.durationMinutes, t));
  }

  if (editing) {
    const editMeta = item.place.address ? [item.place.address] : [];
    if (item.durationMinutes !== null && item.durationMinutes !== undefined) {
      editMeta.push(formatDuration(item.durationMinutes, t));
    }

    return (
      <article className={styles.item}>
        <div className={styles.itemHead}>
          <h3 className={styles.itemName}>{item.place.name}</h3>
          <span className={styles.itemTime}>
            {formatTime(item.startTime, locale) ?? t('trip.timeUnset')}
          </span>
        </div>

        <p className={styles.itemMeta}>
          {editMeta.map((value) => (
            <span key={value}>{value}</span>
          ))}
        </p>

        {item.place.sourceAttribution ? (
          <DataAttribution compact provenance={item.place.sourceAttribution} />
        ) : null}

        <LockRow etag={etag} item={item} tripId={tripId} />
        <ItemMoveControls days={days} etag={etag} item={item} tripId={tripId} />
        <RemoveItemControl
          etag={etag}
          item={item}
          onAnnounce={onAnnounce}
          tripId={tripId}
        />
      </article>
    );
  }

  return (
    <article className={styles.item}>
      <div className={styles.itemHead}>
        {mustVisit ? (
          <span className={styles.mustVisit}>
            <IconPinVisitFilled size={15} />
            <span className={styles.srOnly}>{t('trip.lock.MUST_VISIT')}</span>
          </span>
        ) : null}
        <h3 className={styles.itemName}>{item.place.name}</h3>
        <span className={styles.itemTime}>
          {formatTime(item.startTime, locale) ?? t('trip.timeUnset')}
        </span>
        {tripId ? (
          <Link
            aria-label={t('trip.item.actions', { name: item.place.name })}
            className={styles.itemMenuLink}
            to={`/trip/${tripId}/edit`}
          >
            <span aria-hidden="true">•••</span>
          </Link>
        ) : null}
      </div>

      <p className={styles.itemMeta}>
        {viewMeta.map((value, index) => (
          <span key={`${value}:${String(index)}`}>
            {index > 0 ? <span aria-hidden="true"> · </span> : null}
            {value}
          </span>
        ))}
      </p>

      {item.crowd ? (
        <CrowdLevel
          crowd={item.crowd}
          levelLabel={t('crowd.level', {
            level: item.crowd.ordinalLevel ?? '',
            steps: 5,
          })}
        />
      ) : null}

      {/* CMP-ATT-001: a KTO-sourced place carries its credit wherever it
          appears, shown verbatim (CMP-ATT-003). This row rendered the place
          name and address with none — the header comment above claimed the
          credit reached the screen through CrowdLevel/DataAttribution, and
          neither was imported. It went unnoticed because the trip fixture
          carried sourceAttribution: null at the time; BA-030 maps places
          through the shared catalog projection, so the real response populates
          it, and #281 filled the fixtures in to match. */}
      {item.crowd ? (
        <DataAttribution compact provenance={item.crowd.provenance} />
      ) : item.place.sourceAttribution ? (
        <DataAttribution compact provenance={item.place.sourceAttribution} />
      ) : null}

      <ul aria-label={t('trip.locks')} className={styles.locks}>
        {item.constraints
          .filter((constraint) => constraint.type !== 'MUST_VISIT')
          .map((constraint) => (
            <li
              className={styles.lock}
              data-constraint={constraint.type}
              key={constraint.type}
            >
              {constraint.type === 'DATE' ? <IconDateLock size={12} /> : null}
              {constraint.type === 'TIME' ? <IconTimeLock size={12} /> : null}
              {constraint.type === 'RESERVATION' ? <IconReservation size={12} /> : null}
              {t(`trip.lock.${constraint.type}`)}
            </li>
          ))}
      </ul>
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
