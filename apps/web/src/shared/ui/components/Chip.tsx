import type { ButtonHTMLAttributes } from 'react';
import styles from './Chip.module.css';

// Figma: `Form / Chip` (C48). Variants state=기본|선택, size=md|sm.
// Selection is a UI state, never an API data state (COMPONENT_CATALOG §1).
// Rendered as a button so keyboard and screen readers get it for free; the
// selected state is announced via aria-pressed rather than colour alone.

export interface ChipProps
  extends Omit<ButtonHTMLAttributes<HTMLButtonElement>, 'children'> {
  label: string;
  selected?: boolean;
  size?: 'md' | 'sm';
  /** Why the chip is unavailable. Required whenever disabled. */
  disabledReason?: string;
}

export function Chip({
  label,
  selected = false,
  size = 'md',
  disabled,
  disabledReason,
  ...rest
}: ChipProps) {
  return (
    <button
      type="button"
      className={`${styles.chip} ${styles[size]}`}
      data-selected={selected || undefined}
      aria-pressed={selected}
      disabled={disabled}
      title={disabled ? disabledReason : undefined}
      {...rest}
    >
      {label}
    </button>
  );
}
