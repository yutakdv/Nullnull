import { useEffect, useRef, useState } from 'react';
import type { components } from '@nullnull/api-client';
import { useI18n } from '../../i18n/I18nProvider.js';
import { usePlaceCrowdForecasts, usePlaceSearch } from '../../shared/api/index.js';
import {
  BottomCta,
  MustVisitBadge,
  PlaceAttribution,
  PlaceThumbnail,
  SearchField,
  unitCredits,
} from '../../shared/ui/index.js';
import { restoreFocusTo } from '../../shared/ui/components/focus-restore.js';
import {
  CrowdForecastCardReading,
  CrowdForecastQueryState,
} from '../../shared/crowd/CrowdForecastReading.js';
import styles from './MustVisitScreen.module.css';

type PlaceSummary = components['schemas']['PlaceSummary'];

// Figma: S02-4B must-visit `438:3158`.
//
// FR-TRC-04/05/08: pick the places that must stay, then let the rest be filled.
// A MUST_VISIT pick is a constraint on the trip, never a scheduled item — it
// carries no date or time here (invariant 2).
//
// IN P0 SCOPE (owner, #180). An earlier version of this comment said the
// opposite and called reconnecting it P1 work; that was wrong and is corrected
// here rather than left to mislead the next reader.
//
// This is step 4 of the wizard, not a screen of its own. Step 3 asks how much
// the traveller has already planned (`438:3134`) and its three answers are the
// branch: NOTHING opens the deterministic recommendation preview,
// MUST_VISIT_ONLY comes here, and MOSTLY_PLANNED goes to the S02-4C
// input-method choice. The Figma copy carries that thread — step 3's second
// card reads "그 장소는 지키고 나머지를 채워드릴게요" and this screen opens
// with the same promise.
//
// It used to live at its own route, which nothing linked to, so it was
// reachable only by typing the URL and the picks it collected went nowhere.
// The route is gone: steps 1-3 are component state in TripWizardScreen, and a
// step that needs the same draft has to be held the same way. FIGMA_HANDOFF's
// screen table lists one path for trip creation (`/start`) and never gave this
// one a URL.
//
// The picks reach the server as CANDIDATES of the new trip. #180 was answered
// on 2026-09-13 with option B: the intention rides on the candidate
// (`AddCandidateRequest.mustVisit`). `CreateTripRequest` will never carry it —
// a dateless place cannot be a `seedItem` (`date` is required) and cannot hold
// a lock (`trip_constraints.trip_item_id` is NOT NULL), so it stays an
// intention until scheduling promotes it to a `MUST_VISIT` constraint.
//
// So 이대로 채우기 is `createTrip` followed by one `addTripCandidate` per pick
// (TripWizardScreen `savePicks`), and those N+1 requests are not one
// transaction (invariant 5). When the trip is created and only some picks land,
// this step names the ones that did not and offers 다시 시도 for exactly those
// — the same trip, the same keys — or 여행으로 가기 without them (#185). The
// frame for that state is not drawn yet (FCR-039).
//
// MOCK DATA: searchPlaces has no approved example, so the msw fixture behind it
// is a schema-valid guess (packages/contracts). The screen calls the real
// generated client, so BA-022 landing removes the fixture and handler only.
//
// The Figma card's crowd reading comes from queryPlaceCrowdForecasts, not from
// PlaceSummary. The ordered response is joined to search results by index and
// preserves the chosen point's own provenance; no ordinal is derived from the
// KTO relative index (FCR-029).

export interface MustVisitStepProps {
  /** The places chosen so far, held by the wizard so going back keeps them. */
  picked: PlaceSummary[];
  /** Add one. The wizard applies the cap and the duplicate rule (addMustVisit). */
  onAdd: (place: PlaceSummary) => void;
  onRemove: (placeId: string) => void;
  /** Create the trip with these picks. */
  onSubmit: () => void;
  /** Create the trip without any, which is a real answer rather than a cancel. */
  onSkip: () => void;
  /**
   * True while createTrip or the picks' candidate writes are in flight, so
   * neither exit fires twice.
   */
  isSubmitting: boolean;
  /**
   * Picks the created trip could not take (#185). Non-empty only once the trip
   * exists and some of its candidate writes failed; the step then offers to
   * retry exactly these or to open the trip without them, and nothing else.
   */
  unsaved: PlaceSummary[];
  /** Re-send the unsaved picks to the same trip. Never creates a trip. */
  onRetryUnsaved: () => void;
  /** Open the created trip without the unsaved picks. */
  onOpenTrip: () => void;
  startDate: string | null;
  endDate: string | null;
}

