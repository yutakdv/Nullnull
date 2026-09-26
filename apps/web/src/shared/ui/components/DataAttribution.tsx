import { useId } from 'react';
import type { components } from '@nullnull/api-client';
import { useOptionalI18n } from '../../../i18n/I18nProvider.js';
import { isSafeUrl } from '../../url/safe-url.js';
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
//   - Each becomes a link only when it is https (isSafeUrl, FE-603-T12). An
//     anchor is where a `javascript:` URL runs, and `rel` says nothing about
//     the scheme. A refused credit URL draws the words unlinked, exactly as a
//     null one does, because the credit itself is still owed (CMP-ATT-001).

/** Only for a render with no I18nProvider (a story); the app takes `license.terms`. */
const DEFAULT_TERMS_LABEL = '이용조건';

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
   * wording, shown verbatim (CMP-ATT-003). Only this link is our own label,
   * and an omitted one takes the locale's word (FE-001-T4).
   */
  termsLabel?: string;
  /**
   * The server's name for this credit's source, shown beside it when another
   * credit in the same unit reads the same (`sourceContext` in credits.ts).
   * Never part of the link text, which stays the server's words.
   */
  context?: string | null;
  /**
   * Puts `context` into both links' accessible names instead of describing the
   * credit link with it. For a list of credits that read alike (the data guide):
   * a screen reader's link list shows names only, so without it the list holds
   * several identical "출처: ⓒ한국관광공사" and "이용조건" links.
   */
  nameWithContext?: boolean;
}

export function DataAttribution({
  provenance,
  termsLabel: callerTermsLabel,
  compact = false,
  showLicense = false,
  context = null,
  nameWithContext = false,
}: DataAttributionProps) {
  const contextId = useId();
  const i18n = useOptionalI18n();
  const termsLabel = callerTermsLabel ?? i18n?.t('license.terms') ?? DEFAULT_TERMS_LABEL;
  const officialUrl =
    provenance.officialUrl && isSafeUrl(provenance.officialUrl)
      ? provenance.officialUrl
      : null;
  const licenseUrl =
    provenance.licenseUrl && isSafeUrl(provenance.licenseUrl)
      ? provenance.licenseUrl
      : null;
  const text = shownText(provenance, compact);
  if (!text) return null;

  return (
    <span className={styles.attribution}>
      {officialUrl ? (
        <a
          aria-describedby={context && !nameWithContext ? contextId : undefined}
          aria-label={context && nameWithContext ? `${text} · ${context}` : undefined}
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
          <span aria-hidden={nameWithContext || undefined} id={contextId}>
            {context}
          </span>
        </>
      ) : null}
      {showLicense && licenseUrl ? (
        <>
          {' · '}
          <a
            aria-label={
              context && nameWithContext ? `${termsLabel} · ${context}` : undefined
            }
            href={licenseUrl}
            target="_blank"
            rel="noreferrer noopener"
          >
            {termsLabel}
          </a>
        </>
      ) : null}
    </span>
  );
}
