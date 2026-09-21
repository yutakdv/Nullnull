import { useEffect, useRef, useState } from 'react';
import type { PointerEvent as ReactPointerEvent } from 'react';
import type { components } from '@nullnull/api-client';
import { useI18n } from '../../i18n/I18nProvider.js';
import { isProblem, useReorderTripItems, useTrip } from '../../shared/api/index.js';
import { moveBlock, reorderAt } from './reorder.js';

type TripDetail = components['schemas']['TripDetail'];
type TripDay = TripDetail['days'][number];
type TripItem = TripDay['items'][number];
type ReorderEntry = components['schemas']['ReorderTripItemsRequest']['items'][number];
type DropTarget = { date: string; index: number };

type DragPointer = {
  pointerId: number;
  item: TripItem;
  originX: number;
  originY: number;
  x: number;
  y: number;
  cardLeft: number;
  cardWidth: number;
  cardHeight: number;
  grabOffsetY: number;
  active: boolean;
};

type DragPreview = {
  itemId: string;
  name: string;
  left: number;
  top: number;
  width: number;
  height: number;
};

function dropTargetAt(y: number, itemId: string): DropTarget | null {
  const sections = Array.from(
    document.querySelectorAll<HTMLElement>('section[data-drop-day]'),
  );
  let closest: HTMLElement | null = null;
  let distance = Number.POSITIVE_INFINITY;
  for (const section of sections) {
    const rect = section.getBoundingClientRect();
    const away = y < rect.top ? rect.top - y : y > rect.bottom ? y - rect.bottom : 0;
    if (away < distance) {
      distance = away;
      closest = section;
    }
  }
  if (!closest || distance > 24) return null;
  const date = closest.dataset.dropDay;
  if (!date) return null;
  const cards = Array.from(
    closest.querySelectorAll<HTMLElement>('li[data-trip-item]'),
  ).filter((card) => card.dataset.tripItem !== itemId);
  const index = cards.findIndex((card) => {
    const rect = card.getBoundingClientRect();
    return y < rect.top + rect.height / 2;
  });
  return { date, index: index < 0 ? cards.length : index };
}

function projectedDays(
  days: readonly TripDay[],
  order: readonly ReorderEntry[],
): TripDay[] {
  const changes = new Map(order.map((entry) => [entry.itemId, entry]));
  const items = days.flatMap((day) => day.items);
  return days.map((day) => ({
    ...day,
    items: items
      .map((item) => {
        const change = changes.get(item.id);
        return change ? { ...item, date: change.date, position: change.position } : item;
      })
      .filter((item) => item.date === day.date)
      .sort((left, right) => left.position - right.position),
  }));
}

