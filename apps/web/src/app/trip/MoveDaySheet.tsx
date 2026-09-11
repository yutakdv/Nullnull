import { useEffect, useRef } from 'react';
import type { components } from '@nullnull/api-client';
import { useI18n } from '../../i18n/I18nProvider.js';
import styles from './MoveDaySheet.module.css';
import { currentDate } from './reorder.js';

// S07-10 move sheet `521:3976` (FR-ITM-03, FE-305).
//
// A <dialog> rather than a hand-rolled overlay, for the same reason
// ConfirmDialog is one: the browser supplies the focus trap, the inert
// background and Escape, and a hand-rolled sheet has to reimplement each.
//
// The frame's rules this component keeps:
//   - The item's current day is shown DISABLED with "지금 이 날짜예요" rather
//     than hidden. The user needs to see where it is now to choose where it
//     goes.
//   - The footer states what survives the move ("시작 시간은 그대로 이어받아요").
//     That is a promise about server behaviour, so it is copy the contract
//     backs, not reassurance invented here.
//
// NOT BUILT: the frame shows each day's crowd (`2 · 여유 · 공식 혼잡 예측`) and a
// source line. Crowd for a date comes from getPlaceCrowdForecast, a dated
// series per place — there is no per-day trip crowd in the contract, and
// picking one place's value to represent a whole day would be a number nobody
// measured (invariant 8). Raised with FCR-029's open question on #105.

type TripDetail = components['schemas']['TripDetail'];
type TripDay = TripDetail['days'][number];

export interface MoveDaySheetProps {
  open: boolean;
  itemId: string;
  itemName: string;
  days: readonly TripDay[];
  locale: string;
  onPick: (date: string) => void;
  onCancel: () => void;
}

export function MoveDaySheet({
  open,
  itemId,
  itemName,
  days,
  locale,
  onPick,
  onCancel,
}: MoveDaySheetProps) {
  const { t } = useI18n();
  const ref = useRef<HTMLDialogElement>(null);
  const restoreTo = useRef<HTMLElement | null>(null);
  const cancelRef = useRef<HTMLButtonElement>(null);
  const here = currentDate(days, itemId);

  useEffect(() => {
    const dialog = ref.current;
    if (!dialog) return;
    if (open) {
      restoreTo.current = document.activeElement as HTMLElement | null;
      if (!dialog.open) dialog.showModal();
      cancelRef.current?.focus();
    } else if (dialog.open) {
      dialog.close();
    }
  }, [open]);

  useEffect(() => {
    if (open) return;
    const target = restoreTo.current;
    restoreTo.current = null;
    if (target?.isConnected) target.focus();
  }, [open]);

  function dayLabel(index: number) {
    return t('trip.day', { n: index + 1 });
  }

  function shortDate(date: string) {
    return new Intl.DateTimeFormat(locale, {
      month: 'numeric',
      day: 'numeric',
      timeZone: 'UTC',
    }).format(new Date(`${date}T00:00:00Z`));
  }

  const hereIndex = days.findIndex((day) => day.date === here);

  return (
    <dialog
      aria-labelledby="move-day-title"
      className={styles.sheet}
      onCancel={(event) => {
        event.preventDefault();
        onCancel();
      }}
      onClick={(event) => {
        if (event.target === ref.current) onCancel();
      }}
      onKeyDown={(event) => {
        // happy-dom never fires `cancel`, so Escape is handled here too.
        if (event.key !== 'Escape') return;
        event.preventDefault();
        event.stopPropagation();
        onCancel();
      }}
      ref={ref}
    >
      <div className={styles.panel}>
        <span aria-hidden="true" className={styles.grab} />

        <div className={styles.head}>
          <h2 className={styles.title} id="move-day-title">
            {t('trip.move.title')}
          </h2>
          <button
            className={styles.cancel}
            onClick={onCancel}
            ref={cancelRef}
            type="button"
          >
            {t('trip.move.cancel')}
          </button>
        </div>

        {/* Which item is moving, and where it sits now. */}
        <p className={styles.context}>
          <span className={styles.contextName}>{itemName}</span>
          {here === null ? null : (
            <span className={styles.contextNow}>
              {t('trip.move.now', {
                day: dayLabel(hereIndex),
                date: shortDate(here),
              })}
            </span>
          )}
        </p>

        <p className={styles.pick}>{t('trip.move.pick')}</p>

        <ul className={styles.days}>
          {days.map((day, index) => {
            const isHere = day.date === here;
            return (
              <li key={day.date}>
                <button
                  className={isHere ? `${styles.day} ${styles.dayHere}` : styles.day}
                  // Shown, not hidden: the user needs to see where it is now.
                  disabled={isHere}
                  onClick={() => {
                    onPick(day.date);
                  }}
                  type="button"
                >
                  <span className={styles.dayText}>
                    <span className={styles.dayName}>
                      {dayLabel(index)}
                      <span className={styles.dayDate}>{shortDate(day.date)}</span>
                    </span>
                    {isHere ? (
                      <span className={styles.dayNote}>{t('trip.move.current')}</span>
                    ) : null}
                  </span>
                  {isHere ? null : (
                    <span aria-hidden="true" className={styles.chevron}>
                      ›
                    </span>
                  )}
                </button>
              </li>
            );
          })}
        </ul>

        {/* What survives the move. The server preserves the wall-clock time, so
            this is a statement about behaviour rather than reassurance. */}
        <p className={styles.footnote}>{t('trip.move.keepsTime')}</p>
      </div>
    </dialog>
  );
}
