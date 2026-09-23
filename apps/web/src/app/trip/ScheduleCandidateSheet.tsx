import { useEffect, useRef } from 'react';
import type { components } from '@nullnull/api-client';
import { useI18n } from '../../i18n/I18nProvider.js';
import { usePlaceCrowdForecast } from '../../shared/api/index.js';
import {
  CrowdForecastQueryState,
  CrowdForecastReading,
} from '../../shared/crowd/CrowdForecastReading.js';
import { crowdPointForDate } from '../../shared/crowd/forecast.js';
import { blockedSlots, eligibleSlots, hasDecision } from './candidates.js';
import styles from './ScheduleCandidateSheet.module.css';

// S07-10 후보 일정화 sheet `527:4732` (FR-CAN-07, FR-ITM-02, FE-305).
//
// "어느 날에 추가할까요?" — picking the date a saved candidate is scheduled
// onto. The frame id is named `move-date`, but the sheet inside it is this one:
// its copy is 신규 추가 장소 and 추가할 날짜를 고르세요, not a move. That name
// is why an earlier reading of this audit called it the move sheet; the
// contents settled it.
//
// A <dialog>, like MoveDaySheet and TripPicker, so the browser supplies the
// focus trap, the inert background and Escape rather than three hand-rolled
// copies of each. Escape is handled in onKeyDown as well as onCancel because
// happy-dom never fires `cancel`.
//
// This sheet only CHOOSES a date. The request is the caller's mutation, so the
// sheet cannot become a second place where an item gets written — the same
// split TripPicker keeps, and what stops invariant 1 from being blurred across
// two components.
//
// CandidateMatchResult still carries no crowd. The sheet therefore reads the
// place's dated series separately and joins an exact point by KST target date.
// It never derives an ordinal stage from KTO's relative index.

type CandidateMatchResult = components['schemas']['CandidateMatchResult'];
type CandidateSlot = CandidateMatchResult['slots'][number];

export interface ScheduleCandidateSheetProps {
  open: boolean;
  placeId: string;
  startDate: string | null;
  endDate: string | null;
  /** The place being scheduled, named so the user knows what they are placing. */
  placeName: string;
  /** The server's answer for this candidate, or null while it is being asked. */
  match: CandidateMatchResult | null;
  loading?: boolean;
  failed?: boolean;
  /** Disables every date while a schedule request is in flight. */
  busy?: boolean;
  locale: string;
  /** Day index per date, so a row can say `day 2` the way the itinerary does. */
  dayIndexOf: (date: string) => number;
  onPick: (slot: CandidateSlot) => void;
  onCancel: () => void;
}

