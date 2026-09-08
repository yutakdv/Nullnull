import type { components } from '@nullnull/api-client';
import styles from './DataAttribution.module.css';

// Shared provenance primitive named in COMPONENT_CATALOG §1. Not a Figma
// top-level node; it centralises the attribution wording so no screen
// hardcodes a provider name.
//
// Rules it enforces:
//   - The server's attribution string is shown as written. The client never
//     rebuilds it or merges two providers into one credit (SOURCE_CATALOG).
//   - `compact` uses attributionShort when the server supplies one and falls
//     back to the full string otherwise. It never truncates by itself.
//   - officialUrl and licenseUrl come from the response; the client keeps no
//     provider URL of its own.

type Provenance = components['schemas']['DataProvenance'];

export interface DataAttributionProps {
  provenance: Pick<
    Provenance,
    'attribution' | 'attributionShort' | 'officialUrl' | 'licenseUrl' | 'observedAt'
  >;
  /** Narrow cards prefer the server's short credit. */
  compact?: boolean;
  /** Renders the licence link. Detail views and the data guide use it. */
  showLicense?: boolean;
}

export function DataAttribution({
  provenance,
  compact = false,
  showLicense = false,
}: DataAttributionProps) {
  const { attribution, attributionShort, officialUrl, licenseUrl } = provenance;
  // Falling back to the full string is deliberate: an absent short form must
  // not become a client-side truncation.
  const text = compact ? (attributionShort ?? attribution) : attribution;
  if (!text) return null;

  return (
    <span className={styles.attribution}>
      {officialUrl ? (
        <a href={officialUrl} target="_blank" rel="noreferrer noopener">
          {text}
        </a>
      ) : (
        text
      )}
      {showLicense && licenseUrl ? (
        <>
          {' · '}
          <a href={licenseUrl} target="_blank" rel="noreferrer noopener">
            이용조건
          </a>
        </>
      ) : null}
    </span>
  );
}
