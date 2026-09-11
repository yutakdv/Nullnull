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

  // The 1..4 scale is documented, not invented: COMPONENT_CATALOG.md:136
  // defines CrowdLevel as "1~4단계, 데이터 없음, 척도 밖" and
  // FIGMA_CHANGE_REQUESTS.md:201 fixes the wording (1·매우 여유 … 4·혼잡).
  //
  // The server cannot fill it yet — JdbcKtoForecastSnapshotStore inserts
  // ordinal_level as a NULL literal — so today every reading lands here as
  // null and draws no bars. That is the agreed state, not a bug: FCR-029
  // says the card ships without a crowd figure until the contract carries
  // one. The remaining gap is the WORD, not the number: the design wants
  // "4 · 혼잡" and `label` is diagnostic text we are forbidden to render, so
  // there is nowhere to read "혼잡" from. Backend/AI is confirming the
  // vocabulary against a real response before adding it to the contract.
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
