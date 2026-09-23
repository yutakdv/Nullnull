/**
 * Whether focus can actually land on this element.
 *
 * Extracted from ConfirmDialog, which learned it the hard way. The restore
 * path used to ask only `isConnected` — so a target that was in the page but
 * could not hold focus was restored to, `.focus()` did nothing, and the
 * fallback never ran because that branch returns. #272 cause ④, found in a
 * browser: the move trigger is `disabled` while its own reorder request is in
 * flight, which is exactly the moment the confirm closes.
 *
 * TripPicker reached the same wall independently and is why this lives here
 * rather than in ConfirmDialog: picking a trip starts the save, the 담기
 * trigger re-renders into TripAddButton's `loading` state, and that state is
 * `disabled` (TripAddButton.tsx) — so `isConnected` passed, `focus()` was a
 * silent no-op and focus was left on <body>. Measured in a browser: one call,
 * `focus() on BUTTON:Adding connected=true`, and no `focusin` after it.
 *
 * One copy rather than two: the rule is the same rule, and a rule that lives
 * in two files has one copy that goes stale first.
 *
 * `closest('[inert]')` and the disabled-fieldset case cannot be expressed as a
 * CSS selector on the candidate itself: `:not([disabled])` does not exclude a
 * button inside a disabled <fieldset>, and `inert` is inherited by
 * descendants. Measured in happy-dom — both a disabled fieldset's button and
 * an inert subtree's button match a plain focusable selector.
 */
export function canTakeFocus(element: HTMLElement): boolean {
  if (!element.isConnected) return false;
  if (element.closest('[inert]') !== null) return false;
  // Covers the element's own `disabled` and an ancestor <fieldset disabled>,
  // which disables its controls without marking them.
  if (element.closest(':disabled') !== null) return false;
  return true;
}

/**
 * Focus `target`, and fall back to the <main> landmark when it will not take
 * focus — the honest last resort, because `focus()` fails silently rather than
 * throwing.
 *
 * <main> keeps the traveller inside the page's content region rather than
 * dropping them on <body>, where the next Tab restarts at the top of the
 * document. Its temporary tabindex must stay until focus leaves: Chromium
 * blurs a focused <main> when tabindex is removed immediately after focus().
 * Removing it on blur preserves the page's original tab order.
 */
export function restoreFocusTo(target: HTMLElement | null): void {
  if (target !== null && canTakeFocus(target)) {
    target.focus();
    // Landing is verified rather than assumed: the checks above cover the
    // causes we know of, and `focus()` still fails silently for any we do not.
    if (document.activeElement === target) return;
  }
  const main = document.querySelector<HTMLElement>('main');
  if (!main || !main.isConnected) return;
  const hadTabIndex = main.hasAttribute('tabindex');
  if (!hadTabIndex) main.setAttribute('tabindex', '-1');
  main.focus();
  if (hadTabIndex) return;
  if (document.activeElement !== main) {
    main.removeAttribute('tabindex');
    return;
  }
  main.addEventListener('blur', () => main.removeAttribute('tabindex'), { once: true });
}
