import { DataAttribution } from './DataAttribution.js';
import {
  sourceContext,
  unitCredits,
  type AttributionSource,
  type PlaceLike,
} from './credits.js';

// The credits of a place, for every unit that shows one (CMP-ATT-001).
//
// A screen used to draw `place.sourceAttribution` itself, which credits the
// place record and nothing else. A place's words can come from another
// dataset — its English name from KTO_ENG_SERVICE while the record is
// KorService2's — and the contract sends that credit per field
// (`textProvenance`). This component draws both, each through DataAttribution,
// so each stays the server's own line and no two providers become one credit.
//
// Which credits, in which order, and when one needs its source named beside it
// are decided in credits.ts. This file only draws the answer.

export interface PlaceAttributionProps {
  /**
   * The place the unit shows, or every place it shows at once (a map's markers,
   * a row of chips). A missing place draws nothing, as a null credit does.
   */
  place: PlaceLike | readonly PlaceLike[] | null | undefined;
  /**
   * Other credits drawn in the same unit, after the place's — a crowd
   * forecast's, a relation's. Passed here rather than drawn beside so that one
   * reading the same words as a place credit is told apart from it.
   */
  also?: readonly AttributionSource[];
  compact?: boolean;
  showLicense?: boolean;
  termsLabel?: string;
}

function asList(place: PlaceAttributionProps['place']): readonly PlaceLike[] {
  if (place == null) return [];
  return isList(place) ? place : [place];
}

function isList(place: PlaceLike | readonly PlaceLike[]): place is readonly PlaceLike[] {
  return Array.isArray(place);
}

export function PlaceAttribution({
  place,
  also,
  compact = false,
  showLicense = false,
  termsLabel,
}: PlaceAttributionProps) {
  const credits = unitCredits(asList(place), also);
  return (
    <>
      {credits.map((credit, index) => (
        <DataAttribution
          compact={compact}
          context={sourceContext(credit, credits.slice(0, index), compact)}
          key={`${credit.source ?? ''}:${credit.officialUrl ?? ''}:${String(index)}`}
          provenance={credit}
          showLicense={showLicense}
          termsLabel={termsLabel}
        />
      ))}
    </>
  );
}
