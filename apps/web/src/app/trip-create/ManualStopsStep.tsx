import { useState } from 'react';
import type { components } from '@nullnull/api-client';
import { useI18n } from '../../i18n/I18nProvider.js';
import type { MessageKey } from '../../i18n/messages.js';
import { usePlaceSearch } from '../../shared/api/index.js';
import {
  BottomCta,
  DataAttribution,
  PlaceThumbnail,
  SearchField,
} from '../../shared/ui/index.js';
import wizard from './TripWizardScreen.module.css';
import styles from './ManualStopsStep.module.css';
import {
  canAddStop,
  stopsOn,
  tripDays,
  type Daypart,
  type DraftStop,
  type WizardDraft,
} from './wizard.js';

type PlaceSummary = components['schemas']['PlaceSummary'];

// Figma: S02-4C-C 직접 입력 `438:3199` (FR-TRC-05, FE-103).
//
// The manual half of the input-method branch: the traveller already has an
// itinerary and types it in day by day, instead of pasting it. Reached from
// InputMethodStep's 직접 입력, and before this existed that choice called
// setStep(5) with nothing rendering there — picking it landed on a blank
// screen, the reachable dead end #185 is about.
//
// A STEP, not a route, for the reason InputMethodStep gives: the dates and
// interests collected in steps 1-3 live in the wizard's draft, and a route
// would start from EMPTY_DRAFT.
//
// The day headers come from the trip's own range (`tripDays`), so there is
// exactly one list of days in this wizard and this screen cannot offer a day
// the trip does not have.
//
// NO CROWD FIGURE, and that is deliberate rather than unfinished: the frame
// draws none here, and PlaceSummary carries no crowd scalar to draw one from.
// The same line is left empty for the same reason in MoveDaySheet.tsx and
// ScheduleCandidateSheet.tsx, tracked by #105 (FCR-029).
//
// MOCK DATA: searchPlaces has no approved example, so the msw fixture behind
// it is a schema-valid guess (packages/contracts), as MustVisitScreen notes.

export interface ManualStopsStepProps {
  draft: WizardDraft;
  /** Add a place under one day. The wizard mints the key and applies the cap. */
  onAddStop: (date: string, place: PlaceSummary) => void;
  onRemoveStop: (key: string) => void;
  onSetDaypart: (key: string, daypart: Daypart) => void;
  /** Create the trip carrying these stops as seedItems. */
  onSubmit: () => void;
  /** Create it with none, which is an answer and not a cancel. */
  onSkip: () => void;
  isSubmitting: boolean;
}

const DAYPARTS: Daypart[] = ['MORNING', 'AFTERNOON'];

