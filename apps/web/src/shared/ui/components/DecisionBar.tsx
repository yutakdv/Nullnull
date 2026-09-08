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

export interface DecisionBarProps {
  state: DecisionState;
  /** Primary label, e.g. "이 변경 적용". */
  applyLabel?: string;
  onApply?: () => void;
  onKeep?: () => void;
  /** stale and failed offer one recovery action instead of a decision. */
  onRecover?: () => void;
  message?: string;
}

export function DecisionBar({
  state,
  applyLabel = '이 변경 적용',
  onApply,
  onKeep,
  onRecover,
  message,
}: DecisionBarProps) {
  if (state === 'stale' || state === 'failed') {
    return (
      <div className={styles.bar} role="group" aria-label="최적화 결정">
        <p className={styles.message}>
          {message ??
            (state === 'stale'
              ? '다른 곳에서 일정이 바뀌었어요. 최신 일정 기준으로 다시 계산해야 적용할 수 있어요.'
              : '일정은 바뀌지 않았어요. 네트워크 상태를 확인하고 다시 시도해주세요.')}
        </p>
        <button type="button" className={styles.primary} onClick={onRecover}>
          {state === 'stale' ? '최신 일정으로 다시 계산' : '다시 시도'}
        </button>
      </div>
    );
  }

  if (state === 'applied') {
    return (
      <div className={styles.bar} role="group" aria-label="최적화 결정">
        <p className={styles.message}>{message ?? '일정을 업데이트했어요'}</p>
      </div>
    );
  }

  const busy = state === 'applying';
  return (
    <div className={styles.bar} role="group" aria-label="최적화 결정">
      <button
        type="button"
        className={styles.primary}
        onClick={onApply}
        disabled={busy}
        aria-busy={busy || undefined}
      >
        {busy ? '적용하는 중이에요' : applyLabel}
      </button>
      {/* Both actions lock while a decision is in flight; an already-sent
          APPLY or KEEP cannot be withdrawn from this bar. */}
      <button type="button" className={styles.secondary} onClick={onKeep} disabled={busy}>
        현재 일정 유지
      </button>
    </div>
  );
}
