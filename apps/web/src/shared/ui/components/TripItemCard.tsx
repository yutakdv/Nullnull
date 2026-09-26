import type { components } from '@nullnull/api-client';
import { useOptionalI18n } from '../../../i18n/I18nProvider.js';
import type { MessageKey } from '../../../i18n/messages.js';
import { CrowdLevel } from './CrowdLevel.js';
import { PlaceAttribution } from './PlaceAttribution.js';
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

type Lock = Exclude<ConstraintType, 'MUST_VISIT'>;
type BadgeState = 'changed' | 'conflict' | 'optimized';

/**
 * Every string this card owns but the row menu's name, which carries the
 * place name and so is `menu` on the props. Locks by constraint type, badges
 * by card state.
 */
export type TripItemCardWords = Record<Lock | BadgeState, string> & {
  reservationNote: string;
  timeUnset: string;
};

/**
 * Korean wording, matching the Figma card (C38) and the trip screen's lock row.
 *
 * Only for a render with no I18nProvider at all - a bare unit test; the app and
 * every Storybook story run inside one (.storybook/preview.tsx). There an
 * omitted label takes the locale's word: the trip screen's `trip.lock.*`,
 * `trip.timeUnset` and `trip.item.actions`, and `tripItemCard.badge.*`
 * (FE-001-T4).
 */
const DEFAULT_WORDS: TripItemCardWords = {
  DATE: '날짜 고정',
  TIME: '시간 고정',
  RESERVATION: '예약 고정',
  reservationNote: '예약에서 관리해요',
  timeUnset: '시간 미정',
  changed: '변경됨',
  conflict: '시간 겹침',
  optimized: '최적화 반영',
};

const MESSAGE_KEYS: Record<keyof TripItemCardWords, MessageKey> = {
  DATE: 'trip.lock.DATE',
  TIME: 'trip.lock.TIME',
  RESERVATION: 'trip.lock.RESERVATION',
  reservationNote: 'trip.lock.reservationNote',
  timeUnset: 'trip.timeUnset',
  changed: 'tripItemCard.badge.changed',
  conflict: 'tripItemCard.badge.conflict',
  optimized: 'tripItemCard.badge.optimized',
};

export interface TripItemCardProps {
  item: TripItem;
  state?: TripItemCardState;
  /** Crowd is fetched separately from the trip itself. */
  crowd?: CrowdMetric | null;
  /** Called with the constraint the user asked to change, never a batch. */
  onToggleConstraint?: (type: ConstraintType) => void;
  onOpenMenu?: () => void;
  /**
   * The caller's own wording; each omitted one takes the locale's word.
   * `menu` is the row menu's whole accessible name, place name included.
   */
  labels?: Partial<TripItemCardWords> & { menu?: string };
}

const LOCK_KINDS: Record<Exclude<ConstraintType, 'MUST_VISIT'>, LockKind> = {
  DATE: 'date',
  TIME: 'time',
  RESERVATION: 'reservation',
};

/** A changed row must say so in words, not colour. */
function isBadgeState(state: TripItemCardState): state is BadgeState {
  return state === 'changed' || state === 'conflict' || state === 'optimized';
}

export function TripItemCard({
  item,
  state = 'view',
  crowd,
  onToggleConstraint,
  onOpenMenu,
  labels,
}: TripItemCardProps) {
  const i18n = useOptionalI18n();
  const word = (key: keyof TripItemCardWords) =>
    labels?.[key] ?? i18n?.t(MESSAGE_KEYS[key]) ?? DEFAULT_WORDS[key];
  const present = new Set(item.constraints.map((c) => c.type));
  const badge = isBadgeState(state) ? word(state) : null;
  const menuLabel =
    labels?.menu ??
    i18n?.t('trip.item.actions', { name: item.place.name }) ??
    `${item.place.name} 항목 메뉴`;

  return (
    <article className={styles.card} data-state={state}>
      <header className={styles.header}>
        {present.has('MUST_VISIT') ? <MustVisitBadge /> : null}
        <h3 className={styles.name}>{item.place.name}</h3>
        {badge ? <span className={styles.badge}>{badge}</span> : null}
        <span className={styles.time}>{item.startTime ?? word('timeUnset')}</span>
        <button
          type="button"
          className={styles.menu}
          onClick={onOpenMenu}
          aria-label={menuLabel}
        >
          ⋯
        </button>
      </header>

      {crowd !== undefined ? <CrowdLevel crowd={crowd} /> : null}
      {/* CMP-ATT-001: the place's credit, and the forecast's after it. */}
      <PlaceAttribution
        also={crowd ? [crowd.provenance] : undefined}
        compact
        place={item.place}
      />

      <div className={styles.locks}>
        {(['DATE', 'TIME', 'RESERVATION'] as const).map((type) => {
          if (!present.has(type) && type === 'RESERVATION') return null;
          const locked = present.has(type);
          return (
            <LockControl
              key={type}
              kind={LOCK_KINDS[type]}
              label={word(type)}
              state={
                type === 'RESERVATION'
                  ? 'reservation-locked'
                  : locked
                    ? 'user-locked'
                    : 'unlocked'
              }
              disabledReason={
                type === 'RESERVATION' ? word('reservationNote') : undefined
              }
              onClick={() => onToggleConstraint?.(type)}
            />
          );
        })}
      </div>
    </article>
  );
}
