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
  /**
   * The direction as a WORD, for anyone who cannot see the arrow (#279 하2).
   *
   * The arrow is `aria-hidden` — it is a glyph, and a screen reader saying
   * "down arrow 55" is worse than it not saying it. That was survivable while
   * `value` carried a minus sign, because the sign said the direction out loud.
   * Now that the sign is gone (the arrow and the "-" were the same word twice),
   * this is the ONLY thing left that tells a non-sighted reader which way the
   * number went, so it is rendered visually-hidden beside the figure.
   *
   * Localized by the caller, like every other string here.
   */
  directionLabel?: string;
  /** Why the comparison is unavailable. Required when eligible is false. */
  reason?: string;
}

const ARROWS = { improved: '↓', worsened: '↑', unchanged: '' } as const;

export function MetricDelta({
  label,
  eligible,
  value,
  direction = 'unchanged',
  directionLabel,
  reason,
}: MetricDeltaProps) {
  return (
    <div className={styles.row}>
      <span className={styles.label}>{label}</span>
      {eligible && value ? (
        <span className={styles.value} data-direction={direction}>
          {ARROWS[direction] ? <span aria-hidden="true">{ARROWS[direction]}</span> : null}
          {value}
          {/* After the number, so it reads "55 감소" rather than "감소 55".
              Rendered only when the caller supplies one: an omitted label is a
              silent regression to the arrow-only state, which the card's own
              test asserts against. */}
          {directionLabel ? (
            <span className={styles.srOnly}>{directionLabel}</span>
          ) : null}
        </span>
      ) : (
        <span className={styles.unavailable}>{reason ?? '확인 불가'}</span>
      )}
    </div>
  );
}
