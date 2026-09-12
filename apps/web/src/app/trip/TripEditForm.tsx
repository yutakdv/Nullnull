import { useEffect, useRef, useState } from 'react';
import type { components } from '@nullnull/api-client';
import { useI18n } from '../../i18n/I18nProvider.js';
import type { MessageKey } from '../../i18n/messages.js';
import { isProblem, useTrip, useUpdateTrip } from '../../shared/api/index.js';
import { ConfirmDialog } from '../../shared/ui/components/index.js';
import styles from './TripEditForm.module.css';
import { formatDate } from './trip-view.js';
import {
  MAX_TITLE_LENGTH,
  type TripDraft,
  draftError,
  draftFrom,
  fieldToInput,
  isDirty,
  isShrink,
  outOfRangeItems,
  toPatch,
} from './trip-edit.js';

// S07-2 edit `411:1837` and the discard dialog `413:2020`
// (FR-TRP-02, FR-TRP-03, FR-TRP-05).
//
// The rule the whole component serves: a user's typing is never lost without
// them saying so. That means three things —
//
//   - leaving with unsaved changes asks first (FR-TRP-03);
//   - a save conflict keeps the draft and offers both ways out, rather than
//     reloading over the top of it;
//   - a 422 shows the server's message against the field it names and leaves
//     everything else as typed.
//
// Scope: metadata only. updateTrip patches title/dates/timezone/planningLevel;
// adding, moving and removing items are FR-ITM-* and belong to FE-305, so the
// edit frame's item controls are not built here.

type TripDetail = components['schemas']['TripDetail'];
type PlanningLevel = components['schemas']['PlanningLevel'];
type FieldError = components['schemas']['FieldError'];

const PLANNING_LEVELS: PlanningLevel[] = ['NOTHING', 'MUST_VISIT_ONLY', 'MOSTLY_PLANNED'];

export interface TripEditFormProps {
  trip: TripDetail;
  etag: string | null;
  onClose: () => void;
}

/**
 * A server-sent message for one field.
 *
 * Every input gets one. Rendering the message only beside the field the form
 * happened to anticipate is how a rejection for another field disappears — the
 * save fails and the screen says nothing.
 */
function FieldMessage({ id, message }: { id: string; message: string | null }) {
  if (message === null) return null;
  return (
    <span className={styles.error} id={id} role="alert">
      {message}
    </span>
  );
}

