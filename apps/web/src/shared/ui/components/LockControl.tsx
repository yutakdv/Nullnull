import type { ButtonHTMLAttributes } from 'react';
import { IconDateLock, IconReservation, IconTimeLock } from '../icons/index.js';
import styles from './LockControl.module.css';

// Figma: `Form / LockControl` (C08). state=unlocked|user-locked|
// reservation-locked|disabled.
//
// MUST_VISIT, DATE, TIME and RESERVATION locks are independent and are never
// released automatically (CLAUDE.md invariant 7). This control therefore only
// reports one lock and asks; it never implies that toggling one affects
// another. Releasing a lock is a confirmed action the caller owns.

export type LockKind = 'date' | 'time' | 'reservation';
export type LockState = 'unlocked' | 'user-locked' | 'reservation-locked' | 'disabled';

export interface LockControlProps
  extends Omit<ButtonHTMLAttributes<HTMLButtonElement>, 'children' | 'disabled'> {
  kind: LockKind;
  state: LockState;
  label: string;
  /** Why this lock cannot be changed here. Required when state is disabled. */
  disabledReason?: string;
}

const icons = {
  date: IconDateLock,
  time: IconTimeLock,
  reservation: IconReservation,
};

export function LockControl({
  kind,
  state,
  label,
  disabledReason,
  ...rest
}: LockControlProps) {
  const Glyph = icons[kind];
  const locked = state === 'user-locked' || state === 'reservation-locked';
  // A reservation lock is owned by the booking, not by this control, so it is
  // shown as locked but not offered as a toggle.
  const interactive = state === 'unlocked' || state === 'user-locked';

  return (
    <button
      type="button"
      className={styles.lock}
      data-state={state}
      aria-pressed={interactive ? locked : undefined}
      disabled={!interactive}
      title={!interactive ? disabledReason : undefined}
      {...rest}
    >
      <Glyph size={12} />
      {label}
    </button>
  );
}
