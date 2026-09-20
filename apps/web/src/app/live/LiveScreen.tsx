import { useState } from 'react';
import { Link } from 'react-router';
import { useI18n } from '../../i18n/I18nProvider.js';
import type { MessageKey } from '../../i18n/messages.js';
import {
  useLiveAreaPlaces,
  useLiveAreas,
  usePlaceSearch,
} from '../../shared/api/index.js';
import {
  CrowdLevel,
  DataAttribution,
  SearchField,
  StateLabel,
  type SourceState,
} from '../../shared/ui/index.js';
import styles from './LiveScreen.module.css';

const STATES: SourceState[] = [
  'LIVE',
  'FORECAST',
  'QUALITATIVE',
  'STALE',
  'UNAVAILABLE',
  'REPLAY',
];

// S11-1L `716:4377`: map capability is OFF in the submission profile, so the
// complete list is the primary UI. No geolocation API is called and no
// viewport is sent. Selecting an area is the only action that asks for places.
export function LiveScreen() {
  const { t } = useI18n();
  const areas = useLiveAreas();
  const [query, setQuery] = useState('');
  const [selectedAreaId, setSelectedAreaId] = useState<string | null>(null);
  const places = useLiveAreaPlaces(selectedAreaId);
  const search = usePlaceSearch(query);
  const stateLabels = Object.fromEntries(
    STATES.map((state) => [state, t(`state.${state}` as MessageKey)]),
  ) as Partial<Record<SourceState, string>>;

  return (
    <section aria-labelledby="live-heading" className={styles.screen}>
      <h1 className={styles.srOnly} id="live-heading">
        {t('live.title')}
      </h1>

      <SearchField
        label={t('live.searchLabel')}
        onChange={(event) => setQuery(event.target.value)}
        placeholder={t('live.search')}
        value={query}
      />

      {query.trim().length > 0 ? (
        <div className={styles.searchPanel}>
          {search.isPending ? <p role="status">{t('live.searching')}</p> : null}
          {search.isError ? <p role="alert">{t('live.searchError')}</p> : null}
          {search.isSuccess && search.data.items.length === 0 ? (
            <p>{t('live.searchEmpty')}</p>
          ) : null}
          {search.data && search.data.items.length > 0 ? (
            <ul className={styles.searchResults}>
              {search.data.items.map((place) => (
                <li key={place.id}>
                  <Link
                    aria-label={t('live.searchOpen', { name: place.name })}
                    to={`/live/places/${place.id}`}
                  >
                    <span>{place.name}</span>
                    <span>{place.regionName ?? place.address ?? ''}</span>
                    <span aria-hidden="true">›</span>
                  </Link>
                </li>
              ))}
            </ul>
          ) : null}
        </div>
      ) : null}

      <div className={styles.sectionHead}>
        <h2>{t('live.areas')}</h2>
        {areas.data ? <StateLabel labels={stateLabels} state={areas.data.mode} /> : null}
      </div>

      {areas.isPending ? (
        <div className={styles.stateCard} role="status">
          <strong>{t('live.loading')}</strong>
          <span>{t('live.loadingNote')}</span>
        </div>
      ) : null}

      {areas.isError ? (
        <div className={styles.stateCard} role="alert">
          <strong>{t('live.error')}</strong>
          <button onClick={() => void areas.refetch()} type="button">
            {t('live.retry')}
          </button>
        </div>
      ) : null}

      {areas.isSuccess && areas.data.areas.length === 0 ? (
        <div className={styles.stateCard}>
          <strong>
            {areas.data.mode === 'UNAVAILABLE' ? t('live.unavailable') : t('live.empty')}
          </strong>
          <span>{t('live.emptyNote')}</span>
        </div>
      ) : null}

      {areas.data && areas.data.areas.length > 0 ? (
        <ul className={styles.areaList}>
          {areas.data.areas.map((area) => (
            <li key={area.id}>
              <button
                aria-expanded={selectedAreaId === area.id}
                className={styles.areaButton}
                data-selected={selectedAreaId === area.id || undefined}
                onClick={() => {
                  setSelectedAreaId((current) => (current === area.id ? null : area.id));
                }}
                type="button"
              >
                <span>{area.name}</span>
                <CrowdLevel
                  crowd={area.crowd}
                  stateLabels={stateLabels}
                  unavailableReason={t('live.noReading')}
                />
                <span aria-hidden="true">›</span>
              </button>

              {selectedAreaId === area.id ? (
                <div className={styles.placePanel}>
                  {places.isPending ? (
                    <p role="status">{t('live.placesLoading')}</p>
                  ) : null}
                  {places.isError ? <p role="alert">{t('live.placesError')}</p> : null}
                  {places.data?.length === 0 ? <p>{t('live.placesEmpty')}</p> : null}
                  {places.data && places.data.length > 0 ? (
                    <ul className={styles.placeList}>
                      {places.data.map((item) => (
                        <li key={item.place.id}>
                          <Link
                            aria-label={t('live.searchOpen', { name: item.place.name })}
                            className={styles.placeLink}
                            to={`/live/places/${item.place.id}`}
                          >
                            <span className={styles.placeName}>{item.place.name}</span>
                            <CrowdLevel
                              crowd={item.crowd ?? null}
                              stateLabels={stateLabels}
                              unavailableReason={t('live.noReading')}
                            />
                            <span aria-hidden="true">›</span>
                          </Link>
                          {item.crowd ? (
                            <DataAttribution compact provenance={item.crowd.provenance} />
                          ) : null}
                        </li>
                      ))}
                    </ul>
                  ) : null}
                </div>
              ) : null}
            </li>
          ))}
        </ul>
      ) : null}
    </section>
  );
}
