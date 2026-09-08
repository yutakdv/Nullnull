import type { components } from '@nullnull/api-client';
import { StateLabel } from './StateLabel.js';
import styles from './CrowdLevel.module.css';

// Figma: `CrowdBar / Forecast` and `CrowdState / Live` share one shape here.
// Level plus its own words plus the data state, because a bar alone would be
// colour-only information.

type CrowdMetric = components['schemas']['CrowdMetric'];

export interface CrowdLevelProps {
  crowd: CrowdMetric | null;
  /** Unavailable data must say so rather than rendering an empty bar. */
  unavailableReason?: string;
}

const STEPS = 4;

export function CrowdLevel({ crowd, unavailableReason }: CrowdLevelProps) {
  if (!crowd) {
    return (
      <span className={styles.row}>
        <StateLabel state="UNAVAILABLE" />
        {unavailableReason ? (
          <span className={styles.reason}>{unavailableReason}</span>
        ) : null}
      </span>
    );
  }

  // ordinalLevel is a string in the contract; only a parsed 1..4 draws bars.
  const parsed = Number(crowd.ordinalLevel);
  const level =
    Number.isInteger(parsed) && parsed >= 1 && parsed <= STEPS ? parsed : null;

  return (
    <span className={styles.row}>
      {level === null ? null : (
        <span
          className={styles.bar}
          role="img"
          aria-label={`${STEPS}단계 중 ${level}번째`}
        >
          {Array.from({ length: STEPS }, (_, i) => (
            <span key={i} className={styles.step} data-filled={i < level || undefined} />
          ))}
        </span>
      )}
      <span className={styles.label}>{crowd.label}</span>
      <StateLabel state={crowd.state} />
    </span>
  );
}
