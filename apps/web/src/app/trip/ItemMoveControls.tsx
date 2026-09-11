import { useState } from 'react';
import type { components } from '@nullnull/api-client';
import { useI18n } from '../../i18n/I18nProvider.js';
import {
  isProblem,
  useRelatedPlaces,
  useReorderTripItems,
  useReplaceTripItem,
} from '../../shared/api/index.js';
import { ConfirmDialog } from '../../shared/ui/components/index.js';
import styles from './ItemMoveControls.module.css';
import { MoveDaySheet } from './MoveDaySheet.js';
import { ReplaceSheet } from './ReplaceSheet.js';
import { isReplaceBlocked } from './replace.js';
import {
  isFirstInDay,
  isLastInDay,
  moveBlock,
  moveToDay,
  reorderWithinDay,
} from './reorder.js';

// Move and reorder controls on one itinerary item
// (FR-ITM-03, FR-ITM-05, S07-10 `521:3976`, S07-2 `527:4085`).
//
// Buttons rather than drag. COMPONENT_CATALOG's own Card/TripItem entry says
// "editing은 드래그 정렬 + 위/아래 이동 키보드 대안을 전제", so the keyboard path
// is specified, not a fallback — and it is the one that can actually be tested,
// since happy-dom has no drag. Drag can be layered on later over the same
// reorder call.
//
// Every action sends ONE request carrying the complete ordering for every day
// it touches (see reorder.ts). Moving an item as a sequence of single edits
// would collide with the (trip, date, position) uniqueness partway through.
//
// A DATE lock pins the item to its day, so a move asks first and says what the
// move releases — invariant 7 again: no lock comes off on its own. A
// RESERVATION pins it from outside this screen, so the control refuses rather
// than offering a confirm it cannot honour.

type TripDetail = components['schemas']['TripDetail'];
type TripItem = TripDetail['days'][number]['items'][number];

export interface ItemMoveControlsProps {
  item: TripItem;
  days: readonly TripDetail['days'][number][];
  tripId: string | null;
  etag: string | null;
}

export function ItemMoveControls({ item, days, tripId, etag }: ItemMoveControlsProps) {
  const { locale, t } = useI18n();
  const reorder = useReorderTripItems(tripId);
  const replace = useReplaceTripItem(tripId);
  const [sheetOpen, setSheetOpen] = useState(false);
  const [replaceOpen, setReplaceOpen] = useState(false);
  // Only fetched once the sheet opens: asking for alternatives to every stop
  // up front is a burst of requests for answers nobody has looked at.
  const related = useRelatedPlaces(replaceOpen ? item.place.id : null);
  const [pendingDate, setPendingDate] = useState<string | null>(null);
  const [status, setStatus] = useState<string | null>(null);

  const block = moveBlock(item);
  const first = isFirstInDay(days, item.id);
  const last = isLastInDay(days, item.id);
  const busy = reorder.isPending || etag === null;

  /**
   * Sends one reorder.
   *
   * The key is minted per user action rather than inside the mutation, so a
   * retry of this press reuses it instead of counting as a second move.
   */
  function send(order: ReturnType<typeof reorderWithinDay>, announce: string) {
    if (!order) return;
    setStatus(null);
    reorder.mutate(
      { order, etag, idempotencyKey: crypto.randomUUID() },
      {
        onSuccess: () => {
          setStatus(announce);
        },
        onError: (error) => {
          setStatus(
            isProblem(error) && error.code === 'TRIP_CHANGED'
              ? t('trip.conflict')
              : t('trip.move.failed'),
          );
        },
      },
    );
  }

  function step(direction: -1 | 1) {
    const order = reorderWithinDay(days, item.id, direction);
    if (!order) return;
    const position = order.findIndex((entry) => entry.itemId === item.id) + 1;
    send(order, t('trip.reorder.moved', { name: item.place.name, position }));
  }

  function commitMove(date: string) {
    const order = moveToDay(days, item.id, date);
    if (!order) return;
    const index = days.findIndex((day) => day.date === date);
    send(
      order,
      t('trip.move.moved', {
        name: item.place.name,
        day: t('trip.day', { n: index + 1 }),
      }),
    );
  }

  return (
    <>
      <div className={styles.controls}>
        <button
          aria-label={t('trip.reorder.up', { name: item.place.name })}
          className={styles.step}
          disabled={first || busy}
          onClick={() => {
            step(-1);
          }}
          type="button"
        >
          ↑
        </button>
        <button
          aria-label={t('trip.reorder.down', { name: item.place.name })}
          className={styles.step}
          disabled={last || busy}
          onClick={() => {
            step(1);
          }}
          type="button"
        >
          ↓
        </button>
        <button
          className={styles.move}
          disabled={busy || block === 'reservation'}
          onClick={() => {
            setSheetOpen(true);
          }}
          title={block === 'reservation' ? t('trip.move.reservation') : undefined}
          type="button"
        >
          {t('trip.move.open', { name: item.place.name })}
        </button>
        <button
          className={styles.move}
          disabled={busy || replace.isPending || isReplaceBlocked(item)}
          onClick={() => {
            setReplaceOpen(true);
          }}
          title={isReplaceBlocked(item) ? t('replace.blocked') : undefined}
          type="button"
        >
          {t('replace.open', { name: item.place.name })}
        </button>
      </div>

      {/* One live region per item: the result of a keyboard move has to be
          announced, since the visual change is the only other signal. */}
      <p aria-live="polite" className={styles.status} role="status">
        {reorder.isPending ? t('trip.move.moving') : (status ?? '')}
      </p>

      <MoveDaySheet
        days={days}
        itemId={item.id}
        itemName={item.place.name}
        locale={locale}
        onCancel={() => {
          setSheetOpen(false);
        }}
        onPick={(date) => {
          setSheetOpen(false);
          // A DATE lock pins this item to its day, so the move releases it —
          // and that never happens without the user saying so.
          if (block === 'date-lock') {
            setPendingDate(date);
            return;
          }
          commitMove(date);
        }}
        open={sheetOpen}
      />

      <ReplaceSheet
        busy={replace.isPending}
        failed={related.isError}
        item={item}
        loading={related.isPending && replaceOpen}
        onCancel={() => {
          setReplaceOpen(false);
        }}
        onConfirm={(choice) => {
          setStatus(null);
          replace.mutate(
            {
              itemId: item.id,
              replacement: {
                replacementPlaceId: choice.place.id,
                // preserveDateTime is omitted: the contract defaults it true,
                // and false would move the schedule without being asked.
              },
              etag,
              idempotencyKey: crypto.randomUUID(),
            },
            {
              onSuccess: () => {
                setReplaceOpen(false);
                setStatus(
                  t('replace.replaced', {
                    from: item.place.name,
                    to: choice.place.name,
                  }),
                );
              },
              onError: (error) => {
                setStatus(
                  isProblem(error) && error.code === 'TRIP_CHANGED'
                    ? t('trip.conflict')
                    : t('replace.failed'),
                );
              },
            },
          );
        }}
        open={replaceOpen}
        result={related.data}
      />

      <ConfirmDialog
        body={t('trip.move.dateLock.body')}
        cancelLabel={t('trip.lock.cancel')}
        confirmLabel={t('trip.move.dateLock.confirm')}
        destructive
        onCancel={() => {
          setPendingDate(null);
        }}
        onConfirm={() => {
          const date = pendingDate;
          setPendingDate(null);
          if (date !== null) commitMove(date);
        }}
        open={pendingDate !== null}
        title={t('trip.move.dateLock.title')}
      />
    </>
  );
}
