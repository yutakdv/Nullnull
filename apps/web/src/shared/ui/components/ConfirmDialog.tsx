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

// Module scope rather than rebuilt per restore: the same string is now read
// once per ancestor as the fallback widens its search outward.
const FOCUSABLE =
  'a[href], button:not([disabled]), input:not([disabled]), select, textarea, [tabindex]:not([tabindex="-1"])';

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
  const dialogNode = useRef<HTMLDialogElement | null>(null);
  const cancelRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    const dialog = ref.current;
    if (!dialog) return;

    if (open) {
      // Captured before the dialog takes focus, so it is the element the user
      // actually came from.
      restoreTo.current = document.activeElement as HTMLElement | null;
      // The node itself, not just the ref: React sets `ref.current` to null
      // when this component unmounts, and the unmount path below still needs
      // the element to compare document order against. Measured — on the
      // unmount commit `ref.current === null` while `restoreTo` was intact.
      dialogNode.current = dialog;
      if (!dialog.open) dialog.showModal();
      // The safe choice takes focus, not the destructive one: Enter on an
      // unread dialog must not discard the user's work.
      cancelRef.current?.focus();
    } else if (dialog.open) {
      dialog.close();
    }
  }, [open]);

  // Held in a ref so the unmount cleanup below can call the same routine the
  // close path does, without taking `open` as a dependency — a cleanup that
  // re-ran on every toggle would fire the fallback while the dialog is still
  // alive.
  const restoreFocus = useRef<() => void>(() => undefined);
  restoreFocus.current = () => {
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
    // nowhere is not. So the nearest control still on screen behind the dialog
    // takes it, and the next Tab continues from the region the traveller was
    // working in rather than from the document.
    //
    // An already-focusable control rather than the container itself: marking a
    // container with tabIndex -1 changes tab traversal for every screen that
    // mounts a dialog, and two tab-order tests caught exactly that
    // (profile.test.tsx and trip-screen.test.tsx both went red — measured). A
    // control that is already focusable needs no mutation at all.
    const dialog = ref.current ?? dialogNode.current;
    if (!dialog) return;
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
    // The parent alone is not enough either, and that is #272: when the
    // itinerary move that opened this confirm also unmounts the <article> the
    // dialog sits in, every candidate under `dialog.parentElement` goes with
    // it, and the one picked is detached by the time it is focused. Measured
    // that way before this change: `targetStillConnected=false`,
    // `targetLabel="Replace 경복궁"`, focus on <body> (3/3 runs), with no
    // `focusin` for the target in any of 8 event traces.
    //
    // So the search widens outward — the dialog's parent first, then each
    // ancestor — and stops at the first one that still holds a usable
    // candidate. Widening rather than starting at <body> keeps the near case
    // near: the control beside the dialog is still preferred when it survives,
    // and only a container that went away sends the search outward.
    //
    // The walk starts from a scope that is still IN the document, which is not
    // the same as the dialog's parent and the difference is the whole of the
    // #272 case. By the time this effect runs, React has already removed the
    // <article>, taking the dialog with it: `dialog.isConnected` is false and
    // walking `dialog.parentElement` climbs the DETACHED tree, topping out at
    // the removed <article> without ever reaching the live page. Measured:
    // parent chain after unmount is exactly ["ARTICLE#row"] while the surviving
    // control sat in the live tree the walk never visited. So when the dialog
    // has gone with its container, the search restarts at <body>, which is the
    // nearest scope guaranteed to still be there.
    //
    // Document order still decides among what it finds, and a detached dialog
    // compares as DOCUMENT_POSITION_FOLLOWING against live nodes (measured), so
    // "the last control before the dialog" keeps its meaning: the traveller is
    // returned to the itinerary above the row that just left.
    const closedDialog = (node: HTMLElement) => {
      for (let p: HTMLElement | null = node; p; p = p.parentElement) {
        if (p.tagName === 'DIALOG' && !p.hasAttribute('open')) return true;
      }
      return false;
    };
    const previousWithin = (scope: HTMLElement): HTMLElement | null => {
      let found: HTMLElement | null = null;
      for (const candidate of scope.querySelectorAll<HTMLElement>(FOCUSABLE)) {
        if (dialog.contains(candidate)) continue;
        if (closedDialog(candidate)) continue;
        // The last one before this dialog in document order: the traveller was
        // working forward through the page, so the nearest control behind them
        // is where they left off.
        if (
          candidate.compareDocumentPosition(dialog) & Node.DOCUMENT_POSITION_FOLLOWING
        ) {
          found = candidate;
        }
      }
      return found;
    };

    let previous: HTMLElement | null = null;
    for (
      let scope = dialog.isConnected ? dialog.parentElement : document.body;
      scope && previous === null;
      scope = scope.parentElement
    ) {
      previous = previousWithin(scope);
    }

    previous?.focus();
    // `focus()` fails silently rather than throwing — an element that is
    // detached, `display: none`, or inside an `inert` subtree simply does not
    // take focus — so landing is verified instead of assumed, and the <main>
    // landmark is the honest last resort. It keeps the traveller inside the
    // itinerary region rather than the document, and is made programmatically
    // focusable only for the moment it is needed, because a permanent tabIndex
    // on a container changes tab traversal for every screen that mounts a
    // dialog (profile.test.tsx and trip-screen.test.tsx both went red when that
    // was tried — measured).
    //
    // WHAT THE SUITE PINS, stated precisely because the two differ: the tests
    // below reach this line only with `previous === null` (no candidate
    // survived), so they pin the landmark branch, NOT the comparison itself.
    // Replacing this line with `if (previous !== null) return` leaves all ten
    // green — measured. The comparison is the stronger form on purpose: in
    // happy-dom every candidate accepts focus (measured: hidden and inert
    // elements both became activeElement), so a candidate that is found but
    // refuses focus cannot be built here, while in a real browser it can. That
    // is the same environment gap that made cause ① invisible to unit tests.
    // If this line is ever weakened, the e2e suite is where it would be caught.
    if (document.activeElement === previous) return;
    const main = document.querySelector<HTMLElement>('main');
    if (!main || !main.isConnected) return;
    const hadTabIndex = main.hasAttribute('tabindex');
    if (!hadTabIndex) main.setAttribute('tabindex', '-1');
    main.focus();
    if (!hadTabIndex) main.removeAttribute('tabindex');
  };

  useEffect(() => {
    if (open) return;
    restoreFocus.current();
  }, [open]);

  // The same restore, for the case where this dialog never gets a closing
  // render: the change it confirmed unmounts the container it lives in, so
  // React removes the <article> and the dialog together in ONE commit and the
  // effect above never runs with `open: false`. Measured that way — the only
  // two runs of that effect were `open:false` at mount and `open:true` on
  // opening, with no `focusin` anywhere after the row left and focus sitting on
  // <body>. That is #272: no amount of fixing WHICH candidate the close path
  // picks can help, because the close path does not execute.
  //
  // Empty deps so this is an unmount cleanup and nothing else; the guard makes
  // it a no-op for the ordinary case, where the close path has already run and
  // cleared `restoreTo`.
  useEffect(() => {
    return () => {
      if (restoreTo.current === null) return;
      restoreFocus.current();
    };
  }, []);

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
