import type { components } from '@nullnull/api-client';
import { useCallback, useState, useSyncExternalStore } from 'react';
import { onlineManager } from '@tanstack/react-query';
import { Link, useNavigate, useOutletContext } from 'react-router';
import { useI18n } from '../../i18n/I18nProvider.js';
import type { MessageKey } from '../../i18n/messages.js';
import {
  useLiveAreaPlaces,
  useLiveAreas,
  usePlaceSearch,
  usePlaceDetail,
} from '../../shared/api/index.js';
import {
  Chip,
  CrowdLevel,
  DataAttribution,
  SearchField,
  StateLabel,
  type SourceState,
} from '../../shared/ui/index.js';
import styles from './LiveScreen.module.css';
import { KakaoLiveMap } from './KakaoLiveMap.js';
import type { AppShellOutletContext } from '../AppShell.js';
import { formatReferenceTime } from '../../shared/crowd/reference-time.js';

const EMPTY_AREAS: components['schemas']['LiveArea'][] = [];

const STATES: SourceState[] = [
  'LIVE',
  'FORECAST',
  'QUALITATIVE',
  'STALE',
  'UNAVAILABLE',
  'REPLAY',
];

type CrowdMetric = components['schemas']['CrowdMetric'];

export function LiveScreen() {
  const { locale, t } = useI18n();
  const navigate = useNavigate();
  const { sessionReady } = useOutletContext<AppShellOutletContext>();
  const areas = useLiveAreas(sessionReady);
  const online = useSyncExternalStore(
    (notify) => onlineManager.subscribe(notify),
    () => onlineManager.isOnline(),
  );
  const [query, setQuery] = useState('');
  const [selectedAreaId, setSelectedAreaId] = useState<string | null>(null);
  const [selectedPlaceId, setSelectedPlaceId] = useState<string | null>(null);
  const selectedPlace = usePlaceDetail(selectedPlaceId);
  const places = useLiveAreaPlaces(selectedAreaId);
  const search = usePlaceSearch(query);
  const stateLabels = Object.fromEntries(
    STATES.map((state) => [state, t(`state.${state}` as MessageKey)]),
  ) as Partial<Record<SourceState, string>>;
  const seoulLevelLabels = {
    1: t('live.crowd.seoul.level1'),
    2: t('live.crowd.seoul.level2'),
    3: t('live.crowd.seoul.level3'),
    4: t('live.crowd.seoul.level4'),
  };
  const crowdPresentation = (crowd: CrowdMetric | null) =>
    crowd?.provenance.source === 'SEOUL_CITYDATA'
      ? {
          levelLabel: crowd.ordinalLevel
            ? t('live.crowd.seoul.levelLabel', { level: crowd.ordinalLevel })
            : undefined,
          levelLabels: seoulLevelLabels,
        }
      : {};
  const selectArea = useCallback((areaId: string) => {
    setSelectedAreaId((current) => (current === areaId ? null : areaId));
  }, []);
  const openPlace = useCallback(
    (placeId: string) => {
      void navigate(`/live/places/${placeId}`);
    },
    [navigate],
  );
  const selectedArea = areas.data?.areas.find((area) => area.id === selectedAreaId);
  const observedAt = areas.data?.areas.find((area) => area.crowd)?.crowd?.provenance
    .observedAt;

  return (
    <section aria-labelledby="live-heading" className={styles.screen}>
      <header className={styles.header}>
        <div className={styles.titleRow}>
          <h1 id="live-heading">{t('live.title')}</h1>
          {areas.data ? (
            <div className={styles.persistentState} data-testid="live-persistent-state">
              <StateLabel
                labels={stateLabels}
                observedAt={
                  observedAt
                    ? t('crowd.observedAt', {
                        date: formatReferenceTime(observedAt, locale),
                      })
                    : null
                }
                state={areas.data.mode}
              />
            </div>
          ) : null}
        </div>
        <SearchField
          id="live-search"
          label={t('live.searchLabel')}
          onChange={(event) => setQuery(event.target.value)}
          placeholder={t('live.search')}
          value={query}
        />

        {query.trim().length > 0 ? (
          <div className={styles.searchPanel}>
            {search.isPending ? <p role="status">{t('live.searching')}</p> : null}
            {search.isError ? (
              // Figma 684:4402 pairs the failure with a retry: the query is the
              // traveller's own words, so they should not have to retype it.
              <p role="alert">
                {t('live.searchError')}{' '}
                <button
                  disabled={search.isFetching}
                  onClick={() => void search.refetch()}
                  type="button"
                >
                  {t('live.retry')}
                </button>
              </p>
            ) : null}
            {search.isSuccess && search.data.items.length === 0 ? (
              <p>{t('live.searchEmpty')}</p>
            ) : null}
            {search.data && search.data.items.length > 0 ? (
              <ul className={styles.searchResults}>
                {search.data.items.map((place) => (
                  <li key={place.id}>
                    <Link
                      aria-label={t('live.searchOpen', { name: place.name })}
                      className={styles.searchResult}
                      to={`/live/places/${place.id}`}
                    >
                      <span>{place.name}</span>
                      <span>{place.regionName ?? place.address ?? ''}</span>
                    </Link>
                    <button
                      aria-pressed={selectedPlaceId === place.id}
                      className={styles.showOnMap}
                      onClick={() => setSelectedPlaceId(place.id)}
                      type="button"
                    >
                      {t('live.searchShowOnMap', { name: place.name })}
                    </button>
                  </li>
                ))}
              </ul>
            ) : null}
          </div>
        ) : null}
      </header>

      <div className={styles.mapPanel}>
        <div className={styles.mapViewport}>
          <KakaoLiveMap
            areas={areas.data?.areas ?? EMPTY_AREAS}
            label={t('live.map.label')}
            onOpenPlace={openPlace}
            onSelectArea={selectArea}
            selectedPlace={selectedPlace.data ?? null}
            unavailableDetail={t('live.map.unavailableDetail')}
            unavailableTitle={t('live.map.unavailableTitle')}
          />
        </div>
        {selectedPlaceId && selectedPlace.isPending ? (
          <p role="status">{t('live.detail.loading')}</p>
        ) : null}
        {selectedPlace.isError ? <p role="alert">{t('live.map.placeError')}</p> : null}
        {selectedPlace.data?.sourceAttribution ? (
          <DataAttribution compact provenance={selectedPlace.data.sourceAttribution} />
        ) : null}
      </div>

      <section aria-label={t('live.sheet.title')} className={styles.listPanel}>
        <div className={styles.sheetBody}>
          <div
            aria-label={t('live.view.label')}
            className={styles.viewTabs}
            role="tablist"
          >
            <button aria-selected="true" role="tab" type="button">
              {t('live.view.current')}
            </button>
            <button
              aria-disabled="true"
              aria-selected="false"
              disabled
              role="tab"
              title={t('live.view.otherUnavailable')}
              type="button"
            >
              {t('live.view.other')}
            </button>
          </div>

          <div className={styles.sheetIntro}>
            <h2>{selectedArea?.name ?? t('live.sheet.heading')}</h2>
            <p>{t('live.sheet.description')}</p>
          </div>

          <div
            aria-label={t('live.filters.label')}
            className={styles.filters}
            data-scrolls-x
          >
            <Chip
              disabled
              disabledReason={t('live.filters.nearUnavailable')}
              label={t('live.filters.near')}
              size="sm"
            />
            <Chip
              disabled
              disabledReason={t('live.filters.crowdUnavailable')}
              label={t('live.filters.lowCrowd')}
              size="sm"
            />
            <Chip label={t('live.filters.all')} selected size="sm" />
          </div>

          {!online ? (
            <p role="status" className={styles.stateCard}>
              {t('live.offline')}
            </p>
          ) : null}
          {online && areas.isRefetching ? (
            <p role="status" className={styles.stateCard}>
              {t('live.refreshing')}
            </p>
          ) : null}
          {online && areas.isPending ? (
            <div className={styles.stateCard} role="status">
              <strong>{t('live.loading')}</strong>
              <span>{t('live.loadingNote')}</span>
            </div>
          ) : null}

          {areas.isError ? (
            <div className={styles.stateCard} role="alert">
              <strong>{t(areas.data ? 'live.refreshError' : 'live.error')}</strong>
              <button
                disabled={!online || areas.isFetching}
                onClick={() => void areas.refetch()}
                type="button"
              >
                {t('live.retry')}
              </button>
            </div>
          ) : null}

          {areas.data && areas.data.areas.length > 0 ? (
            <ul aria-label={t('live.areas')} className={styles.areaList}>
              {areas.data.areas.map((area) => (
                <li key={area.id}>
                  <button
                    aria-expanded={selectedAreaId === area.id}
                    className={styles.areaButton}
                    data-selected={selectedAreaId === area.id || undefined}
                    onClick={() => selectArea(area.id)}
                    type="button"
                  >
                    <span>{area.name}</span>
                    <CrowdLevel
                      crowd={area.crowd}
                      {...crowdPresentation(area.crowd)}
                      stateLabels={stateLabels}
                      unavailableReason={t('live.noReading')}
                    />
                    <span aria-hidden="true">›</span>
                  </button>
                  {area.crowd ? (
                    <DataAttribution compact provenance={area.crowd.provenance} />
                  ) : null}

                  {selectedAreaId === area.id ? (
                    <div className={styles.placePanel}>
                      {places.isPending ? (
                        <p role="status">{t('live.placesLoading')}</p>
                      ) : null}
                      {places.isError ? (
                        <p role="alert">{t('live.placesError')}</p>
                      ) : null}
                      {places.data?.length === 0 ? <p>{t('live.placesEmpty')}</p> : null}
                      {places.data && places.data.length > 0 ? (
                        <ul className={styles.placeList}>
                          {places.data.map((item) => (
                            <li key={item.place.id}>
                              <Link
                                aria-label={t('live.searchOpen', {
                                  name: item.place.name,
                                })}
                                className={styles.placeLink}
                                to={`/live/places/${item.place.id}`}
                              >
                                <span className={styles.placeName}>
                                  {item.place.name}
                                </span>
                                <span className={styles.placeMeta}>
                                  {item.place.categoryName ?? item.place.regionName ?? ''}
                                </span>
                                <CrowdLevel
                                  crowd={item.crowd ?? null}
                                  {...crowdPresentation(item.crowd ?? null)}
                                  stateLabels={stateLabels}
                                  unavailableReason={t('live.noReading')}
                                />
                                <span aria-hidden="true">›</span>
                              </Link>
                              <button
                                aria-pressed={selectedPlaceId === item.place.id}
                                className={styles.showOnMap}
                                onClick={() => setSelectedPlaceId(item.place.id)}
                                type="button"
                              >
                                {t('live.searchShowOnMap', { name: item.place.name })}
                              </button>
                              {item.place.sourceAttribution ? (
                                <DataAttribution
                                  compact
                                  provenance={item.place.sourceAttribution}
                                />
                              ) : null}
                              {item.crowd ? (
                                <DataAttribution
                                  compact
                                  provenance={item.crowd.provenance}
                                />
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

          {areas.isSuccess && areas.data.areas.length === 0 ? (
            <div className={styles.stateCard}>
              <strong>
                {areas.data.mode === 'UNAVAILABLE'
                  ? t('live.unavailable')
                  : t('live.empty')}
              </strong>
              <span>{t('live.emptyNote')}</span>
            </div>
          ) : null}
        </div>
      </section>
    </section>
  );
}
