import type { components } from '@nullnull/api-client';
import { useCallback, useEffect, useRef, useState, useSyncExternalStore } from 'react';
import { onlineManager } from '@tanstack/react-query';
import { Link, useLocation, useNavigate, useOutletContext } from 'react-router';
import { useI18n } from '../../i18n/I18nProvider.js';
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
  PlaceAttribution,
  SearchField,
  StateLabel,
} from '../../shared/ui/index.js';
import styles from './LiveScreen.module.css';
import { KakaoLiveMap } from './KakaoLiveMap.js';
import type { AppShellOutletContext } from '../AppShell.js';
import { restoreFocusTo } from '../../shared/ui/components/focus-restore.js';
import { readLiveReturn } from './live-return.js';
import { formatReferenceTime } from '../../shared/crowd/reference-time.js';
import { PlaceSearchMore } from '../../shared/search/PlaceSearchMore.js';

const EMPTY_AREAS: components['schemas']['LiveArea'][] = [];

export function LiveScreen() {
  const { locale, t } = useI18n();
  const navigate = useNavigate();
  const { sessionReady } = useOutletContext<AppShellOutletContext>();
  const areas = useLiveAreas(sessionReady);
  const online = useSyncExternalStore(
    (notify) => onlineManager.subscribe(notify),
    () => onlineManager.isOnline(),
  );
  const location = useLocation();
  // Back from a place detail brings its origin: the expanded area, the search
  // words and the link that was followed.
  const returned = readLiveReturn(location.state);
  const [query, setQuery] = useState(returned?.query ?? '');
  const [selectedAreaId, setSelectedAreaId] = useState<string | null>(
    returned?.areaId ?? null,
  );
  const [selectedPlaceId, setSelectedPlaceId] = useState<string | null>(null);
  const selectedPlace = usePlaceDetail(selectedPlaceId);
  const places = useLiveAreaPlaces(selectedAreaId);
  const search = usePlaceSearch(query, locale);
  const searchList = useRef<HTMLUListElement>(null);
  const selectArea = useCallback((areaId: string) => {
    setSelectedAreaId((current) => (current === areaId ? null : areaId));
  }, []);
  const returnState = useCallback(
    (placeId: string) => ({ liveReturn: { areaId: selectedAreaId, query, placeId } }),
    [query, selectedAreaId],
  );
  const openPlace = useCallback(
    (placeId: string) => {
      void navigate(`/live/places/${placeId}`, { state: returnState(placeId) });
    },
    [navigate, returnState],
  );
  // Focus goes back to the followed link once the list that holds it has
  // rendered again: the area's places or the search results arrive
  // asynchronously. When neither can hold it any more (the place left the
  // area), focus falls back to <main> rather than to the top of the document.
  const restoredFor = useRef<string | null>(null);
  const areaSettled = !returned?.areaId || places.isSuccess || places.isError;
  const searchSettled = !returned?.query || search.isSuccess || search.isError;
  useEffect(() => {
    if (!returned || restoredFor.current === location.key) return;
    // Compared by value rather than built into a selector: the id comes from
    // router state, and no string from there should become selector syntax.
    const link =
      Array.from(document.querySelectorAll<HTMLElement>('[data-live-place-link]')).find(
        (candidate) => candidate.dataset.livePlaceLink === returned.placeId,
      ) ?? null;
    if (!link && !(areaSettled && searchSettled)) return;
    restoredFor.current = location.key;
    restoreFocusTo(link);
  });
  const selectedArea = areas.data?.areas.find((area) => area.id === selectedAreaId);
  const observedAt = areas.data?.areas.find((area) => area.crowd)?.crowd?.provenance
    .observedAt;
  // The header speaks for the list: while any area's provider reports an
  // incident, the list is not called live (A-068). The rows say which.
  const listFlags = areas.data?.areas.some((area) =>
    area.crowd?.provenance.qualityFlags.includes('PROVIDER_INCIDENT'),
  )
    ? (['PROVIDER_INCIDENT'] as const)
    : undefined;

  return (
    <section aria-labelledby="live-heading" className={styles.screen}>
      <header className={styles.header}>
        <div className={styles.titleRow}>
          <h1 id="live-heading">{t('live.title')}</h1>
          {areas.data ? (
            <div className={styles.persistentState} data-testid="live-persistent-state">
              <StateLabel
                observedAt={
                  observedAt
                    ? t('crowd.observedAt', {
                        date: formatReferenceTime(observedAt, locale),
                      })
                    : null
                }
                qualityFlags={listFlags}
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
            {search.isError && !search.isFetchNextPageError ? (
              // Figma 684:4402 pairs the failure with a retry: the query is the
              // traveller's own words, so they should not have to retype it.
              // A failed later page is not this: the results stay, and the
              // continuation below reports and retries it.
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
              <ul className={styles.searchResults} ref={searchList}>
                {search.data.items.map((place) => (
                  <li key={place.id}>
                    <Link
                      aria-label={t('live.searchOpen', { name: place.name })}
                      className={styles.searchResult}
                      data-live-place-link={place.id}
                      state={returnState(place.id)}
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
                    {/* CMP-ATT-001: a result names a catalogue place, so it
                        carries that place's credit like any other row. */}
                    <span className={styles.resultCredit}>
                      <PlaceAttribution compact place={place} />
                    </span>
                  </li>
                ))}
              </ul>
            ) : null}
            <PlaceSearchMore list={searchList} search={search} />
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
        {/* The credits of the places the map shows, once each. Today that is
            the one selected place KakaoLiveMap draws as a marker; the marker
            itself cannot hold a link, so the credit sits under the map. */}
        <div data-map-credits="">
          <PlaceAttribution compact place={selectedPlace.data} />
        </div>
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
                                data-live-place-link={item.place.id}
                                state={returnState(item.place.id)}
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
                              <PlaceAttribution
                                also={item.crowd ? [item.crowd.provenance] : undefined}
                                compact
                                place={item.place}
                              />
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
