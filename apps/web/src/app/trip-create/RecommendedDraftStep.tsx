import type { components } from '@nullnull/api-client';
import { useI18n } from '../../i18n/I18nProvider.js';
import { isProblem } from '../../shared/api/index.js';
import {
  BottomCta,
  DataAttribution,
  IconPinVisit,
  IconPinVisitFilled,
} from '../../shared/ui/index.js';
import wizard from './TripWizardScreen.module.css';
import styles from './RecommendedDraftStep.module.css';
import { recommendedStopKey } from './recommended-draft.js';

type TripDraftPreview = components['schemas']['TripDraftPreview'];
type TripDraftStop = components['schemas']['TripDraftStop'];

export interface RecommendedDraftStepProps {
  preview: TripDraftPreview | null;
  error: unknown | null;
  loading: boolean;
  picked: ReadonlySet<string>;
  isSubmitting: boolean;
  createFailed: boolean;
  onTogglePick: (key: string) => void;
  onSubmit: () => void;
  onRetry: () => void;
  onChangeDates: () => void;
  onEdit: () => void;
}

function hasStops(preview: TripDraftPreview): boolean {
  return preview.days.some((day) => day.stops.length > 0);
}

export function RecommendedDraftStep({
  preview,
  error,
  loading,
  picked,
  isSubmitting,
  createFailed,
  onTogglePick,
  onSubmit,
  onRetry,
  onChangeDates,
  onEdit,
}: RecommendedDraftStepProps) {
  const { locale, t } = useI18n();
  const unavailable = isProblem(error) && error.code === 'SOURCE_UNAVAILABLE';
  const ready = preview?.state === 'READY' && hasStops(preview);
  const empty =
    preview?.state === 'EMPTY' || (preview?.state === 'READY' && !hasStops(preview));

  function dayLabel(date: string): string {
    const parsed = new Date(`${date}T00:00:00Z`);
    const weekday = new Intl.DateTimeFormat(locale, {
      weekday: 'short',
      timeZone: 'UTC',
    }).format(parsed);
    return `${String(parsed.getUTCMonth() + 1)}.${String(parsed.getUTCDate())}/${weekday}`;
  }

  function meta(stop: TripDraftStop): string {
    return [stop.place.categoryName, stop.place.regionName, stop.place.address]
      .filter((part): part is string => typeof part === 'string' && part.length > 0)
      .join(' · ');
  }

  const title = loading
    ? t('draftPreview.loadingTitle')
    : unavailable
      ? t('draftPreview.unavailableTitle')
      : error
        ? t('draftPreview.failedTitle')
        : empty
          ? t('draftPreview.emptyTitle')
          : t('draftPreview.title');

  return (
    <>
      <div
        className={styles.screen}
        aria-busy={loading}
        aria-live="polite"
        aria-atomic={loading || Boolean(error) || empty}
      >
        <div className={wizard.head}>
          <h1 className={wizard.title} id="wizard-heading">
            {title}
          </h1>
          <p className={wizard.lead}>
            {loading
              ? t('draftPreview.loadingBody')
              : unavailable
                ? t('draftPreview.unavailableBody')
                : error
                  ? t('draftPreview.failedBody')
                  : empty
                    ? t('draftPreview.emptyBody')
                    : t('draftPreview.lead')}
          </p>
        </div>

        {ready && preview ? (
          <>
            <div className={styles.basis}>
              <p>{t('draftPreview.basis.dateRange')}</p>
              {preview.basis.includes('OPENING_HOURS_VERIFIED') ? (
                <p>{t('draftPreview.basis.openingHours')}</p>
              ) : (
                <p>{t('draftPreview.basis.hoursUnknown')}</p>
              )}
            </div>

            {preview.reasons.includes('ALL_DATES_FULL') ||
            preview.reasons.includes('POOL_TRUNCATED') ? (
              <p className={styles.notice}>{t('draftPreview.partial')}</p>
            ) : null}

            {createFailed ? (
              <p className={styles.notice} role="alert">
                {t('wizard.createFailed')}
              </p>
            ) : null}

            {preview.days.map((day, dayIndex) => {
              const headingId = `draft-preview-day-${day.date}`;
              return (
                <section
                  aria-labelledby={headingId}
                  className={styles.day}
                  key={day.date}
                >
                  <h2 className={styles.dayHead} id={headingId}>
                    <span className={styles.dayName}>
                      {t('manual.day', { n: dayIndex + 1 })}
                    </span>
                    <span className={styles.dayDate}>{dayLabel(day.date)}</span>
                  </h2>

                  {day.stops.length > 0 ? (
                    <ol className={styles.stops}>
                      {day.stops.map((stop) => {
                        const key = recommendedStopKey(stop);
                        const selected = picked.has(key);
                        return (
                          <li className={styles.stop} key={key}>
                            <span
                              aria-hidden="true"
                              className={styles.dot}
                              data-picked={selected}
                            >
                              {stop.position + 1}
                            </span>
                            <div className={styles.card} data-picked={selected}>
                              <span className={styles.text}>
                                <span className={styles.name}>{stop.place.name}</span>
                                {meta(stop) ? (
                                  <span className={styles.meta}>{meta(stop)}</span>
                                ) : null}
                                {stop.place.sourceAttribution ? (
                                  <span className={styles.source}>
                                    <DataAttribution
                                      compact
                                      provenance={stop.place.sourceAttribution}
                                    />
                                  </span>
                                ) : null}
                              </span>
                              <span className={styles.hours}>
                                {t(`draftPreview.hours.${stop.hoursState}`)}
                              </span>
                              <button
                                type="button"
                                className={styles.pick}
                                aria-pressed={selected}
                                aria-label={t('draftPreview.pickNamed', {
                                  place: stop.place.name,
                                })}
                                onClick={() => {
                                  onTogglePick(key);
                                }}
                              >
                                {selected ? (
                                  <IconPinVisitFilled size={24} />
                                ) : (
                                  <IconPinVisit size={24} />
                                )}
                              </button>
                            </div>
                          </li>
                        );
                      })}
                    </ol>
                  ) : (
                    <p className={styles.noStops}>{t('draftPreview.dayEmpty')}</p>
                  )}
                </section>
              );
            })}
          </>
        ) : null}
      </div>

      {loading ? (
        <BottomCta fixed disabled label={t('draftPreview.loadingCta')} />
      ) : ready ? (
        <BottomCta
          fixed
          label={isSubmitting ? t('wizard.creating') : t('draftPreview.start')}
          disabled={isSubmitting}
          onClick={onSubmit}
          secondary={
            <button
              type="button"
              className={styles.secondary}
              disabled={isSubmitting}
              onClick={onEdit}
            >
              {t('draftPreview.edit')}
            </button>
          }
        />
      ) : unavailable ? (
        <BottomCta
          fixed
          label={t('draftPreview.retry')}
          onClick={onRetry}
          secondary={
            <button type="button" className={styles.secondary} onClick={onEdit}>
              {t('draftPreview.back')}
            </button>
          }
        />
      ) : empty ? (
        <BottomCta
          fixed
          label={t('draftPreview.changeDates')}
          onClick={onChangeDates}
          secondary={
            <button type="button" className={styles.secondary} onClick={onEdit}>
              {t('draftPreview.back')}
            </button>
          }
        />
      ) : (
        <BottomCta fixed label={t('draftPreview.back')} onClick={onEdit} />
      )}
    </>
  );
}
