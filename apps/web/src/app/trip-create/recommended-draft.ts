import type { components } from '@nullnull/api-client';

type TripDraftPreview = components['schemas']['TripDraftPreview'];
type TripDraftStop = components['schemas']['TripDraftStop'];
type SeedTripItem = components['schemas']['SeedTripItem'];

/** Stable identity for one proposed stop across Pick toggles and re-renders. */
export function recommendedStopKey(stop: TripDraftStop): string {
  return `${stop.date}:${String(stop.position)}:${stop.place.id}`;
}

/**
 * Maps the unsaved preview into the atomic createTrip seed payload.
 *
 * The preview never proposes a time, so `startTime` is explicitly null. A Pick
 * becomes the one supported place lock; an unpicked stop omits constraints
 * rather than sending `locked: false`, because lock types are independent.
 */
export function recommendedSeedItems(
  preview: TripDraftPreview,
  picked: ReadonlySet<string>,
): SeedTripItem[] {
  return preview.days.flatMap((day) =>
    day.stops.map((stop) => ({
      placeId: stop.place.id,
      date: stop.date,
      position: stop.position,
      startTime: null,
      ...(picked.has(recommendedStopKey(stop))
        ? { constraints: [{ type: 'MUST_VISIT' as const, locked: true as const }] }
        : {}),
    })),
  );
}
