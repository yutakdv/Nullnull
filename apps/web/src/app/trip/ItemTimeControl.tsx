import { useEffect, useRef, useState } from 'react';
import type { components } from '@nullnull/api-client';
import { useI18n } from '../../i18n/I18nProvider.js';
import { isProblem, useTrip, useUpdateTripItem } from '../../shared/api/index.js';
import styles from './ItemTimeControl.module.css';

type TripItem = components['schemas']['TripDetail']['days'][number]['items'][number];

/** An explicit clock edit in view mode, separate from the staged date/order editor. */
export function ItemTimeControl({
  item,
  tripId,
  etag,
}: {
  item: TripItem;
  tripId: string | null;
  etag: string | null;
}) {
  const { t } = useI18n();
  const trip = useTrip(tripId);
  const update = useUpdateTripItem(tripId);
  const [open, setOpen] = useState(false);
  const [time, setTime] = useState(item.startTime?.slice(0, 5) ?? '');
  const [error, setError] = useState<'conflict' | 'lock' | 'failed' | null>(null);
  const input = useRef<HTMLInputElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (open) input.current?.focus();
  }, [open]);

  function close() {
    setOpen(false);
    setError(null);
    queueMicrotask(() => trigger.current?.focus());
  }

  function save() {
    if (!time && item.startTime == null) return close();
    if (time === item.startTime?.slice(0, 5)) return close();
    update.mutate(
      { itemId: item.id, patch: { startTime: time ? `${time}:00` : null }, etag },
      {
        onSuccess: close,
        onError: (problem) => {
          if (isProblem(problem) && problem.code === 'TRIP_CHANGED') {
            setError('conflict');
            void trip.refetch();
          } else if (isProblem(problem) && problem.code === 'LOCK_CONFLICT') {
            setError('lock');
          } else {
            setError('failed');
          }
        },
      },
    );
  }

  return (
    <div className={styles.root}>
      <button
        disabled={etag === null}
        onClick={() => {
          setTime(item.startTime?.slice(0, 5) ?? '');
          setError(null);
          setOpen(true);
        }}
        ref={trigger}
        type="button"
      >
        {t('trip.time.edit')}
      </button>
      {open ? (
        <form
          className={styles.form}
          onSubmit={(event) => {
            event.preventDefault();
            save();
          }}
        >
          <label>
            {t('trip.time.label')}
            <input
              onChange={(event) => {
                setTime(event.target.value);
                setError(null);
              }}
              ref={input}
              type="time"
              value={time}
            />
          </label>
          <div className={styles.actions}>
            <button disabled={update.isPending} type="button" onClick={close}>
              {t('trip.time.cancel')}
            </button>
            <button disabled={update.isPending || etag === null} type="submit">
              {t('trip.time.save')}
            </button>
          </div>
          {error ? (
            <p role="alert">
              {t(
                error === 'conflict'
                  ? 'trip.time.conflict'
                  : error === 'lock'
                    ? 'trip.time.locked'
                    : 'trip.time.failed',
              )}
            </p>
          ) : null}
        </form>
      ) : null}
    </div>
  );
}
