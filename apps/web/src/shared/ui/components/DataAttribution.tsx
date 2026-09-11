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
type SourceAttribution = components['schemas']['SourceAttribution'];

/**
 * What this component needs, which is less than either contract type supplies.
 *
 * Two shapes carry a credit: DataProvenance (crowd metrics, 29 fields) and
 * SourceAttribution (place records, 7). Only the attribution text and the two
 * links are common to both, and `attributionShort` exists on the first alone —
 * hence optional here rather than a Pick that one of them cannot satisfy.
 */
export interface AttributionSource {
  attribution: string;
  attributionShort?: string | null;
  officialUrl?: string | null;
  licenseUrl?: string | null;
}

export interface DataAttributionProps {
  provenance: AttributionSource | Provenance | SourceAttribution;
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
  const { attribution, officialUrl, licenseUrl } = provenance;
  // Present on DataProvenance, absent on SourceAttribution.
  const attributionShort =
    'attributionShort' in provenance ? provenance.attributionShort : null;
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
