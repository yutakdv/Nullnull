import { useRef, useState } from 'react';
import { Link, useNavigate, useOutletContext, useParams } from 'react-router';
import { useI18n } from '../../i18n/I18nProvider.js';
import type { MessageKey } from '../../i18n/messages.js';
import { useAddTripCandidate, useLivePlace } from '../../shared/api/index.js';
import {
  CrowdLevel,
  DataAttribution,
  NavBar,
  StateLabel,
  type SourceState,
} from '../../shared/ui/index.js';
import styles from './LivePlaceScreen.module.css';
import type { AppShellOutletContext } from '../AppShell.js';

const STATES: SourceState[] = [
  'LIVE',
  'FORECAST',
  'QUALITATIVE',
  'STALE',
  'UNAVAILABLE',
  'REPLAY',
];

// S11-2 `419:2617`: the server supplies the state, timestamp and both source
// credits. Null metrics remain absent rather than being turned into a score.
export function LivePlaceScreen() {
  const { placeId } = useParams();
  const navigate = useNavigate();
  const { t } = useI18n();
  const { activeTripId, activeTripReady } = useOutletContext<AppShellOutletContext>();
  const detail = useLivePlace(placeId ?? null);
  const addCandidate = useAddTripCandidate(activeTripId);
  const [saveStatus, setSaveStatus] = useState<'saved' | 'duplicate' | 'error' | null>(
    null,
  );
  const addKey = useRef<{ placeId: string; value: string } | null>(null);
  const stateLabels = Object.fromEntries(
    STATES.map((state) => [state, t(`state.${state}` as MessageKey)]),
  ) as Partial<Record<SourceState, string>>;

  function saveToTrip() {
    if (!detail.data || !activeTripId) {
      void navigate('/trips/select');
      return;
    }
    const id = detail.data.place.id;
    if (addKey.current?.placeId !== id) {
      addKey.current = { placeId: id, value: crypto.randomUUID() };
    }
    setSaveStatus(null);
    addCandidate.mutate(
      {
        request: { placeId: id, source: { type: 'LIVE' } },
        idempotencyKey: addKey.current.value,
      },
      {
        onSuccess: (result) => {
          addKey.current = null;
          setSaveStatus(result.duplicate ? 'duplicate' : 'saved');
        },
        onError: () => {
          setSaveStatus('error');
        },
      },
    );
  }

  return (
    <section aria-labelledby="live-place-heading" className={styles.screen}>
      <NavBar
        backLabel={t('live.detail.back')}
        onBack={() => void navigate('/live')}
        title={t('live.detail.title')}
      />

      {detail.isPending ? (
        <p className={styles.state} role="status">
          {t('live.detail.loading')}
        </p>
      ) : null}
      {detail.isError ? (
        <div className={styles.state} role="alert">
          <span>{t('live.detail.error')}</span>
          <button onClick={() => void detail.refetch()} type="button">
            {t('live.retry')}
          </button>
        </div>
      ) : null}

      {detail.data ? (
        <div className={styles.body}>
          <div className={styles.titleRow}>
            <h1 id="live-place-heading">{detail.data.place.name}</h1>
            <StateLabel labels={stateLabels} state={detail.data.dataState} />
          </div>
          {detail.data.place.address ? (
            <p className={styles.address}>{detail.data.place.address}</p>
          ) : null}
          {detail.data.place.sourceAttribution ? (
            <DataAttribution compact provenance={detail.data.place.sourceAttribution} />
          ) : null}

          <section aria-labelledby="live-place-crowd" className={styles.card}>
            <h2 id="live-place-crowd">{t('live.detail.crowd')}</h2>
            <CrowdLevel
              crowd={detail.data.crowd ?? null}
              stateLabels={stateLabels}
              unavailableReason={t('live.noReading')}
            />
            {detail.data.crowd ? (
              <DataAttribution compact provenance={detail.data.crowd.provenance} />
            ) : null}
          </section>

          <section aria-labelledby="live-place-related" className={styles.card}>
            <h2 id="live-place-related">{t('live.detail.related')}</h2>
            <p>{t(`live.related.${detail.data.related.state}` as MessageKey)}</p>
            {detail.data.related.items.length > 0 ? (
              <ul className={styles.relatedList}>
                {detail.data.related.items.map((item) => (
                  <li key={item.place.id}>
                    <Link to={`/live/places/${item.place.id}`}>{item.place.name}</Link>
                    <span>{item.relationReason}</span>
                    <CrowdLevel
                      crowd={item.crowd ?? null}
                      stateLabels={stateLabels}
                      unavailableReason={t('live.noReading')}
                    />
                    <DataAttribution compact provenance={item.provenance} />
                  </li>
                ))}
              </ul>
            ) : null}
          </section>

          {activeTripReady ? (
            <div className={styles.saveArea}>
              <button
                className={styles.saveButton}
                disabled={addCandidate.isPending}
                onClick={saveToTrip}
                type="button"
              >
                {addCandidate.isPending
                  ? t('live.detail.saving')
                  : activeTripId
                    ? t('live.detail.save')
                    : t('live.detail.chooseTrip')}
              </button>
              <p className={styles.saveNote}>{t('live.detail.saveNote')}</p>
              {saveStatus ? (
                <p
                  className={styles.saveStatus}
                  role={saveStatus === 'error' ? 'alert' : 'status'}
                >
                  {t(`live.detail.save.${saveStatus}` as MessageKey)}
                </p>
              ) : null}
            </div>
          ) : null}
        </div>
      ) : null}
    </section>
  );
}
