import { useEffect, useRef, useState } from 'react';
import type { components } from '@nullnull/api-client';
import { useI18n } from '../../i18n/I18nProvider.js';
import { isProblem, useRemoveTripItem, useTrip } from '../../shared/api/index.js';
import styles from './RemoveItemControl.module.css';

// Taking a stop off the itinerary (FR-ITM-06, P0).
//
// Everything for this existed except the control: useRemoveTripItem, an msw
// handler, and eighteen translated strings in both locales — all of it unused,
// because no screen rendered a way to remove an item. A place added by mistake
// could not be taken off at all.
//
// The choice is the feature, not a detail of it. FIGMA_HANDOFF states the rule:
// "일정 item 삭제가 후보 복원인지 완전 제거인지는 사용자가 고르게 한다. 기본값은
// 후보 복원이다." So this asks, and it asks with two named outcomes rather than
// an OK/Cancel that hides which one happens.
//
// That is why it does not use ConfirmDialog. That component is confirm+cancel
// by construction, and the third action would have to be smuggled into its
// `body` slot — a primary action rendered as body text, which is exactly the
// kind of thing that reads as a link and gets missed. Three outcomes want three
// buttons.

type TripDetail = components['schemas']['TripDetail'];
type TripItem = TripDetail['days'][number]['items'][number];

export interface RemoveItemControlProps {
  item: TripItem;
  tripId: string | null;
  etag: string | null;
  /**
   * Where the outcome is announced.
   *
   * Not inside this component: a successful removal unmounts the row it lives
   * in, so the message would be destroyed by the very action it reports. The
   * screen holds it instead and outlives the row.
   */
  onAnnounce?: (message: string) => void;
}

