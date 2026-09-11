import { useState } from 'react';
import type { components } from '@nullnull/api-client';
import { useI18n } from '../../i18n/I18nProvider.js';
import type { MessageKey } from '../../i18n/messages.js';
import {
  isProblem,
  useRemoveItemConstraint,
  useSetItemConstraint,
} from '../../shared/api/index.js';
import { ConfirmDialog } from '../../shared/ui/components/index.js';
import styles from './LockRow.module.css';
import { activeLocks, needsConfirm, remainingLocks, settableLocks } from './locks.js';

// S07-7 `413:2081`, S07-10b `527:3876` (FR-CON-02, FR-CON-05, FE-304).
//
// Invariant 7: the four locks are independent and none is released
// automatically. Three things follow, and all three are the point of this
// component rather than incidental to it:
//
//   - One request per lock. The contract's endpoint is
//     DELETE /constraints/{constraintType}, so there is no way to clear two at
//     once and no code here that tries.
//   - Every release is an explicit press. Nothing releases as a side effect of
//     another edit, an optimization, or a date change.
//   - The confirm names what stays. "정말 해제할까요?" tells the user nothing;
//     the frames state the consequence, and the surviving locks are computed
//     from the item so the sentence cannot drift out of date.
//
// RESERVATION is shown but not releasable here: COMPONENT_CATALOG marks it
// `reservation-locked` with a reason, because releasing it from the itinerary
// would leave the booking and the plan disagreeing.

type TripItem = components['schemas']['TripDetail']['days'][number]['items'][number];
type ConstraintType = components['schemas']['ConstraintType'];

export interface LockRowProps {
  item: TripItem;
  tripId: string | null;
  etag: string | null;
}

/**
 * Tolerance sent with a TIME lock.
 *
 * Zero, because the contract requires the field, gives it no default, and the
 * user was offered no slack to choose. Any other number would be this screen
 * inventing a window the user never asked for — "lock this time" means this
 * time. A real tolerance picker belongs with FR-CON-03's design (FCR-017,
 * whose node is still unconfirmed).
 */
const DEFAULT_TIME_TOLERANCE_MINUTES = 0;

