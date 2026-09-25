import type { components } from '@nullnull/api-client';
import { useOptionalI18n } from '../../../i18n/I18nProvider.js';
import type { MessageKey } from '../../../i18n/messages.js';
import { StateLabel, type StateWording } from './StateLabel.js';
import styles from './CrowdLevel.module.css';

// Figma: `CrowdBar / Forecast` and `CrowdState / Live` share one shape here.
// Level plus its own words plus the data state, because a bar alone would be
// colour-only information.

type CrowdMetric = components['schemas']['CrowdMetric'];
type CrowdOrdinal = 1 | 2 | 3 | 4 | 5;

const LEVEL_LABELS: Record<CrowdOrdinal, string> = {
  1: '매우 여유',
  2: '여유',
  3: '보통',
  4: '혼잡',
  5: '매우 혼잡',
};

const SEOUL_LEVEL_LABELS: Partial<Record<CrowdOrdinal, string>> = {
  1: '여유',
  2: '보통',
  3: '약간 붐빔',
  4: '붐빔',
};

export interface CrowdLevelProps {
  crowd: CrowdMetric | null;
  /** Unavailable data must say so rather than rendering an empty bar. */
  unavailableReason?: string;
  /** Localized wording for the six data states, passed to StateLabel. */
  stateLabels?: Partial<Record<StateWording, string>>;
  /**
   * Accessible name for the bar, when the caller must override it. By default
   * it is built here from the steps the reading's source publishes - "4단계 중
   * 3번째" for Seoul - never from the cells the bar has: the contract forbids
   * "Nth of five" for a source that does not publish five.
   */
  levelLabel?: string;
  /** Localized visible wording for each product stage. */
  levelLabels?: Partial<Record<CrowdOrdinal, string>>;
}

export const CROWD_LEVEL_STEPS = 5;
const GENERIC_CELLS = ['1', '2', '3', '4', '5'];
const SEOUL_CELLS = ['1', '2', '3', '4'];

export function CrowdLevel({
  crowd,
  unavailableReason,
  stateLabels,
  levelLabel,
  levelLabels,
}: CrowdLevelProps) {
  const i18n = useOptionalI18n();
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

  // The scale is the server's (`ordinalScale`, #97 decision (B)): `size`
  // cells, of which this source can fill only `publishedCells`. Seoul reports
  // four stages on cells 1-4 of five and leaves the fifth empty (A-060): its
  // top stage has no ceiling, so it is not this scale's maximum. The cells a
  // source never fills are drawn hollow rather than dropped, so a Seoul 4
  // reads as the top of what Seoul reports, short of the last cell.
  //
  // Without a descriptor the bar is the generic five, and a Seoul reading
  // still never claims a fifth stage. A level is drawn only when it is one of
  // the published cells, token for token - "05" or " 5 " is not coerced.
  const seoul = crowd.provenance.source === 'SEOUL_CITYDATA';
  const steps = crowd.ordinalScale?.size ?? CROWD_LEVEL_STEPS;
  const published =
    crowd.ordinalScale?.publishedCells ?? (seoul ? SEOUL_CELLS : GENERIC_CELLS);
  const level =
    crowd.ordinalLevel !== null &&
    crowd.ordinalLevel !== undefined &&
    published.includes(crowd.ordinalLevel) &&
    /^[1-5]$/.test(crowd.ordinalLevel) &&
    Number(crowd.ordinalLevel) <= steps
      ? (Number(crowd.ordinalLevel) as CrowdOrdinal)
      : null;

  // Words and name come from the locale inside the app, and from the Korean
  // defaults only with no provider (a bare story or test).
  const stageWords = (n: CrowdOrdinal) =>
    levelLabels?.[n] ??
    (i18n
      ? i18n.t(
          (seoul
            ? `live.crowd.seoul.level${String(n)}`
            : `crowd.stage.${String(n)}`) as MessageKey,
        )
      : seoul
        ? SEOUL_LEVEL_LABELS[n]
        : LEVEL_LABELS[n]);
  const publishedSteps = published.length;
  const name = (n: CrowdOrdinal) =>
    levelLabel ??
    (i18n
      ? i18n.t(seoul ? 'live.crowd.seoul.levelLabel' : 'crowd.level', {
          level: n,
          steps: publishedSteps,
        })
      : `${String(publishedSteps)}단계 중 ${String(n)}번째`);

  return (
    <span className={styles.row}>
      {level === null ? null : (
        <>
          <span aria-label={name(level)} className={styles.bar} role="img">
            {Array.from({ length: steps }, (_, i) => (
              <span
                key={i}
                className={styles.step}
                data-filled={i < level || undefined}
                data-unpublished={!published.includes(String(i + 1)) || undefined}
              />
            ))}
          </span>
          <span className={styles.levelText}>
            {level} · {stageWords(level)}
          </span>
        </>
      )}
      {/* crowd.label is NOT rendered. The contract calls it "diagnostic, not
          display copy: it is not localized", and says to build user-facing
          wording from `state` and `provenance.metricDefinition` instead. The
          state label below is that wording. */}
      <StateLabel
        labels={stateLabels}
        qualityFlags={crowd.provenance.qualityFlags}
        state={crowd.state}
      />
    </span>
  );
}
