import { useEffect, useId, useRef, type ReactNode } from 'react';
import styles from './ConfirmDialog.module.css';

// Figma: S07-9 폐기 dialog `413:2020`, and the shape any confirm takes.
//
// Built on <dialog showModal()> rather than a hand-rolled overlay: the browser
// gives the focus trap, the inert background, the Escape key and the top layer
// for free, and a hand-rolled version has to reimplement each one correctly.
//
// Focus restore is explicit anyway. `showModal` returns focus to the previously
// focused element on close in a compliant browser, but the trigger is often
// unmounted or re-rendered by the time it closes, so the caller's element is
// captured on open and restored on close (.claude/rules/frontend.md).

export interface ConfirmDialogProps {
  open: boolean;
  title: string;
  body?: ReactNode;
  /** The action that proceeds. Destructive here means "discards the edit". */
  confirmLabel: string;
  cancelLabel: string;
  destructive?: boolean;
  onConfirm: () => void;
  /** Escape, the backdrop, and the cancel button all route here. */
  onCancel: () => void;
}

export function ConfirmDialog({
  open,
  title,
  body,
  confirmLabel,
  cancelLabel,
  destructive = false,
  onConfirm,
  onCancel,
}: ConfirmDialogProps) {
  // Unique per instance: more than one dialog can be mounted at once (a screen
  // with a discard confirm and a lock confirm), and a hardcoded id points every
  // one of them at the first heading in the document.
  const titleId = useId();
  const ref = useRef<HTMLDialogElement>(null);
  const restoreTo = useRef<HTMLElement | null>(null);
  const cancelRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    const dialog = ref.current;
    if (!dialog) return;

    if (open) {
      // Captured before the dialog takes focus, so it is the element the user
      // actually came from.
      restoreTo.current = document.activeElement as HTMLElement | null;
      if (!dialog.open) dialog.showModal();
      // The safe choice takes focus, not the destructive one: Enter on an
      // unread dialog must not discard the user's work.
      cancelRef.current?.focus();
    } else if (dialog.open) {
      dialog.close();
    }
  }, [open]);

  useEffect(() => {
    if (open) return;
    const target = restoreTo.current;
    restoreTo.current = null;
    // Nothing was captured, so this dialog has never been open — every screen
    // that mounts one renders it closed, and moving focus here would steal it
    // from wherever the page actually starts. trip-screen.test.tsx caught
    // exactly that: its tab walk began one control late.
    if (target === null) return;
    // Restored after the dialog has gone, so focus lands on a visible element.
    if (target.isConnected) {
      target.focus();
      return;
    }
    // The opener has unmounted. That happens when one surface opens this
    // dialog and closes itself doing it — a day picked inside MoveDaySheet
    // opens the DATE-lock confirm and dismisses the sheet, so the button this
    // captured is gone by now (#233, measured in a browser: focus fell to
    // <body> and the next Tab restarted at the top of the page).
    //
    // `isConnected` refusing to focus a detached node is right; leaving focus
    // nowhere is not. The dialog's own parent is the nearest thing still on
    // screen that the traveller was looking at, so focus goes there and the
    // next Tab continues from the region they were working in rather than
    // from the document.
    //
    // The dialog's own previous sibling that can still take focus, rather
    // than the parent container: marking the container with tabIndex -1
    // changes tab traversal for every screen that mounts a dialog, and two
    // tab-order tests caught exactly that (profile.test.tsx and
    // trip-screen.test.tsx both went red — measured). A control that is
    // already focusable needs no mutation at all.
    const dialog = ref.current;
    if (!dialog) return;
    const focusable = dialog.parentElement?.querySelectorAll<HTMLElement>(
      'a[href], button:not([disabled]), input:not([disabled]), select, textarea, [tabindex]:not([tabindex="-1"])',
    );
    // The last one before this dialog in document order: the traveller was
    // working forward through the page, so the nearest control behind them is
    // where they left off.
    //
    // `dialog.contains` alone is not enough, and the difference is not
    // theoretical: TripScreen renders LockRow's ConfirmDialog and
    // ItemMoveControls' ConfirmDialog as SIBLINGS inside one <article>
    // (TripScreen.tsx:358,362), and LockRow's stays mounted-but-closed the
    // whole time. `display: none` does not remove a node from
    // querySelectorAll, so that closed dialog's Cancel button matched the
    // selector, sat earlier in document order, and won — and focusing an
    // element inside a closed <dialog> is a no-op per spec, so focus stayed on
    // <body>, which is the exact thing this fallback exists to prevent.
    //
    // KNOWN INCOMPLETE, and the gap is worth stating precisely because it is
    // easy to read this filter as more than it is. Excluding closed dialogs
    // fixes WHICH element gets picked. It does not guarantee focus lands: when
    // the itinerary move that opened this confirm also unmounts the <article>
    // the dialog sits in, every candidate under `dialog.parentElement` goes
    // with it, and the one picked here is detached by the time it is focused.
    // Measured that way: `targetStillConnected=false`,
    // `targetLabel="Replace 경복궁"`, focus on <body> (3/3 runs), with no
    // `focusin` for the target in any of 8 event traces.
    //
    // Closing that needs a candidate search that can leave the moved item's
    // subtree, plus a landing check after `focus()` (it fails silently today).
    // That is a separate change with its own test; this one is scoped to the
    // closed-dialog case, which confirm-dialog.test.tsx pins (mutation:
    // radius 1, its own case only).
    const closedDialog = (node: HTMLElement) => {
      for (let p: HTMLElement | null = node; p; p = p.parentElement) {
        if (p.tagName === 'DIALOG' && !p.hasAttribute('open')) return true;
      }
      return false;
    };
    let previous: HTMLElement | null = null;
    for (const candidate of focusable ?? []) {
      if (dialog.contains(candidate)) continue;
      if (closedDialog(candidate)) continue;
      if (candidate.compareDocumentPosition(dialog) & Node.DOCUMENT_POSITION_FOLLOWING) {
        previous = candidate;
      }
    }
    previous?.focus();
  }, [open]);

  return (
    <dialog
      aria-labelledby={titleId}
      className={styles.dialog}
      onCancel={(event) => {
        // Escape, via the platform. Prevented so React owns the open state
        // rather than the DOM closing underneath it and the two disagreeing.
        event.preventDefault();
        onCancel();
      }}
      onKeyDown={(event) => {
        // Escape again, explicitly. `cancel` is the platform path, but it is
        // not universal: happy-dom (the suite's environment for these screens)
        // never fires it, so relying on `cancel` alone leaves the key untested
        // here and untestable anywhere. Both routes end at the same handler,
        // and stopping propagation keeps one press from closing two things.
        if (event.key !== 'Escape') return;
        event.preventDefault();
        event.stopPropagation();
        onCancel();
      }}
      onClick={(event) => {
        // The backdrop is the dialog element itself; a click on the panel
        // stops at the panel.
        if (event.target === ref.current) onCancel();
      }}
      ref={ref}
    >
      <div className={styles.panel}>
        <h2 className={styles.title} id={titleId}>
          {title}
        </h2>
        {body === undefined ? null : <div className={styles.body}>{body}</div>}
        <div className={styles.actions}>
          <button
            className={styles.cancel}
            onClick={onCancel}
            ref={cancelRef}
            type="button"
          >
            {cancelLabel}
          </button>
          <button
            className={destructive ? styles.destructive : styles.confirm}
            onClick={onConfirm}
            type="button"
          >
            {confirmLabel}
          </button>
        </div>
      </div>
    </dialog>
  );
}
