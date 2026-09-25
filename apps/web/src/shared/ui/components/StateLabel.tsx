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
type QualityFlag = components['schemas']['DataProvenance']['qualityFlags'][number];
/** The words a label can take: one per state, and the live-under-incident one. */
export type StateWording = SourceState | 'PROVIDER_INCIDENT';

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

/**
 * A LIVE reading whose provider reports an incident (A-068, owner 2026-09-25).
 * The reading stays on screen; the word "live" does not, because the flag says
 * the provider itself cannot vouch for it (CMP-ATT-004: the state is not
 * hidden). Only LIVE is relabelled - every other state already says it is not
 * a live observation.
 */
const INCIDENT_LABEL = '제공처 장애';

export interface StateLabelProps {
  state: SourceState;
  /**
   * Localized wording for each state, from the caller.
   *
   * Figma pins the Korean strings ("문구 임의 변경 금지") and this component
   * owns them, so the app passes the same distinction in the chosen language
   * rather than each screen inventing its own.
   */
  labels?: Partial<Record<StateWording, string>>;
  /** The reading's `provenance.qualityFlags`; only PROVIDER_INCIDENT changes the words. */
  qualityFlags?: readonly QualityFlag[];
  /**
   * Observation or target time. Shown next to the label because a state
   * without a reference time cannot be judged (CLAUDE.md invariant 8).
   */
  observedAt?: string | null;
}

export function StateLabel({ state, labels, observedAt, qualityFlags }: StateLabelProps) {
  const incident =
    state === 'LIVE' && (qualityFlags?.includes('PROVIDER_INCIDENT') ?? false);
  return (
    <span
      className={styles.label}
      data-quality={incident ? 'PROVIDER_INCIDENT' : undefined}
      data-state={state}
    >
      {incident
        ? (labels?.PROVIDER_INCIDENT ?? INCIDENT_LABEL)
        : (labels?.[state] ?? LABELS[state])}
      {observedAt ? <span className={styles.time}>{observedAt}</span> : null}
    </span>
  );
}
