import { useOptionalI18n } from '../../../i18n/I18nProvider.js';
import type { MessageKey } from '../../../i18n/messages.js';
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

/** Every string this bar owns. */
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

/**
 * Korean wording, matching the Figma variants (C03).
 *
 * Only for a render with no I18nProvider at all - a bare unit test; the app and
 * every Storybook story run inside one (.storybook/preview.tsx). There an
 * omitted label takes the locale's word (the `decision.*` keys below), so a
 * caller that leaves one out cannot put Korean on an English screen
 * (FE-001-T4).
 */
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

const MESSAGE_KEYS: Record<keyof DecisionBarLabels, MessageKey> = {
  apply: 'decision.apply',
  keep: 'decision.keep',
  applying: 'decision.applying',
  applied: 'decision.applied',
  staleMessage: 'decision.staleMessage',
  staleAction: 'decision.staleAction',
  failedMessage: 'decision.failedMessage',
  failedAction: 'decision.failedAction',
  groupLabel: 'decision.groupLabel',
};

export interface DecisionBarProps {
  state: DecisionState;
  /**
   * The caller's own wording, for any string it wants to differ from the
   * locale's. Each omitted one takes the locale's word.
   */
  labels?: Partial<DecisionBarLabels>;
  onApply?: () => void;
  onKeep?: () => void;
  /** stale and failed offer one recovery action instead of a decision. */
  onRecover?: () => void;
  message?: string;
}

export function DecisionBar({
  state,
  labels,
  onApply,
  onKeep,
  onRecover,
  message,
}: DecisionBarProps) {
  const i18n = useOptionalI18n();
  // The caller's words, else the locale's, else - only with no provider at all
  // - the Korean defaults above.
  const word = (key: keyof DecisionBarLabels) =>
    labels?.[key] ?? i18n?.t(MESSAGE_KEYS[key]) ?? DEFAULT_LABELS[key];
  const groupLabel = word('groupLabel');

  if (state === 'stale' || state === 'failed') {
    const staleOrFailed = state === 'stale';
    return (
      <div className={styles.bar} role="group" aria-label={groupLabel}>
        <p className={styles.message}>
          {message ?? (staleOrFailed ? word('staleMessage') : word('failedMessage'))}
        </p>
        <button type="button" className={styles.primary} onClick={onRecover}>
          {staleOrFailed ? word('staleAction') : word('failedAction')}
        </button>
      </div>
    );
  }

  if (state === 'applied') {
    return (
      <div className={styles.bar} role="group" aria-label={groupLabel}>
        <p className={styles.message}>{message ?? word('applied')}</p>
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
        {busy ? word('applying') : word('apply')}
      </button>
      {/* Both actions lock while a decision is in flight; an already-sent
          APPLY or KEEP cannot be withdrawn from this bar. */}
      <button type="button" className={styles.secondary} onClick={onKeep} disabled={busy}>
        {word('keep')}
      </button>
    </div>
  );
}
