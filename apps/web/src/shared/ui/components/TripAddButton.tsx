import type { ButtonHTMLAttributes } from 'react';
import { IconCheck, IconClose, IconPlus } from '../icons/index.js';
import styles from './TripAddButton.module.css';

// Figma: `Action / TripAddButton` (C04). state=idle|saved|no-trip|duplicate|
// loading|error.
//
// Saving a place creates a TripCandidate. It never creates a TripItem and
// never bumps the trip schedule version (CLAUDE.md invariants 1 and 2), so
// the copy here says 담기, never 일정.

export type TripAddState =
  | 'idle'
  | 'saved'
  | 'no-trip'
  | 'duplicate'
  | 'loading'
  | 'error';

export interface TripAddButtonProps
  extends Omit<ButtonHTMLAttributes<HTMLButtonElement>, 'children' | 'disabled'> {
  state: TripAddState;
}

/** Each state needs its own accessible name; the glyph alone is ambiguous. */
const labels: Record<TripAddState, string> = {
  idle: '내 여행에 담기',
  saved: '담았어요',
  duplicate: '이미 담아둔 장소예요',
  'no-trip': '여행을 만들고 담기',
  loading: '담는 중이에요',
  error: '담지 못했어요. 다시 시도',
};

export function TripAddButton({ state, ...rest }: TripAddButtonProps) {
  return (
    <button
      type="button"
      className={styles.button}
      data-state={state}
      aria-label={labels[state]}
      aria-busy={state === 'loading' || undefined}
      // Only the in-flight request blocks input. A failure must stay
      // retryable, and a duplicate must stay navigable to the existing one.
      disabled={state === 'loading'}
      {...rest}
    >
      {state === 'saved' || state === 'duplicate' ? (
        <IconCheck size={16} />
      ) : state === 'error' ? (
        <IconClose size={16} />
      ) : (
        <IconPlus size={16} />
      )}
    </button>
  );
}
