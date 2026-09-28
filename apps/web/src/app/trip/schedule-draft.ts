import type { components } from '@nullnull/api-client';

type TripDay = components['schemas']['TripDetail']['days'][number];
type ReorderEntry = components['schemas']['ReorderTripItemsRequest']['items'][number];
type ReleasedLock = components['schemas']['ReleasedTemporalLocks'][number];

/** Apply an edit to the local itinerary. No trip mutation happens here. */
export function stageSchedule(
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
        return change
          ? {
              ...item,
              date: change.date,
              position: change.position,
              constraints: item.constraints.filter(
                (constraint) =>
                  !change.releaseConstraints?.includes(constraint.type as ReleasedLock),
              ),
            }
          : item;
      })
      .filter((item) => item.date === day.date)
      .sort((left, right) => left.position - right.position),
  }));
}

/** A full, atomic ordering against the ETag captured when editing began. */
export function schedulePatch(
  initial: readonly TripDay[],
  draft: readonly TripDay[],
): ReorderEntry[] | null {
  const original = new Map(
    initial.flatMap((day) => day.items).map((item) => [item.id, item]),
  );
  let changed = false;
  const entries: ReorderEntry[] = draft.flatMap((day) =>
    day.items.map((item) => {
      const before = original.get(item.id);
      if (!before) throw new Error('A schedule draft contains an unknown item');
      const releaseConstraints = before.constraints
        .filter(
          (constraint) => !item.constraints.some((kept) => kept.type === constraint.type),
        )
        .map((constraint) => constraint.type)
        .filter((type): type is ReleasedLock => type !== 'MUST_VISIT');
      if (
        item.date !== before.date ||
        item.position !== before.position ||
        releaseConstraints.length > 0
      ) {
        changed = true;
      }
      return {
        itemId: item.id,
        date: item.date,
        position: item.position,
        ...(releaseConstraints.length > 0 ? { releaseConstraints } : {}),
      };
    }),
  );
  return changed ? entries : null;
}
