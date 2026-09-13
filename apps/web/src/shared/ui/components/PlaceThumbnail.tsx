import type { components } from '@nullnull/api-client';
import styles from './PlaceThumbnail.module.css';

// A place's thumbnail, shown only when its source can be named (CMP-ATT-001).
//
// The contract states the rule on the field itself: `thumbnailAttribution` is
// "ready-to-render credit for thumbnailUrl, or null when the reviewed licence
// requires none", and "a card that cannot name the image's source must not
// show the image". Redistributable is not the same as creditless.
//
// Why this is a component rather than a line in each screen: four screens
// render a place thumbnail (must-visit search results, must-visit picks,
// candidates, add-place results) and all four rendered the image with no
// credit and no check. A rule copied four times is a rule that will be copied
// a fifth time without its guard, so the decision lives here and the screens
// ask for a thumbnail instead of building one.
//
// The credit is the server's approved wording, shown verbatim (CMP-ATT-003).
// This component never composes one and never falls back to a provider name of
// its own — with no credit there is no image.

type PlaceSummary = components['schemas']['PlaceSummary'];

export interface PlaceThumbnailProps {
  place: Pick<PlaceSummary, 'thumbnailUrl' | 'thumbnailAttribution'>;
  /** Square edge in CSS pixels; the frames use 44, 56 and 66. */
  size: number;
}

/**
 * Renders nothing when there is no image, and nothing when there is an image
 * we may not credit.
 *
 * Returning null in the second case is the point: the alternative — showing
 * the picture and omitting the line — is the compliance failure, and showing a
 * placeholder that implies a source we were not granted is worse.
 */
export function PlaceThumbnail({ place, size }: PlaceThumbnailProps) {
  if (!place.thumbnailUrl) return null;
  if (!place.thumbnailAttribution) return null;
  return (
    <span className={styles.wrap} style={{ width: size }}>
      <img
        alt=""
        className={styles.image}
        height={size}
        loading="lazy"
        src={place.thumbnailUrl}
        width={size}
      />
      {/* Beside the image it credits, not in a footer: the licence is for this
          picture, and a credit further away reads as covering the whole card. */}
      <span className={styles.credit}>{place.thumbnailAttribution}</span>
    </span>
  );
}
