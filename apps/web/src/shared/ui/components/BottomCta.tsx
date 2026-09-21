import type { ReactNode, Ref } from 'react';
import styles from './BottomCta.module.css';

// Figma: `Action / Bottom CTA` (C11). type=단독|보조링크.
export interface BottomCtaProps {
  buttonRef?: Ref<HTMLButtonElement>;
  label: string;
  onClick?: () => void;
  disabled?: boolean;
  /** Pin this action bar to the viewport bottom inside an active flow. */
  fixed?: boolean;
  /** Optional secondary action rendered under the primary button. */
  secondary?: ReactNode;
  /** Secondary actions need a touch target; notes only need reading space. */
  secondaryKind?: 'action' | 'note';
  type?: 'button' | 'submit';
}

export function BottomCta({
  buttonRef,
  label,
  onClick,
  disabled,
  fixed = false,
  secondary,
  secondaryKind = 'action',
  type = 'button',
}: BottomCtaProps) {
  return (
    <div
      className={styles.bar}
      data-fixed={fixed || undefined}
      data-secondary-kind={secondary ? secondaryKind : undefined}
    >
      <button
        ref={buttonRef}
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
