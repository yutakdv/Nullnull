import type { components } from '@nullnull/api-client';
import { StateLabel, type SourceState } from './StateLabel.js';
import styles from './CrowdLevel.module.css';

// Figma: `CrowdBar / Forecast` and `CrowdState / Live` share one shape here.
// Level plus its own words plus the data state, because a bar alone would be
// colour-only information.

type CrowdMetric = components['schemas']['CrowdMetric'];

export interface CrowdLevelProps {
  crowd: CrowdMetric | null;
  /** Unavailable data must say so rather than rendering an empty bar. */
  unavailableReason?: string;
  /** Localized wording for the six data states, passed to StateLabel. */
  stateLabels?: Partial<Record<SourceState, string>>;
  /**
   * Accessible name for the bar, already interpolated by the caller — e.g.
   * "5단계 중 2번째". The glyph row is meaningless without it.
   */
  levelLabel?: string;
}

export const CROWD_LEVEL_STEPS = 5;

export function CrowdLevel({
  crowd,
  unavailableReason,
  stateLabels,
  levelLabel,
}: CrowdLevelProps) {
  if (!crowd) {
    return (
      <span className={styles.row}>
        <StateLabel labels={stateLabels} state="UNAVAILABLE" />
        {unavailableReason ? (
          <span className={styles.reason}>{unavailableReason}</span>
        ) : null}
      </span>
    );
  }

  // The contract uses a 1..5 ordinal scale. Values outside it draw no bar
  // rather than being clamped into a different crowd level.
  const level = /^[1-5]$/.test(crowd.ordinalLevel ?? '')
    ? Number(crowd.ordinalLevel)
    : null;

  return (
    <span className={styles.row}>
      {level === null ? null : (
        <span
          aria-label={levelLabel ?? `${CROWD_LEVEL_STEPS}단계 중 ${level}번째`}
          className={styles.bar}
          role="img"
        >
          {Array.from({ length: CROWD_LEVEL_STEPS }, (_, i) => (
            <span key={i} className={styles.step} data-filled={i < level || undefined} />
          ))}
        </span>
      )}
      {/* crowd.label is NOT rendered. The contract calls it "diagnostic, not
          display copy: it is not localized", and says to build user-facing
          wording from `state` and `provenance.metricDefinition` instead. The
          state label below is that wording. */}
      <StateLabel labels={stateLabels} state={crowd.state} />
    </span>
  );
}
