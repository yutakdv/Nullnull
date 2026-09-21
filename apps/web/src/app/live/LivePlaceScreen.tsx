import type { components } from '@nullnull/api-client';
import { useEffect, useRef, useState } from 'react';
import { Link, useNavigate, useOutletContext, useParams } from 'react-router';
import { useI18n } from '../../i18n/I18nProvider.js';
import type { MessageKey } from '../../i18n/messages.js';
import { useAddTripCandidate, useLivePlace } from '../../shared/api/index.js';
import {
  BottomCta,
  CrowdLevel,
  DataAttribution,
  NavBar,
  StateLabel,
  type SourceState,
} from '../../shared/ui/index.js';
import styles from './LivePlaceScreen.module.css';
import type { AppShellOutletContext } from '../AppShell.js';
import { restoreFocusTo } from '../../shared/ui/components/focus-restore.js';

const STATES: SourceState[] = [
  'LIVE',
  'FORECAST',
  'QUALITATIVE',
  'STALE',
  'UNAVAILABLE',
  'REPLAY',
];

type CrowdMetric = components['schemas']['CrowdMetric'];

function canCompareCrowd(
  current: CrowdMetric | null,
  alternative: CrowdMetric | null,
): boolean {
  if (!current || !alternative) return false;
  const currentSource = current.provenance;
  const alternativeSource = alternative.provenance;
  if (
    currentSource.comparisonEligible !== true ||
    alternativeSource.comparisonEligible !== true ||
    !currentSource.comparisonAxis ||
    currentSource.comparisonAxis !== alternativeSource.comparisonAxis
  ) {
    return false;
  }
  // TEMPORAL eligibility only makes points inside one metric series comparable.
  // It never authorizes a numeric comparison between two different places.
  if (currentSource.comparisonAxis !== 'SPATIAL') return false;
  return (
    currentSource.source === alternativeSource.source &&
    currentSource.scope === alternativeSource.scope &&
    currentSource.comparisonGroupId === alternativeSource.comparisonGroupId &&
    currentSource.snapshotSetId === alternativeSource.snapshotSetId
  );
}

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
  const saveButtonRef = useRef<HTMLButtonElement>(null);
  const restoreSaveFocus = useRef(false);
  const stateLabels = Object.fromEntries(
    STATES.map((state) => [state, t(`state.${state}` as MessageKey)]),
  ) as Partial<Record<SourceState, string>>;

  useEffect(() => {
    if (!addCandidate.isPending && restoreSaveFocus.current) {
      restoreSaveFocus.current = false;
      restoreFocusTo(saveButtonRef.current);
    }
  }, [addCandidate.isPending]);

  function saveToTrip() {
    if (!detail.data || !activeTripId) {
      void navigate('/trips/select');
      return;
    }
    restoreSaveFocus.current = document.activeElement === saveButtonRef.current;
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
        titleSize="large"
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
        <div className={styles.body} data-has-fixed-action={activeTripReady || undefined}>
          <div className={styles.titleRow}>
            <h1 id="live-place-heading">{detail.data.place.name}</h1>
            <StateLabel
              labels={stateLabels}
              observedAt={
                detail.data.crowd?.provenance.observedAt ??
                detail.data.crowd?.provenance.targetAt ??
                detail.data.crowd?.provenance.fetchedAt ??
                null
              }
              state={detail.data.dataState}
            />
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
            {detail.data.related.state === 'NONE' ? (
              <Link className={styles.recovery} to="/live">
                {t('live.related.browse')}
              </Link>
            ) : null}
            {detail.data.related.items.length > 0 ? (
              <ul className={styles.relatedList}>
                {detail.data.related.items.map((item) => (
                  <li key={item.place.id}>
                    <Link to={`/live/places/${item.place.id}`}>{item.place.name}</Link>
                    <span>{item.relationReason}</span>
                    {canCompareCrowd(detail.data.crowd ?? null, item.crowd ?? null) ? (
                      <CrowdLevel
                        crowd={item.crowd ?? null}
                        stateLabels={stateLabels}
                        unavailableReason={t('live.noReading')}
                      />
                    ) : item.crowd ? (
                      <span className={styles.ineligible}>
                        {t('live.related.ineligible')}
                      </span>
                    ) : (
                      <CrowdLevel
                        crowd={null}
                        stateLabels={stateLabels}
                        unavailableReason={t('live.noReading')}
                      />
                    )}
                    <DataAttribution compact provenance={item.provenance} />
                  </li>
                ))}
              </ul>
            ) : null}
          </section>

          {activeTripReady ? (
            <BottomCta
              buttonRef={saveButtonRef}
              disabled={addCandidate.isPending}
              fixed
              label={
                addCandidate.isPending
                  ? t('live.detail.saving')
                  : activeTripId
                    ? t('live.detail.save')
                    : t('live.detail.chooseTrip')
              }
              onClick={saveToTrip}
              secondary={
                <div className={styles.saveFeedback}>
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
              }
              secondaryKind="note"
            />
          ) : null}
        </div>
      ) : null}
    </section>
  );
}