export function RemoveItemControl({
  item,
  tripId,
  etag,
  onAnnounce,
}: RemoveItemControlProps) {
  const { t } = useI18n();
  const trip = useTrip(tripId);
  const remove = useRemoveTripItem(tripId);
  const [confirming, setConfirming] = useState(false);
  const [status, setStatus] = useState<string | null>(null);
  const [failed, setFailed] = useState<'conflict' | 'failed' | null>(null);
  const dialogRef = useRef<HTMLDialogElement>(null);
  const openerRef = useRef<HTMLButtonElement>(null);
  const cancelRef = useRef<HTMLButtonElement>(null);
  /**
   * The day section this row sits in, captured while the row is still mounted.
   *
   * NOT "where focus was before the dialog opened": that is the opener itself,
   * and the opener is inside the row that BOTH dispositions unmount — keeping
   * a place as a candidate still takes the stop off the itinerary. A fallback
   * to the element the user came from is therefore a fallback to the same
   * doomed node, which is how focus reached document.body.
   *
   * The day section outlives the removal even when the stop was the day's
   * last, because the day then renders its empty state rather than
   * disappearing. It is owned by TripScreen, so it is reached by walking up
   * from the opener rather than by a ref this component could hold.
   */
  const restoreTo = useRef<HTMLElement | null>(null);

  // Opened and closed in an EFFECT, not during render. showModal() is what
  // gives the platform focus trap and inert background and React has no prop
  // for it, but doing it in the component body means moving focus during a
  // render pass — and main.tsx wraps the app in StrictMode, which invokes
  // render twice and may discard the first. ConfirmDialog and TripPicker both
  // already do this in an effect; this was the one dialog that did not.
  useEffect(() => {
    const dialog = dialogRef.current;
    if (!dialog) return;
    if (confirming) {
      // Captured while the row is still mounted, because after the removal
      // there is no way back up from a detached node. tabIndex is set here
      // rather than in TripScreen so the section only becomes focusable for
      // the exit that needs it, and -1 keeps it out of the tab order.
      const day = openerRef.current?.closest<HTMLElement>(
        'section[aria-labelledby^="day-"]',
      );
      if (day) day.tabIndex = -1;
      restoreTo.current = day ?? null;
      if (!dialog.open) dialog.showModal();
      // The safe choice takes focus, the way ConfirmDialog does it. NOT
      // autoFocus: this dialog is mounted (closed) on every row, and autoFocus
      // fires on mount, so it pulled focus out of the page and broke the
      // screen's tab order — caught by trip-screen's keyboard test.
      cancelRef.current?.focus();
    } else if (dialog.open) {
      dialog.close();
    }
  }, [confirming]);

  /**
   * Closes the dialog and puts focus somewhere the user can see.
   *
   * Every exit routes through here — cancel, Escape, backdrop AND both submit
   * dispositions. `submit` used to call setConfirming(false) directly, so a
   * successful removal left focus on nothing: the opener it would have
   * returned to is inside the row the removal unmounts, and focus fell to
   * document.body.
   *
   * Where focus goes is the CALLER's to say, not something read off the DOM.
   * An earlier version preferred the trigger whenever it was still connected
   * and fell back to the day otherwise — which never fired, because submit()
   * closes before the request resolves: at that moment the row is still
   * mounted, so the trigger looks alive, takes focus, and is then destroyed
   * when the removal lands. Focus fell to document.body exactly as before.
   *
   * So the answers that leave the stop on screen (cancel, Escape, backdrop)
   * ask for the trigger, and the submit paths ask for the day — the nearest
   * thing that outlives the action and tells the user where they were.
   */
  function close(restore: 'trigger' | 'day' = 'trigger') {
    setConfirming(false);
    const target = restore === 'day' ? restoreTo.current : openerRef.current;
    restoreTo.current = null;
    // Deferred so focus lands after React has committed the close.
    queueMicrotask(() => {
      if (target?.isConnected) target.focus();
    });
  }

  function submit(disposition: 'RESTORE_CANDIDATE' | 'REMOVE') {
    setFailed(null);
    setStatus(null);
    // 'day', not the trigger: this row is leaving whichever disposition was
    // chosen — keeping the place as a candidate still takes the stop off the
    // itinerary — so the trigger is about to be unmounted and cannot hold
    // focus. Closing happens here rather than in onSuccess so the dialog does
    // not sit open over a request the user has already answered.
    close('day');
    remove.mutate(
      { itemId: item.id, disposition, etag },
      {
        onSuccess: () => {
          // The two outcomes are announced differently because they ARE
          // different: one leaves the place in the trip's saved list and the
          // other does not, and a single "removed" would hide which happened.
          const message = t(
            disposition === 'RESTORE_CANDIDATE'
              ? 'trip.remove.keptAsCandidate'
              : 'trip.remove.removed',
            { name: item.place.name },
          );
          if (onAnnounce) onAnnounce(message);
          else setStatus(message);
        },
        onError: (error) => {
          // A conflict means the trip moved on. The item is unchanged either
          // way, so this reports instead of retrying behind the user's back —
          // and refetches, because the cached ETag is stale the moment the
          // server says TRIP_CHANGED and a second press would fail the same way.
          if (isProblem(error) && error.code === 'TRIP_CHANGED') {
            setFailed('conflict');
            void trip.refetch();
            return;
          }
          setFailed('failed');
        },
      },
    );
  }

  return (
    <>
      <button
        className={styles.open}
        disabled={remove.isPending || etag === null}
        onClick={() => {
          setConfirming(true);
        }}
        ref={openerRef}
        type="button"
      >
        {t('trip.remove.open', { name: item.place.name })}
      </button>

      <dialog
        aria-labelledby={`remove-${item.id}`}
        className={styles.dialog}
        onCancel={(event) => {
          event.preventDefault();
          close();
        }}
        onClick={(event) => {
          if (event.target === dialogRef.current) close();
        }}
        onKeyDown={(event) => {
          // happy-dom never fires `cancel`, so Escape is handled here too or
          // the unit tests would never exercise the key a real user presses.
          if (event.key !== 'Escape') return;
          event.preventDefault();
          event.stopPropagation();
          close();
        }}
        ref={dialogRef}
      >
        <div className={styles.panel}>
          <h2 className={styles.title} id={`remove-${item.id}`}>
            {t('trip.remove.title')}
          </h2>
          <p className={styles.body}>
            {t('trip.remove.body', { name: item.place.name })}
          </p>
          <div className={styles.actions}>
            {/* Cancel first and focused: Enter on an unread dialog must not
                take a stop off the itinerary. */}
            <button
              className={styles.cancel}
              onClick={() => {
                // Wrapped, not passed bare: close() now takes where focus
                // should go, and React would hand it the MouseEvent.
                close();
              }}
              ref={cancelRef}
              type="button"
            >
              {t('trip.remove.cancel')}
            </button>
            {/* The default the handoff names, so it reads as the ordinary
                choice rather than the cautious one. */}
            <button
              className={styles.keep}
              onClick={() => {
                submit('RESTORE_CANDIDATE');
              }}
              type="button"
            >
              {t('trip.remove.keepCandidate')}
            </button>
            <button
              className={styles.discard}
              onClick={() => {
                submit('REMOVE');
              }}
              type="button"
            >
              {t('trip.remove.discard')}
            </button>
          </div>
        </div>
      </dialog>

      {status === null ? null : (
        <p aria-live="polite" className={styles.status} role="status">
          {status}
        </p>
      )}

      {failed === null ? null : (
        <p className={styles.status} role="alert">
          {t(failed === 'conflict' ? 'trip.conflict' : 'trip.remove.failed')}
        </p>
      )}
    </>
  );
}
