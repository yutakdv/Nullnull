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
   * "4단계 중 2번째". The glyph row is meaningless without it.
   */
  levelLabel?: string;
}

const STEPS = 4;

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

  // ordinalLevel is an untyped, enum-less string in the contract
  // (openapi.yaml: `type: [string, "null"]`), so the 1..4 read here is a
  // guess. Real KTO-shaped data uses words — the feed fixture carries
  // "보통" — and those draw no bars at all rather than a wrong number, which
  // is the safe direction (invariant 8). Asked Backend/AI for the vocabulary:
  // if it is ordinal words the bar needs a mapping, and if it is 1..4 the
  // contract should say so with an enum.
  const parsed = Number(crowd.ordinalLevel);
  const level =
    Number.isInteger(parsed) && parsed >= 1 && parsed <= STEPS ? parsed : null;

  return (
    <span className={styles.row}>
      {level === null ? null : (
        <span
          aria-label={levelLabel ?? `${STEPS}단계 중 ${level}번째`}
          className={styles.bar}
          role="img"
        >
          {Array.from({ length: STEPS }, (_, i) => (
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
