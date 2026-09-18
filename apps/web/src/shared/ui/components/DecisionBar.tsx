import styles from './DecisionBar.module.css';

// Figma: `Action / DecisionBar` (C03). state=preview|applying|applied|stale|
// failed.
//
// The optimizer only ever produces a preview. Nothing here changes the trip
// until the user picks APPLY, and KEEP, a failure, an expiry or a stale run
// all leave the schedule untouched (CLAUDE.md invariants 3 and 4). The bar
// therefore always offers a way to decline, and it never presents a single
// affirmative button.

export type DecisionState = 'preview' | 'applying' | 'applied' | 'stale' | 'failed';

/**
 * Korean wording, matching the Figma variants (C03).
 *
 * A default, not the only copy: a caller inside the app passes the selected
 * locale's words through `labels`. The default keeps this renderable without
 * an I18nProvider, which is how the Storybook stories mount it.
 */
export interface DecisionBarLabels {
  apply: string;
  keep: string;
  applying: string;
  applied: string;
  staleMessage: string;
  staleAction: string;
  failedMessage: string;
  failedAction: string;
  /** Accessible name for the bar's role="group". */
  groupLabel: string;
}

const DEFAULT_LABELS: DecisionBarLabels = {
  apply: '이 변경 적용',
  keep: '현재 일정 유지',
  applying: '적용하는 중이에요',
  applied: '일정을 업데이트했어요',
  staleMessage:
    '다른 곳에서 일정이 바뀌었어요. 최신 일정 기준으로 다시 계산해야 적용할 수 있어요.',
  staleAction: '최신 일정으로 다시 계산',
  failedMessage: '일정은 바뀌지 않았어요. 네트워크 상태를 확인하고 다시 시도해주세요.',
  failedAction: '다시 시도',
  groupLabel: '최적화 결정',
};

export interface DecisionBarProps {
  state: DecisionState;
  /**
   * Primary label, e.g. "이 변경 적용".
   *
   * @deprecated Use `labels.apply` instead. Kept so existing callers and
   * Storybook stories do not break; `labels.apply` wins when both are given.
   */
  applyLabel?: string;
  /** Localized wording for every string this bar owns, from the caller. */
  labels?: Partial<DecisionBarLabels>;
  onApply?: () => void;
  onKeep?: () => void;
  /** stale and failed offer one recovery action instead of a decision. */
  onRecover?: () => void;
  message?: string;
}

export function DecisionBar({
  state,
  applyLabel,
  labels,
  onApply,
  onKeep,
  onRecover,
  message,
}: DecisionBarProps) {
  const groupLabel = labels?.groupLabel ?? DEFAULT_LABELS.groupLabel;

  if (state === 'stale' || state === 'failed') {
    const staleOrFailed = state === 'stale';
    return (
      <div className={styles.bar} role="group" aria-label={groupLabel}>
        <p className={styles.message}>
          {message ??
            (staleOrFailed
              ? (labels?.staleMessage ?? DEFAULT_LABELS.staleMessage)
              : (labels?.failedMessage ?? DEFAULT_LABELS.failedMessage))}
        </p>
        <button type="button" className={styles.primary} onClick={onRecover}>
          {staleOrFailed
            ? (labels?.staleAction ?? DEFAULT_LABELS.staleAction)
            : (labels?.failedAction ?? DEFAULT_LABELS.failedAction)}
        </button>
      </div>
    );
  }

  if (state === 'applied') {
    return (
      <div className={styles.bar} role="group" aria-label={groupLabel}>
        <p className={styles.message}>
          {message ?? labels?.applied ?? DEFAULT_LABELS.applied}
        </p>
      </div>
    );
  }

  const busy = state === 'applying';
  return (
    <div className={styles.bar} role="group" aria-label={groupLabel}>
      <button
        type="button"
        className={styles.primary}
        onClick={onApply}
        disabled={busy}
        aria-busy={busy || undefined}
      >
        {busy
          ? (labels?.applying ?? DEFAULT_LABELS.applying)
          : (labels?.apply ?? applyLabel ?? DEFAULT_LABELS.apply)}
      </button>
      {/* Both actions lock while a decision is in flight; an already-sent
          APPLY or KEEP cannot be withdrawn from this bar. */}
      <button type="button" className={styles.secondary} onClick={onKeep} disabled={busy}>
        {labels?.keep ?? DEFAULT_LABELS.keep}
      </button>
    </div>
  );
}
