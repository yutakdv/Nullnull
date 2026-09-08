import styles from './MetricDelta.module.css';

// Figma: `Data / MetricDelta` (C06). state=improved|unchanged|worsened|
// unavailable.
//
// A number appears only when the server says the pair is comparable. With
// comparisonEligible=false the component shows the reason instead and never
// substitutes 0 or "보통" for a missing value (CLAUDE.md invariant 8,
// AGENTS.md rule 6). Callers pass the server's verdict; they do not compute
// it from the two values.

export interface MetricDeltaProps {
  label: string;
  /** Server verdict. False renders the reason, never a number. */
  eligible: boolean;
  /** Shown when eligible. Pre-formatted by the caller, e.g. "4 · 혼잡 → 1 · 매우 여유". */
  value?: string;
  direction?: 'improved' | 'worsened' | 'unchanged';
  /** Why the comparison is unavailable. Required when eligible is false. */
  reason?: string;
}

const ARROWS = { improved: '↓', worsened: '↑', unchanged: '' } as const;

export function MetricDelta({
  label,
  eligible,
  value,
  direction = 'unchanged',
  reason,
}: MetricDeltaProps) {
  return (
    <div className={styles.row}>
      <span className={styles.label}>{label}</span>
      {eligible && value ? (
        <span className={styles.value} data-direction={direction}>
          {ARROWS[direction] ? <span aria-hidden="true">{ARROWS[direction]}</span> : null}
          {value}
        </span>
      ) : (
        <span className={styles.unavailable}>{reason ?? '확인 불가'}</span>
      )}
    </div>
  );
}
