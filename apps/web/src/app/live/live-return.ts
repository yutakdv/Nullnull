/**
 * Where a Live place detail was opened from, carried in router state so the
 * detail's Back returns the traveller to the same area, the same search words
 * and focus on the link they followed (FE-401-T3 "focus 복귀"). Without it the
 * list remounted collapsed and focus fell to the top of the page.
 */
export interface LiveReturn {
  areaId: string | null;
  query: string;
  placeId: string;
}

export function readLiveReturn(state: unknown): LiveReturn | null {
  if (typeof state !== 'object' || state === null || !('liveReturn' in state))
    return null;
  const value = (state as { liveReturn: unknown }).liveReturn;
  if (typeof value !== 'object' || value === null) return null;
  const { areaId, query, placeId } = value as Record<string, unknown>;
  if (typeof placeId !== 'string' || typeof query !== 'string') return null;
  if (areaId !== null && typeof areaId !== 'string') return null;
  return { areaId, query, placeId };
}
