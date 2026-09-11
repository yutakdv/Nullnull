import type { components } from '@nullnull/api-client';
import styles from './StateLabel.module.css';

// Figma: `Data / StateLabel` (C07). One label per SourceState.
//
// The six states are a data state from the API, never a UI state
// (COMPONENT_CATALOG §1). Synthetic seeds, live observation, forecast,
// replay, stale readings and missing data must stay distinguishable on
// screen (AGENTS.md rule 6), so each state carries its own words and is
// never conveyed by colour alone.

export type SourceState = components['schemas']['SourceState'];

/**
 * Korean wording, matching the Figma variants and the S15 data guide.
 *
 * A default, not the only copy: a caller inside the app passes the selected
 * locale's words through `labels`. The default keeps this renderable without
 * an I18nProvider, which is how the Storybook stories mount it.
 */
const LABELS: Record<SourceState, string> = {
  LIVE: '실시간 관측',
  FORECAST: '공식 혼잡 예측',
  QUALITATIVE: '공식 혼잡 예측 범위 밖',
  STALE: '업데이트 지연',
  UNAVAILABLE: '현재 데이터 없음',
  REPLAY: '과거 관측 재생 · 실시간 아님',
};

export interface StateLabelProps {
  state: SourceState;
  /**
   * Localized wording for each state, from the caller.
   *
   * Figma pins the Korean strings ("문구 임의 변경 금지") and this component
   * owns them, so the app passes the same distinction in the chosen language
   * rather than each screen inventing its own.
   */
  labels?: Partial<Record<SourceState, string>>;
  /**
   * Observation or target time. Shown next to the label because a state
   * without a reference time cannot be judged (CLAUDE.md invariant 8).
   */
  observedAt?: string | null;
}

export function StateLabel({ state, labels, observedAt }: StateLabelProps) {
  return (
    <span className={styles.label} data-state={state}>
      {labels?.[state] ?? LABELS[state]}
      {observedAt ? <span className={styles.time}>{observedAt}</span> : null}
    </span>
  );
}
