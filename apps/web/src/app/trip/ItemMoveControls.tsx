import { useRef, useState } from 'react';
import type { components } from '@nullnull/api-client';
import { useI18n } from '../../i18n/I18nProvider.js';
import {
  isProblem,
  useRelatedPlaces,
  useReorderTripItems,
  useReplaceTripItem,
  useTrip,
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

/**
 * A lock the user agreed to release as part of this edit.
 *
 * Derived from the contract rather than written out: ReleasedTemporalLocks
 * lists the temporal locks only (MUST_VISIT is excluded there because a
 * temporal edit keeps the place), so naming a lock this union does not hold
 * fails to compile instead of failing at the server.
 */
type ReleasedLock = components['schemas']['ReleasedTemporalLocks'][number];
// The replace counterpart is ReleasedPlaceLocks, a separate list because the
// two edits are refused by different locks: a temporal edit keeps the place, a
// replacement keeps the schedule, so only that one can name MUST_VISIT. It is
// not aliased here — ReplaceSheet's onConfirm is typed to it directly, so the
// value arrives already bound to the request schema.

export interface ItemMoveControlsProps {
  item: TripItem;
  days: readonly TripDetail['days'][number][];
  tripId: string | null;
  etag: string | null;
}

export function ItemMoveControls({ item, days, tripId, etag }: ItemMoveControlsProps) {
  // Same query key as the screen's, so this is the one cached trip rather than
  // a second copy. Used to refetch after a conflict: the cached ETag is stale
  // the moment the server says TRIP_CHANGED, so without this every later press
  // sends the same If-Match and fails identically — LockRow documents the same
  // defect it already fixed.
  const trip = useTrip(tripId);
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
  // The key for the reorder in flight, and for retries of that same press.
  //
  // Minted inside the mutate() call it used to be regenerated on every press,
  // so pressing ↓ again after a failure was a SECOND move to the server, not a
  // retry of the first — which is the duplicate command invariant 6 exists to
  // prevent. Cleared on success and whenever the user aims at a different move,
  // so a genuinely new command still gets a genuinely new key.
  const moveKey = useRef<string | null>(null);
  const moveIntent = useRef<string | null>(null);
  const replaceKey = useRef<string | null>(null);
  const replaceIntent = useRef<string | null>(null);

  const block = moveBlock(item);
  const first = isFirstInDay(days, item.id);
  const last = isLastInDay(days, item.id);
  const busy = reorder.isPending || etag === null;

  /**
   * Sends one reorder.
   *
   * The key is held in a ref rather than minted here, so a retry of this press
   * replays the same command instead of counting as a second move. It said that
   * before and did the opposite — `crypto.randomUUID()` sat inside the mutate
   * call, giving every press a fresh key.
   */
  function send(
    order: ReturnType<typeof reorderWithinDay>,
    announce: string,
    released: ReleasedLock[] = [],
  ) {
    if (!order) return;
    setStatus(null);
    // The consent the user just gave, carried to the server on the entry it
    // applies to. The contract is explicit that naming a lock here is the ONLY
    // way it is released and that an unnamed lock still refuses the edit, so
    // the dialog's answer has to travel with the request — until now it did
    // not, and the move arrived looking like nobody had been asked.
    const items =
      released.length === 0
        ? order
        : order.map((entry) =>
            entry.itemId === item.id ? { ...entry, releaseConstraints: released } : entry,
          );
    reorder.mutate(
      { order: items, etag, idempotencyKey: (moveKey.current ??= crypto.randomUUID()) },
      {
        onSuccess: () => {
          moveKey.current = null;
          setStatus(announce);
        },
        onError: (error) => {
          const conflict = isProblem(error) && error.code === 'TRIP_CHANGED';
          setStatus(conflict ? t('trip.conflict') : t('trip.move.failed'));
          // Reload so the next press carries a current ETag. Without it every
          // move control on every item stays dead until the page is reloaded.
          if (conflict) void trip.refetch();
        },
      },
    );
  }

  /**
   * Starts a new command, so the next send mints a fresh key.
   *
   * "Same command" is the destination the user aimed at: pressing ↓ twice is two
   * different moves and must not replay the first, while pressing ↓ again after
   * a failure is the same move retried. Keyed by intent rather than by press.
   */
  function beginMove(intent: string) {
    if (moveIntent.current !== intent) {
      moveIntent.current = intent;
      moveKey.current = null;
    }
  }

  function step(direction: -1 | 1) {
    const order = reorderWithinDay(days, item.id, direction);
    if (!order) return;
    const position = order.findIndex((entry) => entry.itemId === item.id) + 1;
    beginMove(`step:${String(position)}`);
    send(order, t('trip.reorder.moved', { name: item.place.name, position }));
  }

  function commitMove(date: string, released: ReleasedLock[] = []) {
    const order = moveToDay(days, item.id, date);
    if (!order) return;
    const index = days.findIndex((day) => day.date === date);
    beginMove(`day:${date}`);
    send(
      order,
      t('trip.move.moved', {
        name: item.place.name,
        day: t('trip.day', { n: index + 1 }),
      }),
      released,
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
        placeId={item.place.id}
      />

      <ReplaceSheet
        busy={replace.isPending}
        failed={related.isError}
        item={item}
        loading={related.isPending && replaceOpen}
        onCancel={() => {
          setReplaceOpen(false);
        }}
        onConfirm={(choice, released) => {
          setStatus(null);
          // A different replacement is a different command; the same one
          // confirmed again after a failure is a retry of it.
          if (replaceIntent.current !== choice.place.id) {
            replaceIntent.current = choice.place.id;
            replaceKey.current = null;
          }
          replace.mutate(
            {
              itemId: item.id,
              replacement: {
                replacementPlaceId: choice.place.id,
                // The consequence the sheet just showed, carried to the server
                // as consent. The sheet has always named the locks this swap
                // releases ("고정된 장소가 해제될 수 있어요"), but the request
                // carried nothing that could cause it — so the edit arrived
                // looking like nobody had been asked, and now that
                // replaceTripItem is implemented it comes back LOCK_CONFLICT.
                // Naming a lock is the only way it is released, and an unnamed
                // one still refuses the edit (#166, #199).
                ...(released.length > 0 ? { releaseConstraints: released } : {}),
                // preserveDateTime is omitted, and stays omitted until #203
                // settles what it means. The contract gives it `default: true`
                // and no description, and replaceTripItem's own description
                // says the item keeps its schedule unconditionally — so what
                // `false` would do is undefined, not merely unused. Omitting
                // is the only value here that asks for nothing: sending the
                // default explicitly would still be sending a field whose
                // meaning nobody has fixed. BE rejects `false` with 422 in the
                // meantime (#203).
              },
              etag,
              idempotencyKey: (replaceKey.current ??= crypto.randomUUID()),
            },
            {
              onSuccess: () => {
                replaceKey.current = null;
                setReplaceOpen(false);
                setStatus(
                  t('replace.replaced', {
                    from: item.place.name,
                    to: choice.place.name,
                  }),
                );
              },
              onError: (error) => {
                const conflict = isProblem(error) && error.code === 'TRIP_CHANGED';
                setStatus(conflict ? t('trip.conflict') : t('replace.failed'));
                if (conflict) void trip.refetch();
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
          // The dialog asked to release the DATE lock; this is where that
          // answer becomes part of the request.
          if (date !== null) commitMove(date, ['DATE']);
        }}
        open={pendingDate !== null}
        title={t('trip.move.dateLock.title')}
      />
    </>
  );
}
