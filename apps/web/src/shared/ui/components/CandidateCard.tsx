import type { components } from '@nullnull/api-client';
import { useOptionalI18n } from '../../../i18n/I18nProvider.js';
import type { MessageKey } from '../../../i18n/messages.js';
import { CrowdLevel } from './CrowdLevel.js';
import { PlaceAttribution } from './PlaceAttribution.js';
import styles from './CandidateCard.module.css';

// Figma: `Card / Candidate` (C01). Takes one TripCandidate.
//
// A candidate has no date or time. Scheduling it is a separate, explicit act
// that creates a TripItem (CLAUDE.md invariants 1 and 2), so this card offers
// 일정에 넣기 rather than implying it is already in the schedule.

type TripCandidate = components['schemas']['TripCandidate'];
type CrowdMetric = components['schemas']['CrowdMetric'];

/** Every string this card owns. */
export interface CandidateCardLabels {
  schedule: string;
  scheduled: string;
  unscheduled: string;
  remove: string;
}

/**
 * Korean wording, matching the Figma card (C01).
 *
 * Only for a render with no I18nProvider at all - a bare unit test; the app and
 * every Storybook story run inside one (.storybook/preview.tsx). There an
 * omitted label takes the locale's `candidateCard.*` word (FE-001-T4).
 */
const DEFAULT_LABELS: CandidateCardLabels = {
  schedule: '일정에 넣기',
  scheduled: '일정에 넣었어요',
  unscheduled: '날짜·시간 없이 담아둔 장소예요',
  remove: '담기 취소',
};

const MESSAGE_KEYS: Record<keyof CandidateCardLabels, MessageKey> = {
  schedule: 'candidateCard.schedule',
  scheduled: 'candidateCard.scheduled',
  unscheduled: 'candidateCard.unscheduled',
  remove: 'candidateCard.remove',
};

export interface CandidateCardProps {
  candidate: TripCandidate;
  crowd?: CrowdMetric | null;
  onSchedule?: (candidateId: string) => void;
  onRemove?: (candidateId: string) => void;
  /** The caller's own wording; each omitted one takes the locale's word. */
  labels?: Partial<CandidateCardLabels>;
}

export function CandidateCard({
  candidate,
  crowd,
  onSchedule,
  onRemove,
  labels,
}: CandidateCardProps) {
  const i18n = useOptionalI18n();
  const word = (key: keyof CandidateCardLabels) =>
    labels?.[key] ?? i18n?.t(MESSAGE_KEYS[key]) ?? DEFAULT_LABELS[key];
  const scheduled = candidate.status === 'SCHEDULED';
  return (
    <article className={styles.card}>
      <div className={styles.body}>
        <h3 className={styles.name}>{candidate.place.name}</h3>
        {crowd !== undefined ? <CrowdLevel crowd={crowd ?? null} /> : null}
        {/* CMP-ATT-001: the place's credit, and the forecast's beside it. */}
        <PlaceAttribution
          also={crowd ? [crowd.provenance] : undefined}
          compact
          place={candidate.place}
        />
        <p className={styles.status}>
          {scheduled ? word('scheduled') : word('unscheduled')}
        </p>
      </div>
      <div className={styles.actions}>
        {scheduled ? null : (
          <button
            type="button"
            className={styles.primary}
            onClick={() => onSchedule?.(candidate.id)}
          >
            {word('schedule')}
          </button>
        )}
        <button
          type="button"
          className={styles.secondary}
          onClick={() => onRemove?.(candidate.id)}
        >
          {word('remove')}
        </button>
      </div>
    </article>
  );
}