export function MustVisitStep({
  picked,
  onAdd,
  onRemove,
  onSubmit,
  onSkip,
  isSubmitting,
  unsaved,
  onRetryUnsaved,
  onOpenTrip,
  startDate,
  endDate,
}: MustVisitStepProps) {
  const { locale, t } = useI18n();
  const [query, setQuery] = useState('');
  const search = usePlaceSearch(query, locale);
  const searchResults = search.data?.items ?? [];
  const forecasts = usePlaceCrowdForecasts(
    searchResults.map((place) => place.id),
    startDate,
    endDate,
  );

  const pickedIds = new Set(picked.map((place) => place.id));
  // The list is what gets sent, so it stops changing once sending starts: a
  // place kept after the press would not be among the picks 다시 시도 sends,
  // and one removed would still be sent (#185).
  const locked = isSubmitting || unsaved.length > 0;
  // #185's partial failure, shown once a write attempt has finished with
  // places left over — and again after each retry that leaves some.
  const showUnsaved = unsaved.length > 0 && !isSubmitting;
  const unsavedRef = useRef<HTMLParagraphElement>(null);
  const retryRef = useRef<HTMLButtonElement>(null);
  // Brings the message into view and focus back to the CTA, which is 다시
  // 시도 now. The CTA was disabled while the writes ran, and Chromium drops
  // focus from a disabled control to <body> (focus-restore.ts records the same
  // measurement), so without this the next Tab starts from the top of the page
  // and the message can sit scrolled out of sight above a long result list.
  useEffect(() => {
    if (!showUnsaved) return;
    unsavedRef.current?.scrollIntoView({ block: 'nearest' });
    restoreFocusTo(retryRef.current);
  }, [showUnsaved]);

  function meta(place: PlaceSummary): string {
    // categoryName, not categoryCode. BA-022 made the distinction explicit in
    // the contract: the code is "opaque provider-derived … not display copy",
    // and a null name means "do not show a category", never "unknown". So a
    // missing name drops the segment rather than falling back to the code.
    return [place.categoryName, place.regionName, place.address]
      .filter((part): part is string => typeof part === 'string' && part.length > 0)
      .join(' · ');
  }

  // No <section>, no STEP line and no NavBar: TripWizardScreen renders the
  // shell for every step, and this one used to draw its own because it was a
  // route. Keeping both would put two STEP 4 labels on the page and nest a
  // second labelled region inside the wizard's.
  return (
    <div className={styles.screen}>
      <div className={styles.body}>
        <h1 className={styles.title} id="wizard-heading">
          {t('mustVisit.title1')}
          <br />
          {t('mustVisit.title2')}
        </h1>
        <p className={styles.lead}>
          {t('mustVisit.body1')}
          <br />
          {t('mustVisit.body2')}
        </p>

        <SearchField
          label={t('mustVisit.searchLabel')}
          placeholder={t('mustVisit.search')}
          value={query}
          onChange={(event) => {
            setQuery(event.target.value);
          }}
        />

        {query.trim() ? (
          <div>
            <p className={styles.sectionLabel} id="search-results">
              {t('mustVisit.results')}
            </p>
            {search.isPending ? (
              <p className={styles.state} role="status">
                {t('mustVisit.searching')}
              </p>
            ) : null}
            {search.isError ? (
              <p className={styles.state} role="alert">
                {t('mustVisit.searchError')}
              </p>
            ) : null}
            {search.isSuccess && search.data.items.length === 0 ? (
              <p className={styles.state}>{t('mustVisit.noResults')}</p>
            ) : null}
            {searchResults.length > 0 ? (
              <CrowdForecastQueryState
                failed={forecasts.isError}
                loading={forecasts.isFetching}
                series={undefined}
              />
            ) : null}
            {search.isSuccess && search.data.items.length > 0 ? (
              <ul className={styles.list} aria-labelledby="search-results">
                {search.data.items.map((place, index) => (
                  <li className={styles.card} key={place.id}>
                    {/* 438:3171: a 66px thumbnail. Decorative — the name beside
                      it is the accessible content. */}
                    {place.thumbnailUrl && place.thumbnailAttribution ? (
                      <PlaceThumbnail place={place} size={66} />
                    ) : (
                      <span aria-hidden="true" className={styles.thumb} />
                    )}
                    <span className={styles.cardText}>
                      <span className={styles.name}>{place.name}</span>
                      <span className={styles.meta}>{meta(place)}</span>
                      {/* FCR-031 / CMP-ATT-001: the credit the server approved for
                        this record, shown verbatim. Never composed here — the
                        contract says to display the string as given, and
                        CMP-ATT-003 forbids implying a source that was not
                        granted. Null only for places with no external source. */}
                      <PlaceAttribution compact place={place} />
                      <CrowdForecastCardReading
                        alongside={unitCredits([place])}
                        series={forecasts.data?.items[index]}
                      />
                    </span>
                    <button
                      type="button"
                      // Every result's button reads 담기, so a screen reader
                      // hears the same name down the whole list and cannot tell
                      // which place each one adds. The visible label stays short
                      // — the place is right beside it on screen — and only the
                      // accessible name carries it.
                      aria-label={t('mustVisit.addNamed', { place: place.name })}
                      className={styles.action}
                      disabled={pickedIds.has(place.id) || locked}
                      onClick={() => {
                        onAdd(place);
                      }}
                    >
                      {t('mustVisit.add')}
                    </button>
                  </li>
                ))}
              </ul>
            ) : null}
          </div>
        ) : null}

        <div>
          <div className={styles.sectionHead}>
            <span className={styles.sectionLabel} id="picked-places">
              {t('mustVisit.picked')}
            </span>
            {/* One interpolated message, not a number glued to a suffix: the
              noun goes before the count in English and after it in Korean, and
              the concatenated form left English with a bare digit. */}
            <span className={styles.count}>
              {t('mustVisit.pickedCount', { count: picked.length })}
            </span>
          </div>
          {picked.length === 0 ? (
            <p className={styles.state}>{t('mustVisit.pickedEmpty')}</p>
          ) : (
            <ul className={styles.list} aria-labelledby="picked-places">
              {picked.map((place) => (
                <li className={`${styles.card} ${styles.picked}`} key={place.id}>
                  {/* 438:3171: a 66px thumbnail. Decorative — the name beside
                    it is the accessible content. */}
                  {place.thumbnailUrl && place.thumbnailAttribution ? (
                    <PlaceThumbnail place={place} size={66} />
                  ) : (
                    <span aria-hidden="true" className={styles.thumb} />
                  )}
                  <span className={styles.cardText}>
                    <span className={styles.nameRow}>
                      <span className={styles.name}>{place.name}</span>
                      <MustVisitBadge label={t('mustVisit.badge')} />
                    </span>
                    <span className={styles.meta}>{meta(place)}</span>
                    <PlaceAttribution compact place={place} />
                  </span>
                  <button
                    type="button"
                    className={styles.action}
                    aria-label={`${place.name} ${t('mustVisit.remove')}`}
                    disabled={locked}
                    onClick={() => {
                      onRemove(place.id);
                    }}
                  >
                    {t('mustVisit.remove')}
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>

        {/* #185's partial failure: the trip exists and these picks are not on
            it. No Figma frame yet (FCR-039), so this is an FE placeholder in
            the wizard's own error style. Hidden while a retry is in flight and
            rendered again if it fails, so the alert is announced each time. */}
        {showUnsaved ? (
          <p
            className={`${styles.state} ${styles.unsaved}`}
            ref={unsavedRef}
            role="alert"
          >
            {t('mustVisit.unsaved', {
              count: unsaved.length,
              places: unsaved.map((place) => place.name).join(', '),
            })}
          </p>
        ) : null}
      </div>

      {/* The two exits now do different things, which is the whole point of
          #185: both used to call navigate('/feed'), so a traveller who picked
          places and pressed 이대로 채우기 got the same trip as one who pressed
          건너뛰기, and the picks vanished with no word. 이대로 채우기 carries
          them into the trip; 건너뛰기 states that there are none.

          Both create the trip, so both are blocked while one is in flight —
          a second press would be a second trip, which Idempotency-Key guards
          against but the user should not have to discover.

          Once the trip exists with picks unsaved, neither is offered: each
          would create the trip again. The same bar carries 다시 시도, which
          re-sends only those picks, and 여행으로 가기. */}
      {unsaved.length > 0 ? (
        <BottomCta
          buttonRef={retryRef}
          fixed
          label={t('mustVisit.retry')}
          disabled={isSubmitting}
          onClick={onRetryUnsaved}
          secondary={
            <button
              type="button"
              className={styles.skip}
              disabled={isSubmitting}
              onClick={onOpenTrip}
            >
              {t('mustVisit.openTrip')}
            </button>
          }
        />
      ) : (
        <BottomCta
          fixed
          label={t('mustVisit.next')}
          disabled={isSubmitting}
          onClick={onSubmit}
          secondary={
            <button
              type="button"
              className={styles.skip}
              disabled={isSubmitting}
              onClick={onSkip}
            >
              {t('mustVisit.skip')}
            </button>
          }
        />
      )}
    </div>
  );
}
