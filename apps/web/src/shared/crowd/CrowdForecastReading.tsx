import type { components } from '@nullnull/api-client';
import { useI18n } from '../../i18n/I18nProvider.js';
import type { MessageKey } from '../../i18n/messages.js';
import { DataAttribution } from '../ui/components/DataAttribution.js';
import { StateLabel, type SourceState } from '../ui/components/StateLabel.js';
import { busiestCrowdPoint, crowdTargetDate } from './forecast.js';
import { formatReferenceTime } from './reference-time.js';
import styles from './CrowdForecastReading.module.css';

type CrowdMetric = components['schemas']['CrowdMetric'];
type CrowdSeries = components['schemas']['CrowdSeries'];

const STATES: SourceState[] = [
  'LIVE',
  'FORECAST',
  'QUALITATIVE',
  'STALE',
  'UNAVAILABLE',
  'REPLAY',
];

/** The exact dated reading returned by the server, with its own provenance. */
export function CrowdForecastReading({ point }: { point: CrowdMetric | null }) {
  const { locale, t } = useI18n();
  if (point === null || typeof point.value !== 'number') return null;

  const targetDate = crowdTargetDate(point);
  const dateLabel = targetDate
    ? new Intl.DateTimeFormat(locale, {
        month: 'numeric',
        day: 'numeric',
        timeZone: 'UTC',
      }).format(new Date(`${targetDate}T00:00:00Z`))
    : null;
  const value = new Intl.NumberFormat(locale, { maximumFractionDigits: 1 }).format(
    point.value,
  );
  const referenceAt = point.provenance.observedAt ?? point.provenance.fetchedAt;
  const referenceLabel = formatReferenceTime(referenceAt, locale);
  const stateLabels = Object.fromEntries(
    STATES.map((state) => [state, t(`state.${state}` as MessageKey)]),
  ) as Partial<Record<SourceState, string>>;

  return (
    <span className={styles.reading}>
      <span className={styles.summary}>
        <span>{t('crowd.relativeIndex', { value })}</span>
        {dateLabel ? <span>{t('crowd.targetDate', { date: dateLabel })}</span> : null}
        <StateLabel labels={stateLabels} state={point.state} />
        <span>
          {point.provenance.observedAt
            ? t('crowd.observedAt', { date: referenceLabel })
            : t('crowd.fetchedAt', { date: referenceLabel })}
        </span>
      </span>
      <DataAttribution compact provenance={point.provenance} />
    </span>
  );
}

/** One card's representative point, or an honest unavailable explanation. */
export function CrowdForecastCardReading({
  series,
}: {
  series: CrowdSeries | null | undefined;
}) {
  const { t } = useI18n();
  const point = busiestCrowdPoint(series);
  if (point !== null) return <CrowdForecastReading point={point} />;
  if (series?.state !== 'UNAVAILABLE') return null;

  const reason =
    series.unavailableReason === 'NO_COVERAGE'
      ? t('crowd.unavailable.NO_COVERAGE')
      : series.unavailableReason === 'PLACE_UNAVAILABLE'
        ? t('crowd.unavailable.PLACE_UNAVAILABLE')
        : t('crowd.unavailable.unknown');
  return <span className={styles.unavailable}>{reason}</span>;
}

export function CrowdForecastQueryState({
  series,
  loading,
  failed,
}: {
  series: CrowdSeries | null | undefined;
  loading: boolean;
  failed: boolean;
}) {
  const { t } = useI18n();
  if (loading)
    return (
      <span className={styles.unavailable} role="status">
        {t('crowd.loading')}
      </span>
    );
  if (failed)
    return (
      <span className={styles.unavailable} role="alert">
        {t('crowd.error')}
      </span>
    );
  if (series?.state !== 'UNAVAILABLE') return null;
  return <CrowdForecastCardReading series={series} />;
}
