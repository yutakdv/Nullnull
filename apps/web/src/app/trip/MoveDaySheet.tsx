import { useEffect, useId, useRef } from 'react';
import type { components } from '@nullnull/api-client';
import { useI18n } from '../../i18n/I18nProvider.js';
import { usePlaceCrowdForecast } from '../../shared/api/index.js';
import {
  CrowdForecastQueryState,
  CrowdForecastReading,
} from '../../shared/crowd/CrowdForecastReading.js';
import { crowdPointForDate } from '../../shared/crowd/forecast.js';
import { restoreFocusTo } from '../../shared/ui/components/focus-restore.js';
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
// Each row now maps the place's dated forecast by its exact KST target date.
// KTO's relative index has no approved ordinal mapping, so the row shows the
// returned value/state/provenance and never invents the Figma's example stage.

type TripDetail = components['schemas']['TripDetail'];
type TripDay = TripDetail['days'][number];

export interface MoveDaySheetProps {
  open: boolean;
  placeId: string;
  itemId: string;
  itemName: string;
  days: readonly TripDay[];
  locale: string;
  onPick: (date: string) => void;
  onCancel: () => void;
}

export function MoveDaySheet({
  open,
  placeId,
  itemId,
  itemName,
  days,
  locale,
  onPick,
  onCancel,
}: MoveDaySheetProps) {
  const { t } = useI18n();
  // Unique per instance, not a literal: this screen mounts one sheet PER trip
  // item, so a hardcoded id put the same value on every dialog in the document.
  // getElementById returns the first match, so every sheet after the first was
  // labelled by another item's heading — a screen reader announced the wrong
  // place. ConfirmDialog already does it this way.
  const titleId = useId();
  const ref = useRef<HTMLDialogElement>(null);
  const restoreTo = useRef<HTMLElement | null>(null);
  const cancelRef = useRef<HTMLButtonElement>(null);
  const here = currentDate(days, itemId);
  const dates = days.map((day) => day.date).sort();
  const forecast = usePlaceCrowdForecast(
    placeId,
    dates[0] ?? null,
    dates.at(-1) ?? null,
    open,
  );

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

  // `restoreFocusTo` rather than `isConnected` + `.focus()`: picking a day
  // closes this sheet and starts the reorder IN THE SAME HANDLER
  // (ItemMoveControls.tsx `onPick` — setSheetOpen(false) then commitMove), and
  // the trigger that opened it is `disabled={busy || ...}` with
  // `busy = reorder.isPending`, inside an item menu that the same press hides.
  // So the restore target is connected but cannot hold focus, `.focus()` fails
  // silently, and focus falls to <body> where the next Tab restarts at the top
  // of the document. That is #272 cause ④, reached through this sheet. The
  // shared helper declines a target that cannot hold focus and falls back to
  // the <main> landmark.
  //
  // What goes red if this line is reverted to a bare `.focus()`, measured:
  // keyboard-flow.spec.ts "moving a stop whose lock does not hold its date"
  // (FE-305-T4) in 8 runs of 8, and locally "a completed move leaves focus
  // somewhere" in 8 of 8 as well. Both read focus only once the stop has moved
  // and focus has left the closed sheet; read earlier, the pressed day button
  // still held it and the second test caught the revert in 1 run of 6.
  //
  //   - A unit test cannot see it. happy-dom lets `.focus()` succeed inside a
  //     CLOSED <dialog> (focus-restore.spec.ts, "WHY A BROWSER"), so the
  //     restored and the lost case are the same observation there. One was
  //     written against this path and went green with the defect in place.
  //   - In the gate, "a completed move" moves FIRST_ITEM, which carries a DATE
  //     lock (seeded-trip.ts), so `onPick` leaves through the lock confirm and
  //     ConfirmDialog does the restore. The MUST_VISIT stop (SECOND_ITEM) is the
  //     path where this line is the only guard, and FE-305-T4 walks it in the
  //     gate as well as in the mock.
  useEffect(() => {
    if (open) return;
    const target = restoreTo.current;
    restoreTo.current = null;
    restoreFocusTo(target);
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
      aria-labelledby={titleId}
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
          <h2 className={styles.title} id={titleId}>
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
        <CrowdForecastQueryState
          failed={forecast.isError}
          loading={forecast.isFetching}
          series={forecast.data}
        />

        <ul className={styles.days}>
          {days.map((day, index) => {
            const isHere = day.date === here;
            return (
              <li className={styles.dayRow} key={day.date}>
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
                <CrowdForecastReading
                  point={crowdPointForDate(forecast.data, day.date)}
                />
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