export function ManualStopsStep({
  draft,
  onAddStop,
  onRemoveStop,
  onSetDaypart,
  onSubmit,
  onSkip,
  isSubmitting,
}: ManualStopsStepProps) {
  const { locale, t } = useI18n();
  // Which day's 장소 추가 was pressed; null closes the search. One search at a
  // time, because a result has to land on a known day — the frame opens the
  // picker under the day whose button was pressed.
  const [addingTo, setAddingTo] = useState<string | null>(null);
  const [query, setQuery] = useState('');
  const search = usePlaceSearch(addingTo === null ? '' : query);

  const days = tripDays(draft);

  /** `10.4/일` — the header's date, in the active locale's weekday. */
  function dayLabel(date: string): string {
    const parsed = new Date(`${date}T00:00:00Z`);
    const weekday = new Intl.DateTimeFormat(locale, {
      weekday: 'short',
      timeZone: 'UTC',
    }).format(parsed);
    const month = parsed.getUTCMonth() + 1;
    const day = parsed.getUTCDate();
    return `${String(month)}.${String(day)}/${weekday}`;
  }

  function meta(place: PlaceSummary): string {
    // categoryName, never categoryCode: BA-022 calls the code opaque and
    // provider-derived, and a null name means "show no category".
    return [place.categoryName, place.regionName]
      .filter((part): part is string => typeof part === 'string' && part.length > 0)
      .join(' · ');
  }

  function closeSearch() {
    setAddingTo(null);
    setQuery('');
  }

  function renderStop(stop: DraftStop, index: number) {
    return (
      <li className={styles.stop} key={stop.key}>
        {/* The numbered dot on the spine. Decorative: the order is already in
            the list structure, and a screen reader counts the items itself. */}
        <span aria-hidden="true" className={styles.dot}>
          {index + 1}
        </span>
        <div className={styles.card}>
          <span className={styles.stopName}>{stop.place.name}</span>
          {/* 오전 ▾ / 오후 ▾. A real control, not the frame's static text — but
              it only groups and orders the stop, and never becomes a
              startTime. seedItemsOf says why at length. */}
          <label className={styles.daypartLabel}>
            <span className={styles.srOnly}>
              {t('manual.daypartFor', { place: stop.place.name })}
            </span>
            <select
              className={styles.daypart}
              value={stop.daypart}
              onChange={(event) => {
                onSetDaypart(stop.key, event.target.value as Daypart);
              }}
            >
              {DAYPARTS.map((part) => (
                <option key={part} value={part}>
                  {t(`manual.daypart.${part}` as MessageKey)}
                </option>
              ))}
            </select>
          </label>
          <button
            type="button"
            className={styles.remove}
            // Every row's ✕ would otherwise read the same to a screen reader,
            // which cannot tell which stop it drops. The visible glyph stays
            // short; only the accessible name carries the place.
            aria-label={t('manual.removeNamed', { place: stop.place.name })}
            onClick={() => {
              onRemoveStop(stop.key);
            }}
          >
            <span aria-hidden="true">✕</span>
          </button>
        </div>
      </li>
    );
  }

  return (
    <>
      <div className={wizard.head}>
        <h1 className={wizard.title} id="wizard-heading">
          {t('manual.title')}
        </h1>
        <p className={wizard.lead}>{t('manual.lead')}</p>
      </div>

      {days.map((date) => {
        const stops = stopsOn(draft, date);
        const headingId = `day-${date}`;
        return (
          <section aria-labelledby={headingId} className={styles.day} key={date}>
            <h2 className={styles.dayHead} id={headingId}>
              <span className={styles.dayName}>
                {t('manual.day', { n: days.indexOf(date) + 1 })}
              </span>
              <span className={styles.dayDate}>{dayLabel(date)}</span>
            </h2>

            {stops.length > 0 ? (
              <ol className={styles.stops}>{stops.map(renderStop)}</ol>
            ) : null}

            {addingTo === date ? (
              <div className={styles.search}>
                <SearchField
                  label={t('manual.searchLabel')}
                  placeholder={t('manual.search')}
                  value={query}
                  onChange={(event) => {
                    setQuery(event.target.value);
                  }}
                />
                {query.trim() ? (
                  <>
                    {search.isPending ? (
                      <p className={styles.state} role="status">
                        {t('manual.searching')}
                      </p>
                    ) : null}
                    {search.isError ? (
                      <p className={styles.state} role="alert">
                        {t('manual.searchError')}
                      </p>
                    ) : null}
                    {search.isSuccess && search.data.items.length === 0 ? (
                      <p className={styles.state}>{t('manual.noResults')}</p>
                    ) : null}
                    {search.isSuccess && search.data.items.length > 0 ? (
                      <ul className={styles.results}>
                        {search.data.items.map((place) => (
                          <li className={styles.result} key={place.id}>
                            {place.thumbnailUrl && place.thumbnailAttribution ? (
                              <PlaceThumbnail place={place} size={44} />
                            ) : (
                              <span aria-hidden="true" className={styles.thumb} />
                            )}
                            <span className={styles.resultText}>
                              <span className={styles.resultName}>{place.name}</span>
                              <span className={styles.resultMeta}>{meta(place)}</span>
                              {/* FCR-031 / CMP-ATT-001: the credit the server
                                  approved, verbatim. Never composed here. */}
                              {place.sourceAttribution ? (
                                <DataAttribution
                                  compact
                                  provenance={place.sourceAttribution}
                                />
                              ) : null}
                            </span>
                            <button
                              type="button"
                              className={styles.pick}
                              aria-label={t('manual.addNamed', { place: place.name })}
                              onClick={() => {
                                onAddStop(date, place);
                                closeSearch();
                              }}
                            >
                              {t('manual.pick')}
                            </button>
                          </li>
                        ))}
                      </ul>
                    ) : null}
                  </>
                ) : null}
                <button type="button" className={styles.cancel} onClick={closeSearch}>
                  {t('manual.cancel')}
                </button>
              </div>
            ) : (
              <button
                type="button"
                className={styles.add}
                // Disabled at the contract's 100, rather than accepting the
                // 101st and dropping it silently.
                disabled={!canAddStop(draft)}
                aria-label={t('manual.addToDay', { day: dayLabel(date) })}
                onClick={() => {
                  setAddingTo(date);
                  setQuery('');
                }}
              >
                {t('manual.add')}
              </button>
            )}
          </section>
        );
      })}

      {/* Both exits create the trip, so both are blocked while one is in
          flight: a second press would be a second trip, which the
          Idempotency-Key guards against but the user should not have to
          discover. 이 일정으로 시작하기 carries the stops; 건너뛰기 states
          there are none, the same distinction MustVisitStep draws (#185). */}
      <BottomCta
        label={t('manual.next')}
        disabled={isSubmitting}
        onClick={onSubmit}
        secondary={
          <button
            type="button"
            className={styles.skip}
            disabled={isSubmitting}
            onClick={onSkip}
          >
            {t('manual.skip')}
          </button>
        }
      />
    </>
  );
}
