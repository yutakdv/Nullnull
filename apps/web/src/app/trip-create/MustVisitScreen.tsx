import { useState } from 'react';
import { useNavigate } from 'react-router';
import type { components } from '@nullnull/api-client';
import { useI18n } from '../../i18n/I18nProvider.js';
import { usePlaceSearch } from '../../shared/api/index.js';
import {
  BottomCta,
  DataAttribution,
  MustVisitBadge,
  SearchField,
} from '../../shared/ui/index.js';
import styles from './MustVisitScreen.module.css';

type PlaceSummary = components['schemas']['PlaceSummary'];

// Figma: S02-4B must-visit `438:3158`.
//
// FR-TRC-04/05/08: pick the places that must stay, then let the rest be filled.
// A MUST_VISIT pick is a constraint on the trip, never a scheduled item — it
// carries no date or time here (invariant 2).
//
// MOCK DATA: searchPlaces has no approved example, so the msw fixture behind it
// is a schema-valid guess (packages/contracts). The screen calls the real
// generated client, so BA-022 landing removes the fixture and handler only.
//
// Waiting on the contract, not missed: the Figma card shows a crowd level, a
// "공식 혼잡 예측" badge and a source line, but neither PlaceSummary nor
// PlaceDetail carries a crowd field yet. Backend/AI confirmed crowd is not
// implemented and is planned, so the Figma design stands and this card ships
// without it -- rendering a number the contract cannot source is what
// invariant 8 forbids. Add it here once PlaceSummary gains crowd with its
// provenance and comparison eligibility (FCR-029).

export function MustVisitScreen() {
  const { t } = useI18n();
  const navigate = useNavigate();
  const [query, setQuery] = useState('');
  const [picked, setPicked] = useState<PlaceSummary[]>([]);
  const search = usePlaceSearch(query);

  const pickedIds = new Set(picked.map((place) => place.id));

  function meta(place: PlaceSummary): string {
    // categoryName, not categoryCode. BA-022 made the distinction explicit in
    // the contract: the code is "opaque provider-derived … not display copy",
    // and a null name means "do not show a category", never "unknown". So a
    // missing name drops the segment rather than falling back to the code.
    return [place.categoryName, place.regionName, place.address]
      .filter((part): part is string => typeof part === 'string' && part.length > 0)
      .join(' · ');
  }

  return (
    <section className={styles.screen} aria-labelledby="must-visit-heading">
      <p className={styles.step}>{t('mustVisit.step')}</p>

      <h1 className={styles.title} id="must-visit-heading">
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
          {search.isSuccess && search.data.items.length > 0 ? (
            <ul className={styles.list} aria-labelledby="search-results">
              {search.data.items.map((place) => (
                <li className={styles.card} key={place.id}>
                  {/* 438:3171: a 66px thumbnail. Decorative — the name beside
                      it is the accessible content. */}
                  {place.thumbnailUrl ? (
                    <img
                      alt=""
                      className={styles.thumb}
                      height={66}
                      src={place.thumbnailUrl}
                      width={66}
                    />
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
                    {place.sourceAttribution ? (
                      <DataAttribution compact provenance={place.sourceAttribution} />
                    ) : null}
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
                    disabled={pickedIds.has(place.id)}
                    onClick={() => {
                      setPicked((current) => [...current, place]);
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
                {place.thumbnailUrl ? (
                  <img
                    alt=""
                    className={styles.thumb}
                    height={66}
                    src={place.thumbnailUrl}
                    width={66}
                  />
                ) : (
                  <span aria-hidden="true" className={styles.thumb} />
                )}
                <span className={styles.cardText}>
                  <span className={styles.nameRow}>
                    <span className={styles.name}>{place.name}</span>
                    <MustVisitBadge label={t('mustVisit.badge')} />
                  </span>
                  <span className={styles.meta}>{meta(place)}</span>
                  {place.sourceAttribution ? (
                    <DataAttribution compact provenance={place.sourceAttribution} />
                  ) : null}
                </span>
                <button
                  type="button"
                  className={styles.action}
                  aria-label={`${place.name} ${t('mustVisit.remove')}`}
                  onClick={() => {
                    setPicked((current) => current.filter((p) => p.id !== place.id));
                  }}
                >
                  {t('mustVisit.remove')}
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>

      <BottomCta
        label={t('mustVisit.next')}
        onClick={() => {
          void navigate('/feed');
        }}
        secondary={
          <button
            type="button"
            className={styles.skip}
            onClick={() => {
              void navigate('/feed');
            }}
          >
            {t('mustVisit.skip')}
          </button>
        }
      />
    </section>
  );
}
