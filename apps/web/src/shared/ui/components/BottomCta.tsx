import type { ReactNode } from 'react';
import styles from './BottomCta.module.css';

// Figma: `Action / Bottom CTA` (C11). type=단독|보조링크.
export interface BottomCtaProps {
  label: string;
  onClick?: () => void;
  disabled?: boolean;
  /** Optional secondary action rendered under the primary button. */
  secondary?: ReactNode;
  type?: 'button' | 'submit';
}

export function BottomCta({
  label,
  onClick,
  disabled,
  secondary,
  type = 'button',
}: BottomCtaProps) {
  return (
    <div className={styles.bar}>
      <button
        type={type}
        className={styles.primary}
        onClick={onClick}
        disabled={disabled}
      >
        {label}
      </button>
      {secondary ? <div className={styles.secondary}>{secondary}</div> : null}
    </div>
  );
}
