import type { components } from '@nullnull/api-client';
import { CrowdLevel } from './CrowdLevel.js';
import { DataAttribution } from './DataAttribution.js';
import { LockControl, type LockKind } from './LockControl.js';
import { MustVisitBadge } from './MustVisitBadge.js';
import styles from './TripItemCard.module.css';

// Figma: `Card / TripItem` (C38). Takes one TripItem from getTrip.
//
// The four constraint types are independent and none is released
// automatically (CLAUDE.md invariant 7). This card renders whichever are
// present and asks for each separately; it never infers one from another and
// never hides a lock to simplify the row.

type TripItem = components['schemas']['TripItem'];
type ConstraintType = components['schemas']['ConstraintType'];
type CrowdMetric = components['schemas']['CrowdMetric'];

export type TripItemCardState = 'view' | 'editing' | 'changed' | 'conflict' | 'optimized';

export interface TripItemCardProps {
  item: TripItem;
  state?: TripItemCardState;
  /** Crowd is fetched separately from the trip itself. */
  crowd?: CrowdMetric | null;
  /** Called with the constraint the user asked to change, never a batch. */
  onToggleConstraint?: (type: ConstraintType) => void;
  onOpenMenu?: () => void;
}

const LOCK_LABELS: Record<Exclude<ConstraintType, 'MUST_VISIT'>, string> = {
  DATE: '날짜 고정',
  TIME: '시간 고정',
  RESERVATION: '예약 고정',
};

const LOCK_KINDS: Record<Exclude<ConstraintType, 'MUST_VISIT'>, LockKind> = {
  DATE: 'date',
  TIME: 'time',
  RESERVATION: 'reservation',
};

/** Badge wording per state; a changed row must say so in words, not colour. */
const STATE_BADGES: Partial<Record<TripItemCardState, string>> = {
  changed: '변경됨',
  conflict: '시간 겹침',
  optimized: '최적화 반영',
};

export function TripItemCard({
  item,
  state = 'view',
  crowd,
  onToggleConstraint,
  onOpenMenu,
}: TripItemCardProps) {
  const present = new Set(item.constraints.map((c) => c.type));
  const badge = STATE_BADGES[state];

  return (
    <article className={styles.card} data-state={state}>
      <header className={styles.header}>
        {present.has('MUST_VISIT') ? <MustVisitBadge /> : null}
        <h3 className={styles.name}>{item.place.name}</h3>
        {badge ? <span className={styles.badge}>{badge}</span> : null}
        <span className={styles.time}>{item.startTime ?? '시간 미정'}</span>
        <button
          type="button"
          className={styles.menu}
          onClick={onOpenMenu}
          aria-label={`${item.place.name} 항목 메뉴`}
        >
          ⋯
        </button>
      </header>

      {crowd !== undefined ? <CrowdLevel crowd={crowd} /> : null}
      {crowd ? <DataAttribution provenance={crowd.provenance} compact /> : null}

      <div className={styles.locks}>
        {(['DATE', 'TIME', 'RESERVATION'] as const).map((type) => {
          if (!present.has(type) && type === 'RESERVATION') return null;
          const locked = present.has(type);
          return (
            <LockControl
              key={type}
              kind={LOCK_KINDS[type]}
              label={LOCK_LABELS[type]}
              state={
                type === 'RESERVATION'
                  ? 'reservation-locked'
                  : locked
                    ? 'user-locked'
                    : 'unlocked'
              }
              disabledReason={type === 'RESERVATION' ? '예약에서 관리해요' : undefined}
              onClick={() => onToggleConstraint?.(type)}
            />
          );
        })}
      </div>
    </article>
  );
}
