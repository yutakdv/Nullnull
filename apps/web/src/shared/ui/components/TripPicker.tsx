import { useEffect, useRef } from 'react';
import type { components } from '@nullnull/api-client';
import { formatTripPeriod } from '../../i18n/trip-period.js';
import styles from './TripPicker.module.css';
import { SheetGrab } from './SheetGrab.js';
import { restoreFocusTo } from './focus-restore.js';

// `Sheet / TripPicker` (C02), S03-C1 `399:658` / S06-1 `409:1595`.
//
// FR-CAN-01 opens this from `+`: the user chooses WHICH trip a place is saved
// into. Until now there was no choice — FeedScreen took `trips.data.items[0]`
// with a comment saying a real selector was FE-203's job, so a traveller with
// four trips could only ever collect into the first one and the other three
// were unreachable from the feed.
//
// A <dialog> rather than a hand-rolled overlay, for the same reasons
// MoveDaySheet gives: the platform supplies the focus trap, the backdrop, and
// inert-ing the page behind. Escape is handled in onKeyDown as well as
// onCancel because happy-dom never fires `cancel`, so the unit tests would
// otherwise never exercise the path a real user takes.
//
// This component only CHOOSES. It sends nothing: saving is the caller's
// mutation, so the sheet cannot become a second place where a candidate gets
// written (invariant 1 keeps SavedPost, TripCandidate and TripItem apart, and a
// picker that also saved would blur where that happens).

type TripSummary = components['schemas']['TripSummary'];

export interface TripPickerLabels {
  title: string;
  cancel: string;
  /** Shown while the trip list is still loading. */
  loading: string;
  /** Shown when the owner has no trips at all. */
  empty: string;
  /** The action offered alongside `empty`. */
  createTrip: string;
  /** Shown when the trip list could not be read. */
  error: string;
  retry: string;
}

export interface TripPickerProps {
  open: boolean;
  locale: string;
  /** The place being saved, named so the user knows what they are filing. */
  placeName: string;
  trips: readonly TripSummary[];
  /** Highlighted as the current target; null when nothing is chosen yet. */
  selectedTripId?: string | null;
  loading?: boolean;
  failed?: boolean;
  labels: TripPickerLabels;
  onPick: (tripId: string) => void;
  onCreateTrip?: () => void;
  onRetry?: () => void;
  onCancel: () => void;
}

export function TripPicker({
  open,
  locale,
  placeName,
  trips,
  selectedTripId = null,
  loading = false,
  failed = false,
  labels,
  onPick,
  onCreateTrip,
  onRetry,
  onCancel,
}: TripPickerProps) {
  const ref = useRef<HTMLDialogElement>(null);
  const restoreTo = useRef<HTMLElement | null>(null);
  const cancelRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    const dialog = ref.current;
    if (!dialog) return;
    if (open) {
      restoreTo.current = document.activeElement as HTMLElement | null;
      if (!dialog.open) dialog.showModal();
      // Focus the one control that exists in every state. Focusing a trip row
      // would land on nothing while the list is loading or empty.
      cancelRef.current?.focus();
    } else if (dialog.open) {
      dialog.close();
    }
  }, [open]);

  useEffect(() => {
    if (open) return;
    const target = restoreTo.current;
    restoreTo.current = null;
    // Not `isConnected` alone: picking a trip starts the save, which
    // re-renders this sheet's trigger into TripAddButton's `loading` state,
    // and that state is `disabled`. The node survives, so `isConnected` says
    // yes, but focusing a disabled button is a silent no-op and focus is left
    // on <body> — measured in a browser (#272 cause ④, the same one
    // ConfirmDialog hit). `restoreFocusTo` asks whether focus can actually
    // land, and falls back to <main> when it cannot.
    restoreFocusTo(target);
  }, [open]);

  return (
    <dialog
      aria-labelledby="trip-picker-title"
      className={styles.sheet}
      onCancel={(event) => {
        event.preventDefault();
        onCancel();
      }}
      onClick={(event) => {
        if (event.target === ref.current) onCancel();
      }}
      onKeyDown={(event) => {
        if (event.key !== 'Escape') return;
        event.preventDefault();
        event.stopPropagation();
        onCancel();
      }}
      ref={ref}
    >
      <div className={styles.panel}>
        <SheetGrab />

        <div className={styles.head}>
          <h2 className={styles.title} id="trip-picker-title">
            {labels.title}
          </h2>
          <button
            className={styles.cancel}
            onClick={onCancel}
            ref={cancelRef}
            type="button"
          >
            {labels.cancel}
          </button>
        </div>

        {/* What is being filed. Without it the sheet asks "which trip?" about
            nothing in particular, which is the same question for every card. */}
        <p className={styles.context}>
          <span className={styles.contextName}>{placeName}</span>
        </p>

        {loading ? (
          <p className={styles.state} role="status">
            {labels.loading}
          </p>
        ) : null}

        {failed ? (
          <p className={styles.state} role="alert">
            {labels.error}
            {onRetry ? (
              <button className={styles.secondary} onClick={onRetry} type="button">
                {labels.retry}
              </button>
            ) : null}
          </p>
        ) : null}

        {/* No trip is not an error: it is the state every new owner starts in,
            and the only action that changes it is offered rather than described. */}
        {!loading && !failed && trips.length === 0 ? (
          <p className={styles.state}>
            {labels.empty}
            {onCreateTrip ? (
              <button className={styles.secondary} onClick={onCreateTrip} type="button">
                {labels.createTrip}
              </button>
            ) : null}
          </p>
        ) : null}

        {trips.length > 0 ? (
          <ul className={styles.trips}>
            {trips.map((trip) => {
              const current = trip.id === selectedTripId;
              return (
                <li key={trip.id}>
                  <button
                    aria-current={current ? 'true' : undefined}
                    className={
                      current ? `${styles.trip} ${styles.tripHere}` : styles.trip
                    }
                    onClick={() => {
                      onPick(trip.id);
                    }}
                    type="button"
                  >
                    <span className={styles.tripText}>
                      <span className={styles.tripName}>{trip.title}</span>
                      <span className={styles.tripDates}>
                        {formatTripPeriod(trip.startDate, trip.endDate, locale, 'short')}
                      </span>
                    </span>
                    <span aria-hidden="true" className={styles.chevron}>
                      ›
                    </span>
                  </button>
                </li>
              );
            })}
          </ul>
        ) : null}
      </div>
    </dialog>
  );
}
