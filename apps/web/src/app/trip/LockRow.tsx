import { useState } from 'react';
import type { components } from '@nullnull/api-client';
import { useI18n } from '../../i18n/I18nProvider.js';
import type { MessageKey } from '../../i18n/messages.js';
import { isProblem, useRemoveItemConstraint } from '../../shared/api/index.js';
import { ConfirmDialog } from '../../shared/ui/components/index.js';
import styles from './LockRow.module.css';
import { activeLocks, needsConfirm, remainingLocks } from './locks.js';

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

export function LockRow({ item, tripId, etag }: LockRowProps) {
  const { t } = useI18n();
  const [confirming, setConfirming] = useState<ConstraintType | null>(null);
  const [failed, setFailed] = useState(false);
  const remove = useRemoveItemConstraint(tripId);
  const locks = activeLocks(item);

  if (locks.length === 0) return null;

  function release(type: ConstraintType) {
    setFailed(false);
    remove.mutate(
      { itemId: item.id, constraintType: type, etag },
      {
        onError: (error) => {
          // A conflict means the trip moved on; the lock is unchanged either
          // way, so this reports rather than retrying behind the user's back.
          setFailed(true);
          if (isProblem(error) && error.code === 'TRIP_CHANGED') return;
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

      {remove.isPending ? (
        <p className={styles.state} role="status">
          {t('trip.lock.releasing')}
        </p>
      ) : null}

      {failed ? (
        <p className={styles.state} role="alert">
          {t('trip.lock.releaseFailed')}
        </p>
      ) : null}

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
