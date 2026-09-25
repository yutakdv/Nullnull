import type { components } from '@nullnull/api-client';
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

export interface CandidateCardProps {
  candidate: TripCandidate;
  crowd?: CrowdMetric | null;
  onSchedule?: (candidateId: string) => void;
  onRemove?: (candidateId: string) => void;
}

export function CandidateCard({
  candidate,
  crowd,
  onSchedule,
  onRemove,
}: CandidateCardProps) {
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
          {scheduled ? '일정에 넣었어요' : '날짜·시간 없이 담아둔 장소예요'}
        </p>
      </div>
      <div className={styles.actions}>
        {scheduled ? null : (
          <button
            type="button"
            className={styles.primary}
            onClick={() => onSchedule?.(candidate.id)}
          >
            일정에 넣기
          </button>
        )}
        <button
          type="button"
          className={styles.secondary}
          onClick={() => onRemove?.(candidate.id)}
        >
          담기 취소
        </button>
      </div>
    </article>
  );
}
