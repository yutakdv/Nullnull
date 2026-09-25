import type { components } from '@nullnull/api-client';

// The rules for WHICH credits a unit draws, kept apart from how one is drawn
// (DataAttribution) so each can be tested without a render.
//
// CMP-ATT-001 asks every unit that shows a place to credit it. A place carries
// two kinds of credit: the record's (`sourceAttribution`) and, per text field,
// the dataset that text came from (`textProvenance`). The English name of a
// place comes from KTO_ENG_SERVICE while the record is KorService2's, so
// crediting the record alone links the wrong dataset for the words on screen.

type SourceAttribution = components['schemas']['SourceAttribution'];
type PlaceTextProvenance = components['schemas']['PlaceTextProvenance'];

/**
 * What drawing a credit needs, which is less than either contract type supplies.
 *
 * Two shapes carry a credit: DataProvenance (crowd metrics, 29 fields) and
 * SourceAttribution (place records, 7). Only the attribution text and the two
 * links are common to both, and `attributionShort` exists on the first alone —
 * hence optional here rather than a Pick that one of them cannot satisfy.
 * `source` and `sourceDisplayName` are on both; they are optional only so a
 * caller holding neither can still draw a plain credit.
 */
export interface AttributionSource {
  /** Nullable on DataProvenance; a credit with no words draws nothing. */
  attribution: string | null;
  attributionShort?: string | null;
  officialUrl?: string | null;
  licenseUrl?: string | null;
  source?: string;
  sourceDisplayName?: string;
}

/** The two credit fields of PlaceSummary and PlaceDetail, both optional in the contract. */
export interface PlaceLike {
  sourceAttribution?: SourceAttribution | null;
  textProvenance?: PlaceTextProvenance | null;
}

/**
 * The words a credit shows. `compact` prefers the server's short form and falls
 * back to the full string — never a client-side truncation.
 */
export function shownText(credit: AttributionSource, compact: boolean): string | null {
  return compact ? (credit.attributionShort ?? credit.attribution) : credit.attribution;
}

/** How a unit draws its credits, which decides what a reader can tell apart. */
export interface CreditDisplay {
  compact?: boolean;
  showLicense?: boolean;
}

/**
 * Two credits of one source are the same credit when everything a reader sees
 * or follows is: the words shown, the page linked, and the licence link when
 * it is shown. A hidden licence difference is not something a reader can see.
 * The source stays in the key, so two providers are never folded into one.
 */
function creditKey(credit: AttributionSource, display: CreditDisplay): string {
  return [
    credit.source ?? '',
    shownText(credit, display.compact ?? false) ?? '',
    credit.officialUrl ?? '',
    display.showLicense ? (credit.licenseUrl ?? '') : '',
  ].join('\u0000');
}

/**
 * Every credit one unit draws, in order, each once.
 *
 * Per place: the record's credit, then the credit of each text field. A text
 * field with a null credit adds nothing — the contract says an older row has no
 * text provenance and forbids copying the record's credit into it. `also` is
 * any other credit the same unit draws (a crowd forecast's), after the places'.
 *
 * Once, because a place whose name and address come from the dataset that
 * credits the record would otherwise draw three identical links.
 */
export function unitCredits(
  places: readonly PlaceLike[],
  also: readonly AttributionSource[] = [],
  display: CreditDisplay = {},
): AttributionSource[] {
  const found: AttributionSource[] = [];
  for (const place of places) {
    const text = place.textProvenance;
    found.push(
      ...[
        place.sourceAttribution,
        text?.name?.sourceAttribution,
        text?.address?.sourceAttribution,
        text?.description?.sourceAttribution,
      ].filter((credit): credit is SourceAttribution => credit != null),
    );
  }
  found.push(...also);

  const seen = new Set<string>();
  return found.filter((credit) => {
    const key = creditKey(credit, display);
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

/**
 * The visible context a credit needs, or null.
 *
 * KTO's datasets all carry the same approved words (`출처: ⓒ한국관광공사`) and
 * differ only in the page they link. Two of them in one unit read as the same
 * link twice, pointing at two places — to a sighted reader and to a screen
 * reader's link list alike. The later one of such a pair gets the server's own
 * `sourceDisplayName` beside it. The credit text is untouched (CMP-ATT-003):
 * nothing is composed, the name is the server's, and the first of the pair keeps
 * the plain credit every other unit draws.
 *
 * `earlier` is what the unit has already drawn. `compact` matters because the
 * comparison is on the words shown, and a short form can make two credits
 * differ that would otherwise read the same.
 */
export function sourceContext(
  credit: AttributionSource,
  earlier: readonly AttributionSource[],
  compact: boolean,
): string | null {
  const words = shownText(credit, compact);
  if (!words) return null;
  const clash = earlier.some(
    (other) => shownText(other, compact) === words && other.source !== credit.source,
  );
  return clash ? (credit.sourceDisplayName ?? null) : null;
}
