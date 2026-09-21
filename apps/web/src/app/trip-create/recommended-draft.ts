import type { components } from '@nullnull/api-client';

type TripDraftPreview = components['schemas']['TripDraftPreview'];
type TripDraftStop = components['schemas']['TripDraftStop'];
type SeedTripItem = components['schemas']['SeedTripItem'];

export function recommendedStopKey(stop: TripDraftStop): string {
  return `${stop.date}:${String(stop.position)}:${stop.place.id}`;
}

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
      constraints: picked.has(recommendedStopKey(stop))
        ? [{ type: 'MUST_VISIT' as const, locked: true as const }]
        : [],
    })),
  );
}
