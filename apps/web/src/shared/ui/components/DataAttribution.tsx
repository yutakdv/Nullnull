import { useId } from 'react';
import type { components } from '@nullnull/api-client';
import { shownText, type AttributionSource } from './credits.js';
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

export interface DataAttributionProps {
  provenance: AttributionSource | Provenance | SourceAttribution;
  /** Narrow cards prefer the server's short credit. */
  compact?: boolean;
  /** Renders the licence link. Detail views and the data guide use it. */
  showLicense?: boolean;
  /**
   * Localized text for the licence link, from the caller.
   *
   * The credit itself is never localized — it is the server's approved
   * wording, shown verbatim (CMP-ATT-003). Only this link is our own label.
   */
  termsLabel?: string;
  /**
   * The server's name for this credit's source, shown beside it when another
   * credit in the same unit reads the same (`sourceContext` in credits.ts).
   * Never part of the link text, which stays the server's words.
   */
  context?: string | null;
}

export function DataAttribution({
  provenance,
  termsLabel = '이용조건',
  compact = false,
  showLicense = false,
  context = null,
}: DataAttributionProps) {
  const contextId = useId();
  const { officialUrl, licenseUrl } = provenance;
  const text = shownText(provenance, compact);
  if (!text) return null;

  return (
    <span className={styles.attribution}>
      {officialUrl ? (
        <a
          aria-describedby={context ? contextId : undefined}
          href={officialUrl}
          target="_blank"
          rel="noreferrer noopener"
        >
          {text}
        </a>
      ) : (
        text
      )}
      {context ? (
        <>
          <span aria-hidden="true">{' · '}</span>
          <span id={contextId}>{context}</span>
        </>
      ) : null}
      {showLicense && licenseUrl ? (
        <>
          {' · '}
          <a href={licenseUrl} target="_blank" rel="noreferrer noopener">
            {termsLabel}
          </a>
        </>
      ) : null}
    </span>
  );
}