export function ScheduleCandidateSheet({
  open,
  placeId,
  startDate,
  endDate,
  placeName,
  match,
  loading = false,
  failed = false,
  busy = false,
  locale,
  dayIndexOf,
  onPick,
  onCancel,
}: ScheduleCandidateSheetProps) {
  const { t } = useI18n();
  const ref = useRef<HTMLDialogElement>(null);
  const restoreTo = useRef<HTMLElement | null>(null);
  const cancelRef = useRef<HTMLButtonElement>(null);
  const forecast = usePlaceCrowdForecast(placeId, startDate, endDate, open);

  useEffect(() => {
    const dialog = ref.current;
    if (!dialog) return;
    if (open) {
      restoreTo.current = document.activeElement as HTMLElement | null;
      if (!dialog.open) dialog.showModal();
      // The one control present in every state. Focusing a date would land on
      // nothing while the match is still being checked or came back NONE.
      cancelRef.current?.focus();
    } else if (dialog.open) {
      dialog.close();
    }
  }, [open]);

  useEffect(() => {
    if (open) return;
    const target = restoreTo.current;
    restoreTo.current = null;
    // LATENT, recorded rather than fixed (#272 cause 4). `isConnected` asks
    // whether the node is in the page, not whether it can HOLD focus: focusing
    // a disabled button is a silent no-op and leaves focus on <body>.
    // TripPicker had exactly that defect -- picking a trip re-rendered its
    // trigger into TripAddButton's `loading` state, which is `disabled` -- and
    // now routes through `restoreFocusTo`
    // (shared/ui/components/focus-restore.ts), which also falls back to <main>.
    //
    // UNREACHABLE here today: this sheet's trigger (CandidatesScreen.tsx:343)
    // carries no `disabled` at all. It is conditionally RENDERED instead
    // (`{scheduled ? null : ...}`), so on the path that removes it the node is
    // gone rather than present-but-disabled, `isConnected` correctly says no,
    // and CandidatesScreen does its own restore to the row's Remove control.
    // That path is pinned by the saved-places test in
    // e2e/focus-restore.spec.ts (which carries no acceptance ID -- that file
    // says why).
    //
    // Switching now would be a radius-0 change -- no test could tell it apart
    // -- so this note stands in for it: if a `disabled` is ever added to that
    // trigger (a pending schedule is the obvious one), this line starts
    // dropping focus to <body> silently, and the fix is `restoreFocusTo`.
    if (target?.isConnected) target.focus();
  }, [open]);

  function shortDate(date: string) {
    return new Intl.DateTimeFormat(locale, {
      month: 'numeric',
      day: 'numeric',
      timeZone: 'UTC',
    }).format(new Date(`${date}T00:00:00Z`));
  }

  function formatTime(time: string) {
    return new Intl.DateTimeFormat(locale, {
      hour: 'numeric',
      minute: '2-digit',
      timeZone: 'UTC',
    }).format(new Date(`1970-01-01T${time}Z`));
  }

  const eligible = match === null ? [] : eligibleSlots(match);
  const blocked = match === null ? [] : blockedSlots(match);
  // CHECKING and UNKNOWN are not "no dates" — the server has not finished
  // looking, or has no basis to say. NONE is the only state that decided
  // nothing fits, and each says its own thing.
  const undecided = match !== null && !hasDecision(match);

  /**
   * A sentence for the server's `reasonCode`, or the general one.
   *
   * The contract leaves `reasonCode` an open string, so the set is not fixed
   * and the screen cannot promise a sentence for every value. Known codes get
   * their own copy; anything else says the general thing rather than showing
   * the raw code, which is how a value nobody translated still reads as Korean.
   */
  function blockedReason(code: string | null | undefined): string {
    if (code === 'TIME_CONFLICT') return t('candidates.sheet.blocked.TIME_CONFLICT');
    if (code === 'DAY_FULL') return t('candidates.sheet.blocked.DAY_FULL');
    return t('candidates.sheet.blocked');
  }

  function dayRow(slot: CandidateSlot, usable: boolean) {
    const index = dayIndexOf(slot.date);
    return (
      <li className={styles.dayRow} key={slot.date}>
        <button
          className={usable ? styles.day : `${styles.day} ${styles.dayBlocked}`}
          disabled={!usable || busy}
          onClick={() => {
            onPick(slot);
          }}
          type="button"
        >
          <span className={styles.dayText}>
            <span className={styles.dayName}>
              {index < 0 ? shortDate(slot.date) : t('trip.day', { n: index + 1 })}
              <span className={styles.dayDate}>{shortDate(slot.date)}</span>
            </span>
            {usable ? (
              slot.suggestedTime ? (
                <span className={styles.dayTime}>
                  {t('candidates.sheet.suggested', {
                    time: formatTime(slot.suggestedTime),
                  })}
                </span>
              ) : null
            ) : (
              // Why this day cannot take it. `reasonCode` is a machine code
              // (`TIME_CONFLICT`), not copy — printing it raw would put a
              // server enum in front of a traveller, so it is rendered only
              // when the locale has a sentence for it and falls back to the
              // general one otherwise. A date dropped with no reason at all
              // reads as a bug; with one it reads as an answer.
              <span className={styles.dayNote}>{blockedReason(slot.reasonCode)}</span>
            )}
          </span>
          {usable ? (
            <span aria-hidden="true" className={styles.chevron}>
              ›
            </span>
          ) : null}
        </button>
        <CrowdForecastReading point={crowdPointForDate(forecast.data, slot.date)} />
      </li>
    );
  }

  return (
    <dialog
      aria-labelledby="schedule-candidate-title"
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
        <span aria-hidden="true" className={styles.grab} />

        <div className={styles.head}>
          <h2 className={styles.title} id="schedule-candidate-title">
            {t('candidates.sheet.title')}
          </h2>
          <button
            className={styles.cancel}
            onClick={onCancel}
            ref={cancelRef}
            type="button"
          >
            {t('candidates.sheet.cancel')}
          </button>
        </div>

        {/* What is being placed. Without it the sheet asks "which day?" about
            nothing in particular, which is the same question for every card. */}
        <p className={styles.context}>
          <span className={styles.contextLabel}>{t('candidates.sheet.newPlace')}</span>
          <span className={styles.contextName}>{placeName}</span>
        </p>

        {loading ? (
          <p className={styles.state} role="status">
            {t('candidates.match.CHECKING')}
          </p>
        ) : null}

        {failed ? (
          <p className={styles.state} role="alert">
            {t('candidates.match.error')}
          </p>
        ) : null}

        {/* NONE / CHECKING / UNKNOWN are NOT repeated here. The card already
            carries the state in its relation badge, and printing it a second
            time inside the sheet puts the same sentence on screen twice — a
            screen reader reads both, and a test asking "does it say NONE?"
            matches two nodes and cannot tell which one it measured.
            What the sheet owes these states is the absence of dates. NOT_ACTIVE
            instead explains that the candidate changed: the card badge is
            outside the modal and cannot be read while it is open. */}
        {undecided && !loading && !failed ? (
          <p className={styles.state} role="status">
            {match?.state === 'NOT_ACTIVE'
              ? t('candidates.match.NOT_ACTIVE')
              : t('candidates.sheet.noDates')}
          </p>
        ) : null}

        <CrowdForecastQueryState
          failed={forecast.isError}
          loading={forecast.isFetching}
          series={forecast.data}
        />

        {eligible.length > 0 || blocked.length > 0 ? (
          <>
            <p className={styles.pick}>{t('candidates.sheet.pickDate')}</p>
            {/* Named so the list is not an unlabelled group of bare dates to a
                screen reader, and so a test can address the dates rather than
                whatever list happens to be on screen. */}
            <ul aria-label={t('candidates.pickDate')} className={styles.days}>
              {eligible.map((slot) => dayRow(slot, true))}
              {blocked.map((slot) => dayRow(slot, false))}
            </ul>
          </>
        ) : null}

        {/* What the schedule gets. Saving a candidate never set a time, so the
            server's suggestion is the only one there is — said here rather than
            implied by a date that silently acquires one. */}
        <p className={styles.footnote}>{t('candidates.sheet.keepsTime')}</p>
      </div>
    </dialog>
  );
}