export function useTripDragReorder({
  days,
  tripId,
  etag,
  selectedDay,
}: {
  days: readonly TripDay[];
  tripId: string | null;
  etag: string | null;
  selectedDay: string | null;
}) {
  const { t } = useI18n();
  const trip = useTrip(tripId);
  const reorder = useReorderTripItems(tripId);
  const pointer = useRef<DragPointer | null>(null);
  const intent = useRef<string | null>(null);
  const key = useRef<string | null>(null);
  const [preview, setPreview] = useState<DragPreview | null>(null);
  const [target, setTarget] = useState<DropTarget | null>(null);
  const [provisional, setProvisional] = useState<TripDay[] | null>(null);
  const [pendingDateLock, setPendingDateLock] = useState<{
    item: TripItem;
    target: DropTarget;
  } | null>(null);
  const [status, setStatus] = useState<string | null>(null);

  useEffect(() => {
    if (preview === null) return;
    let frame = 0;
    const scroll = () => {
      const current = pointer.current;
      if (!current?.active) return;
      const scroller = document.getElementById('main');
      if (!scroller) return;
      const bounds = scroller.getBoundingClientRect();
      const edge = 72;
      const speed =
        current.y < bounds.top + edge ? -12 : current.y > bounds.bottom - edge ? 12 : 0;
      if (speed !== 0) {
        scroller.scrollBy(0, speed);
        setTarget(dropTargetAt(current.y, current.item.id));
      }
      frame = window.requestAnimationFrame(scroll);
    };
    frame = window.requestAnimationFrame(scroll);
    return () => window.cancelAnimationFrame(frame);
  }, [preview?.itemId]);

  useEffect(() => {
    if (preview === null) return;
    function cancel(event: KeyboardEvent) {
      if (event.key !== 'Escape') return;
      pointer.current = null;
      setPreview(null);
      setTarget(null);
    }
    document.addEventListener('keydown', cancel);
    return () => document.removeEventListener('keydown', cancel);
  }, [preview?.itemId]);

  function send(item: TripItem, drop: DropTarget, releasedDate = false) {
    const order = reorderAt(days, item.id, drop.date, drop.index);
    if (!order) return;
    const request = releasedDate
      ? order.map((entry) =>
          entry.itemId === item.id
            ? { ...entry, releaseConstraints: ['DATE' as const] }
            : entry,
        )
      : order;
    const nextIntent = `${item.id}:${drop.date}:${String(drop.index)}`;
    if (intent.current !== nextIntent) {
      intent.current = nextIntent;
      key.current = null;
    }
    setStatus(null);
    setProvisional(projectedDays(days, order));
    reorder.mutate(
      {
        order: request,
        etag,
        idempotencyKey: (key.current ??= crypto.randomUUID()),
      },
      {
        onSuccess: () => {
          key.current = null;
          setProvisional(null);
          const dayIndex = days.findIndex((day) => day.date === drop.date);
          setStatus(
            item.date === drop.date
              ? t('trip.reorder.moved', {
                  name: item.place.name,
                  position: drop.index + 1,
                })
              : t('trip.move.moved', {
                  name: item.place.name,
                  day: t('trip.day', { n: dayIndex + 1 }),
                }),
          );
        },
        onError: (error) => {
          setProvisional(null);
          const conflict = isProblem(error) && error.code === 'TRIP_CHANGED';
          setStatus(conflict ? t('trip.conflict') : t('trip.move.failed'));
          if (conflict) void trip.refetch();
        },
      },
    );
  }

  function propose(item: TripItem, drop: DropTarget) {
    if (selectedDay !== null && drop.date !== item.date) return;
    if (!reorderAt(days, item.id, drop.date, drop.index)) return;
    if (drop.date !== item.date) {
      const block = moveBlock(item);
      if (block === 'reservation') {
        setStatus(t('trip.move.reservation'));
        return;
      }
      if (block === 'date-lock') {
        setPendingDateLock({ item, target: drop });
        return;
      }
    }
    send(item, drop);
  }

  function onPointerDown(event: ReactPointerEvent<HTMLButtonElement>, item: TripItem) {
    if (event.button !== 0 || etag === null || reorder.isPending) return;
    const card = event.currentTarget.closest('article');
    if (!card) return;
    const rect = card.getBoundingClientRect();
    event.currentTarget.setPointerCapture(event.pointerId);
    pointer.current = {
      pointerId: event.pointerId,
      item,
      originX: event.clientX,
      originY: event.clientY,
      x: event.clientX,
      y: event.clientY,
      cardLeft: rect.left,
      cardWidth: rect.width,
      cardHeight: rect.height,
      grabOffsetY: event.clientY - rect.top,
      active: false,
    };
  }

  function onPointerMove(event: ReactPointerEvent<HTMLButtonElement>) {
    const current = pointer.current;
    if (!current || current.pointerId !== event.pointerId) return;
    current.x = event.clientX;
    current.y = event.clientY;
    if (
      !current.active &&
      Math.hypot(current.x - current.originX, current.y - current.originY) < 8
    ) {
      return;
    }
    current.active = true;
    setPreview({
      itemId: current.item.id,
      name: current.item.place.name,
      left: current.cardLeft,
      top: current.y - current.grabOffsetY,
      width: current.cardWidth,
      height: current.cardHeight,
    });
    setTarget(dropTargetAt(current.y, current.item.id));
  }

  function finishPointer(event: ReactPointerEvent<HTMLButtonElement>, cancelled = false) {
    const current = pointer.current;
    if (!current || current.pointerId !== event.pointerId) return;
    const drop =
      !cancelled && current.active ? dropTargetAt(event.clientY, current.item.id) : null;
    pointer.current = null;
    setPreview(null);
    setTarget(null);
    if (drop) propose(current.item, drop);
  }

  return {
    days: provisional ?? days,
    draggingItemId: preview?.itemId ?? null,
    preview,
    target,
    status,
    busy: reorder.isPending,
    dateLockConfirmationOpen: pendingDateLock !== null,
    cancelDateLock: () => setPendingDateLock(null),
    confirmDateLock: () => {
      if (pendingDateLock) send(pendingDateLock.item, pendingDateLock.target, true);
      setPendingDateLock(null);
    },
    onPointerDown,
    onPointerMove,
    onPointerUp: (event: ReactPointerEvent<HTMLButtonElement>) => finishPointer(event),
    onPointerCancel: (event: ReactPointerEvent<HTMLButtonElement>) =>
      finishPointer(event, true),
  };
}
