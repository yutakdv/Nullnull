import type { components } from '@nullnull/api-client';

// Replace rules for S07-11 `479:3497` and the compare sheet `414:2347`
// (FR-ITM-07, FR-ITM-08, FE-305).
//
// Invariant 8 governs this screen more than any other: it puts the current
// place and an alternative side by side, which is exactly the comparison the
// invariant restricts. Two things follow, and both are enforced here rather
// than left to the component:
//
//   - A number is only comparable when the server says the pair is comparable.
//     `provenance.comparisonEligible` is computed per request (a provider
//     incident can flip it between two reads of the same row), so it is read
//     fresh and never cached or derived.
//   - Ordering alternatives by crowd is itself a numeric comparison. If the
//     pair is not eligible, the list keeps the server's order rather than
//     ranking by a value that must not be ranked.

type TripDetail = components['schemas']['TripDetail'];
type TripItem = TripDetail['days'][number]['items'][number];
type RelatedPlace = components['schemas']['RelatedPlace'];
type RelatedPlaceResult = components['schemas']['RelatedPlaceResult'];
type ConstraintType = components['schemas']['ConstraintType'];

/** States in which the server has actually decided there are alternatives. */
const DECIDED: RelatedPlaceResult['state'][] = ['EXACT', 'SIMILAR'];

/**
 * True when the result is a decision rather than the absence of one.
 *
 * NONE, CHECKING and UNKNOWN all arrive with an empty `items` array, so
 * branching on `items.length` would tell the user "no alternatives" while the
 * server was still looking or had no basis to answer.
 */
export function hasAlternatives(result: RelatedPlaceResult): boolean {
  return DECIDED.includes(result.state) && result.items.length > 0;
}

/**
 * The alternatives to offer, in the server's own order.
 *
 * Deliberately not sorted. Ranking by crowd would be a numeric comparison
 * across places whose provenance may not permit one, which is the same rule
 * that stops the card printing a delta.
 */
export function alternatives(result: RelatedPlaceResult): RelatedPlace[] {
  return hasAlternatives(result) ? result.items : [];
}

/**
 * Whether this pair may be compared numerically.
 *
 * Both sides must carry provenance that says so. Missing crowd on either side
 * is not "equal" or "unknown-but-probably-fine" — it is simply not comparable.
 */
export function comparable(current: TripItem, candidate: RelatedPlace): boolean {
  const here = current.crowd;
  const there = candidate.crowd;
  if (!here || !there) return false;
  return (
    here.provenance.comparisonEligible === true &&
    there.provenance.comparisonEligible === true &&
    // Two numbers from different axes are not a comparison.
    here.provenance.comparisonAxis === there.provenance.comparisonAxis
  );
}

/**
 * Why a comparison is unavailable, for the screen to state plainly.
 *
 * The server supplies a reason code when it has one. An absent code still
 * yields a reason ('no-data'), because silence would leave the user to assume
 * the two are equivalent.
 */
export function comparisonBlock(
  current: TripItem,
  candidate: RelatedPlace,
): string | null {
  if (comparable(current, candidate)) return null;
  if (!current.crowd || !candidate.crowd) return 'no-data';
  return (
    current.crowd.provenance.comparisonReasonCode ??
    candidate.crowd.provenance.comparisonReasonCode ??
    'not-eligible'
  );
}

/**
 * The locks a replace would release, and the ones it keeps.
 *
 * MUST_VISIT pins the PLACE, so swapping the place releases it — which is what
 * the frame warns about ("고정된 장소가 해제될 수 있어요"). DATE, TIME and
 * RESERVATION pin the schedule, and `preserveDateTime` defaults to true, so
 * they survive.
 *
 * Computed from the item rather than written into copy: an "everything else
 * stays" sentence goes stale the moment a lock is added.
 */
export function replaceLockEffect(item: TripItem): {
  released: ConstraintType[];
  kept: ConstraintType[];
} {
  const present = item.constraints.map((c) => c.type);
  return {
    released: present.filter((type) => type === 'MUST_VISIT'),
    kept: present.filter((type) => type !== 'MUST_VISIT'),
  };
}

/** True when a reservation pins this item, so it cannot be replaced here. */
export function isReplaceBlocked(item: TripItem): boolean {
  return item.constraints.some((c) => c.type === 'RESERVATION');
}