export function LockRow({ item, tripId, etag }: LockRowProps) {
  const { t } = useI18n();
  const [confirming, setConfirming] = useState<ConstraintType | null>(null);
  // Which action failed, so the message names the right one.
  const [failed, setFailed] = useState<'set' | 'release' | null>(null);
  const remove = useRemoveItemConstraint(tripId);
  const set = useSetItemConstraint(tripId);
  const locks = activeLocks(item);
  const settable = settableLocks(item);

  // No early return any more. This component used to render nothing when an
  // item had no locks, which is why a user could release a lock but never
  // create one — the only locks that existed came from an import (FE-307).
  if (locks.length === 0 && settable.length === 0) return null;

  function release(type: ConstraintType) {
    setFailed(null);
    remove.mutate(
      { itemId: item.id, constraintType: type, etag },
      {
        onError: (error) => {
          // A conflict means the trip moved on; the lock is unchanged either
          // way, so this reports rather than retrying behind the user's back.
          setFailed('release');
          if (isProblem(error) && error.code === 'TRIP_CHANGED') return;
        },
      },
    );
  }

  function apply(type: ConstraintType) {
    setFailed(null);
    // One request, one lock. The body repeats the path's type because the
    // contract makes it the union discriminator, and the value it carries is
    // read off the item rather than invented: a DATE lock pins the day the
    // item is already on, and a TIME lock pins the time it already has.
    const constraint =
      type === 'DATE'
        ? ({ type: 'DATE', locked: true, date: item.date } as const)
        : type === 'TIME'
          ? ({
              type: 'TIME',
              locked: true,
              startTime: item.startTime ?? '',
              toleranceMinutes: DEFAULT_TIME_TOLERANCE_MINUTES,
            } as const)
          : ({ type: 'MUST_VISIT', locked: true } as const);
    set.mutate(
      { itemId: item.id, constraint, etag },
      {
        onError: () => {
          setFailed('set');
        },
      },
    );
  }

  /** What the confirm says survives this release. */
  function keepsLine(type: ConstraintType): string {
    const keeps = remainingLocks(item, type);
    if (keeps.length === 0) return t('trip.lock.keepsNone');
    return t('trip.lock.keeps', {
      locks: keeps.map((lock) => t(`trip.lock.${lock}` as MessageKey)).join(' · '),
    });
  }

  const pending = confirming;

  return (
    <>
      <ul className={styles.locks}>
        {locks.map((lock) => {
          const managed = lock === 'RESERVATION';
          const label = t(`trip.lock.${lock}` as MessageKey);
          return (
            <li key={lock}>
              <button
                // Named for what pressing does, not for the lock's name: a
                // button called "날짜 고정" reads as if it applies one.
                aria-label={t('trip.lock.release', { lock: label })}
                className={managed ? `${styles.lock} ${styles.managed}` : styles.lock}
                disabled={managed || remove.isPending || etag === null}
                onClick={() => {
                  if (needsConfirm(lock)) {
                    setConfirming(lock);
                    return;
                  }
                  release(lock);
                }}
                title={managed ? t('trip.lock.reservationNote') : undefined}
                type="button"
              >
                {label}
              </button>
            </li>
          );
        })}
      </ul>

      {settable.length > 0 ? (
        <ul className={styles.locks}>
          {settable.map((lock) => {
            const label = t(`trip.lock.${lock}` as MessageKey);
            return (
              <li key={lock}>
                <button
                  // Named for what pressing does. Each press sends one request
                  // for one lock and leaves the others exactly as they were
                  // (invariant 7).
                  aria-label={t('trip.lock.apply', { lock: label })}
                  className={`${styles.lock} ${styles.settable}`}
                  disabled={set.isPending || etag === null}
                  onClick={() => {
                    apply(lock);
                  }}
                  type="button"
                >
                  {/* The visible text says what pressing does, not just which
                      lock it is. An item with MUST_VISIT set and DATE unset
                      otherwise showed "Must visit" on a release control and
                      "Date locked" on a set control with nothing to tell them
                      apart but the accessible name. */}
                  {t('trip.lock.apply', { lock: label })}
                </button>
              </li>
            );
          })}
        </ul>
      ) : null}

      {set.isPending ? (
        <p className={styles.state} role="status">
          {t('trip.lock.applying')}
        </p>
      ) : null}

      {remove.isPending ? (
        <p className={styles.state} role="status">
          {t('trip.lock.releasing')}
        </p>
      ) : null}

      {failed === null ? null : (
        <p className={styles.state} role="alert">
          {t(failed === 'set' ? 'trip.lock.applyFailed' : 'trip.lock.releaseFailed')}
        </p>
      )}

      <ConfirmDialog
        body={
          pending === null ? undefined : (
            <>
              <span className={styles.body}>
                {t(
                  pending === 'MUST_VISIT'
                    ? 'trip.lock.mustVisit.body'
                    : 'trip.lock.date.body',
                )}
              </span>
              {/* The surviving locks, named. Computed from the item so an
                  "everything else stays" sentence cannot go stale. */}
              <span className={styles.keeps}>{keepsLine(pending)}</span>
            </>
          )
        }
        cancelLabel={t('trip.lock.cancel')}
        confirmLabel={t(
          pending === 'MUST_VISIT'
            ? 'trip.lock.mustVisit.confirm'
            : 'trip.lock.date.confirm',
        )}
        destructive
        onCancel={() => {
          setConfirming(null);
        }}
        onConfirm={() => {
          if (pending !== null) release(pending);
          setConfirming(null);
        }}
        open={pending !== null}
        title={t(
          pending === 'MUST_VISIT' ? 'trip.lock.mustVisit.title' : 'trip.lock.date.title',
        )}
      />
    </>
  );
}