export function TripEditForm({ trip, etag, onClose }: TripEditFormProps) {
  const { locale, t } = useI18n();
  const [draft, setDraft] = useState<TripDraft>(() => draftFrom(trip));
  const [confirming, setConfirming] = useState(false);
  const [conflict, setConflict] = useState(false);
  // Same query key as the screen's, so this is the one cached trip. The form
  // needs it to refetch after a conflict rather than only to read.
  const query = useTrip(trip.id);
  const [fieldErrors, setFieldErrors] = useState<FieldError[]>([]);
  const [saved, setSaved] = useState(false);
  const [datesUndone, setDatesUndone] = useState(false);
  const update = useUpdateTrip(trip.id);

  const titleRef = useRef<HTMLInputElement>(null);
  const startRef = useRef<HTMLInputElement>(null);
  const endRef = useRef<HTMLInputElement>(null);

  const dirty = isDirty(draft, trip);
  const localError = draftError(draft);

  // Only for a shrink: widening the range strands nothing, and listing items
  // that sit outside a range the user is growing would be noise.
  const stranded = isShrink(draft, trip) ? outOfRangeItems(trip, draft) : [];

  // Warns on a real browser close/refresh too, not just an in-app exit. The
  // browser owns this dialog; the in-app one below covers navigation.
  useEffect(() => {
    if (!dirty) return;
    const warn = (event: BeforeUnloadEvent) => {
      event.preventDefault();
    };
    window.addEventListener('beforeunload', warn);
    return () => {
      window.removeEventListener('beforeunload', warn);
    };
  }, [dirty]);

  function set<K extends keyof TripDraft>(key: K, value: TripDraft[K]) {
    setSaved(false);
    setDraft((current) => ({ ...current, [key]: value }));
  }

  /** Leaving: asks first when there is something to lose. */
  function requestClose() {
    if (dirty) {
      setConfirming(true);
      return;
    }
    onClose();
  }

  function onSave() {
    setConflict(false);
    setFieldErrors([]);
    const patch = toPatch(draft, trip);
    // Nothing changed: an empty merge-patch is minProperties:1 and would 400.
    if (patch === null) {
      onClose();
      return;
    }
    update.mutate(
      { patch, etag },
      {
        onSuccess: () => {
          setSaved(true);
          onClose();
        },
        onError: (error) => {
          if (!isProblem(error)) return;
          if (error.code === 'TRIP_CHANGED') {
            setConflict(true);
            return;
          }
          if (error.code === 'VALIDATION_FAILED') {
            // The server names the field; the draft is untouched so the user
            // sees exactly what they typed next to what was wrong with it.
            const errors = error.fieldErrors ?? [];
            setFieldErrors(errors);
            const first = errors.find((e) => fieldToInput(e.field) !== null);
            const input = first ? fieldToInput(first.field) : null;
            if (input === 'title') titleRef.current?.focus();
            if (input === 'startDate') startRef.current?.focus();
            if (input === 'endDate') endRef.current?.focus();
          }
        },
      },
    );
  }

  /** The server's message for one field, if it named it. */
  function errorFor(field: keyof TripDraft): string | null {
    const match = fieldErrors.find((e) => fieldToInput(e.field) === field);
    return match?.message ?? null;
  }

  // Errors the server sent for fields this form does not render. Still shown:
  // dropping them would leave the user with a failed save and no reason.
  const unmapped = fieldErrors.filter((e) => fieldToInput(e.field) === null);

  return (
    <form
      className={styles.form}
      onSubmit={(event) => {
        event.preventDefault();
        onSave();
      }}
    >
      <p className={styles.mode}>{t('trip.editMode')}</p>

      <p className={styles.field}>
        <label className={styles.label} htmlFor="trip-title">
          {t('trip.field.title')}
        </label>
        <input
          aria-describedby={errorFor('title') ? 'trip-title-error' : undefined}
          aria-invalid={errorFor('title') !== null || localError?.startsWith('title')}
          className={styles.input}
          id="trip-title"
          maxLength={MAX_TITLE_LENGTH}
          onChange={(event) => {
            set('title', event.target.value);
          }}
          ref={titleRef}
          type="text"
          value={draft.title}
        />
        <FieldMessage id="trip-title-error" message={errorFor('title')} />
      </p>

      <p className={styles.field}>
        <label className={styles.label} htmlFor="trip-start">
          {t('trip.field.startDate')}
        </label>
        <input
          aria-describedby={errorFor('startDate') ? 'trip-start-error' : undefined}
          aria-invalid={errorFor('startDate') !== null}
          className={styles.input}
          id="trip-start"
          onChange={(event) => {
            set('startDate', event.target.value);
          }}
          ref={startRef}
          type="date"
          value={draft.startDate}
        />
        <FieldMessage id="trip-start-error" message={errorFor('startDate')} />
      </p>

      <p className={styles.field}>
        <label className={styles.label} htmlFor="trip-end">
          {t('trip.field.endDate')}
        </label>
        <input
          aria-describedby={errorFor('endDate') ? 'trip-end-error' : undefined}
          aria-invalid={errorFor('endDate') !== null}
          className={styles.input}
          id="trip-end"
          onChange={(event) => {
            set('endDate', event.target.value);
          }}
          ref={endRef}
          type="date"
          value={draft.endDate}
        />
        <FieldMessage id="trip-end-error" message={errorFor('endDate')} />
      </p>

      {/* FE-306 / FCR-032: what a narrowed range would leave outside.
          Deliberately NOT part of the save-disabled expression below — the
          contract's rule is predictable from data we hold, but `trip` is a
          cached query and may be stale, so the server stays the judge and the
          user keeps the right to try (FR-TRP-05: no implicit deletion). */}
      {stranded.length > 0 ? (
        <aside aria-labelledby="trip-range-impact" className={styles.impact}>
          <p className={styles.impactHead}>
            <span className={styles.impactTitle} id="trip-range-impact">
              {t('trip.range.impactTitle')}
            </span>
            <span className={styles.impactCount}>
              {t('trip.range.impactCount', { count: stranded.length })}
            </span>
          </p>
          <p className={styles.impactNote}>{t('trip.range.impactNote')}</p>
          <ul className={styles.impactList}>
            {stranded.map((item) => (
              <li className={styles.impactItem} key={item.itemId}>
                <span className={styles.impactName}>{item.placeName}</span>
                {/* The item's own date, when that is what puts it outside.
                    Null when only a lock's date does, so naming it would
                    point at a date that is actually inside the range. */}
                {item.date ? (
                  <span className={styles.impactDate}>
                    {formatDate(item.date, locale)}
                  </span>
                ) : null}
                {item.blockingLocks.map((lock) => (
                  <span className={styles.impactLock} key={lock}>
                    {lock === 'DATE'
                      ? t('trip.range.lockDate')
                      : t('trip.range.lockReservation')}
                  </span>
                ))}
              </li>
            ))}
          </ul>
          {/* FE-306-T1's 취소: restores only the dates, leaving an edited
              title alone. The whole-form discard is a different control. */}
          <button
            className={styles.impactUndo}
            onClick={() => {
              setDraft((current) => ({
                ...current,
                startDate: trip.startDate,
                endDate: trip.endDate,
              }));
              setDatesUndone(true);
            }}
            type="button"
          >
            {t('trip.range.undo')}
          </button>
        </aside>
      ) : null}
      {/* Announced outside the block, because restoring the dates removes the
          block itself — a message inside it would vanish before it was read. */}
      <p aria-live="polite" className={styles.srOnly}>
        {datesUndone ? t('trip.range.undone') : ''}
      </p>

      <p className={styles.field}>
        <label className={styles.label} htmlFor="trip-planning">
          {t('trip.field.planningLevel')}
        </label>
        <select
          className={styles.input}
          id="trip-planning"
          onChange={(event) => {
            set('planningLevel', event.target.value as PlanningLevel);
          }}
          value={draft.planningLevel}
        >
          {PLANNING_LEVELS.map((level) => (
            <option key={level} value={level}>
              {t(`trip.planning.${level}` as MessageKey)}
            </option>
          ))}
        </select>
        <FieldMessage id="trip-planning-error" message={errorFor('planningLevel')} />
      </p>

      {/* Local validation: only the limits the contract states. The date-range
          shrink rule depends on server state, so it is not predicted here —
          the 422 is surfaced instead (FR-TRP-05: no implicit deletion). */}
      {localError ? (
        <p className={styles.error} role="alert">
          {t(`trip.error.${localError}` as MessageKey)}
        </p>
      ) : null}

      {unmapped.map((error) => (
        <p className={styles.error} key={`${error.field}:${error.code}`} role="alert">
          {error.message}
        </p>
      ))}

      {conflict ? (
        <div className={styles.conflict} role="alert">
          <p className={styles.conflictText}>{t('trip.conflict')}</p>
          <div className={styles.conflictActions}>
            <button
              className={styles.secondary}
              onClick={() => {
                // Take the server's version and drop the draft — but only
                // because the user asked.
                setConflict(false);
                onClose();
              }}
              type="button"
            >
              {t('trip.conflict.discard')}
            </button>
            <button
              className={styles.secondary}
              onClick={() => {
                // Keep typing against the refreshed trip. The refetch is done
                // HERE: nothing else triggers one, so the comment that used to
                // say "the parent refetches" described something no code did —
                // the stale ETag stayed, and every later save failed the same
                // way with the same message.
                setConflict(false);
                void query.refetch();
              }}
              type="button"
            >
              {t('trip.conflict.reload')}
            </button>
          </div>
        </div>
      ) : null}

      {update.isError && !conflict && fieldErrors.length === 0 ? (
        <p className={styles.error} role="alert">
          {t('trip.saveFailed')}
        </p>
      ) : null}

      {saved ? (
        <p className={styles.state} role="status">
          {t('trip.editSaved')}
        </p>
      ) : null}

      <div className={styles.actions}>
        <button className={styles.secondary} onClick={requestClose} type="button">
          {t('trip.editCancel')}
        </button>
        <button
          className={styles.primary}
          disabled={update.isPending || localError !== null || etag === null}
          type="submit"
        >
          {update.isPending ? t('trip.editSaving') : t('trip.editSave')}
        </button>
      </div>

      <ConfirmDialog
        body={t('trip.discard.body')}
        cancelLabel={t('trip.discard.keep')}
        confirmLabel={t('trip.discard.leave')}
        destructive
        onCancel={() => {
          setConfirming(false);
        }}
        onConfirm={() => {
          setConfirming(false);
          onClose();
        }}
        open={confirming}
        title={t('trip.discard.title')}
      />
    </form>
  );
}
