import { useEffect, useRef, useState } from 'react';
import { useI18n } from '../../i18n/I18nProvider.js';
import type { MessageKey } from '../../i18n/messages.js';
import { usePlaceCrowdForecast } from '../../shared/api/index.js';
import {
  CrowdForecastQueryState,
  CrowdForecastReading,
} from '../../shared/crowd/CrowdForecastReading.js';
import { crowdPointForDate } from '../../shared/crowd/forecast.js';
import {
  BottomCta,
  DataAttribution,
  IconPinVisit,
  IconPinVisitFilled,
} from '../../shared/ui/index.js';
import wizard from './TripWizardScreen.module.css';
import styles from './ConfirmStopsStep.module.css';
import { stopsOn, tripDays, type DraftStop, type WizardDraft } from './wizard.js';

// Figma: S02-5C 최종 확인 `438:3259` (FR-TRC-05, FE-103).
//
// The last step of the manual branch: read back what was entered and pick the
// places that must stay. FIGMA_HANDOFF:154 calls this "구조화 결과 최종 확인",
// which reads like a read-only summary — it is not. The Pick toggle on every
// card is the reason this screen exists, and describing it as a summary is how
// it went uncounted as work.
//
// The pick is a MUST_VISIT constraint on the stop's seedItem, NOT the dateless
// `draft.mustVisit` that #180 settled onto the candidate. The distinction is
// the date: these stops have one, so they are seed items, and a constraint row
// has a `trip_item_id` to point at. It all goes in the one createTrip call, so
// #185's partial-failure question does not arise (invariant 5).
//
// Each visible stop lazily reads its own exact date. This screen can hold up to
// 100 draft stops, above the batch endpoint's 50-place cap, so eager fan-out or
// one oversized batch are both wrong. IntersectionObserver keeps off-screen
// cards quiet; the request starts when a card approaches the viewport.
//
// The hint copy IS contract-backed and ships. "고른 곳은 다른 장소로 바꾸자고
// 하지 않아요 / 대신 덜 붐비는 날짜를 알려드려요" is what MUST_VISIT does:
// apps/ai's filters.py keeps the place ("MUST_VISIT always passes here because
// a temporal move keeps the place") while a temporal move stays allowed.

export interface ConfirmStopsStepProps {
  draft: WizardDraft;
  onTogglePick: (key: string) => void;
  /** Create the trip with these stops and picks. */
  onSubmit: () => void;
  /** Back to the manual entry step to change what was typed. */
  onEdit: () => void;
  isSubmitting: boolean;
}

function LazyStopCrowd({ placeId, date }: { placeId: string; date: string }) {
  const anchor = useRef<HTMLSpanElement>(null);
  const [visible, setVisible] = useState(false);

  useEffect(() => {
    const node = anchor.current;
    if (!node) return;
    if (typeof IntersectionObserver === 'undefined') {
      // Progressive fallback for older browsers. Supported browsers take the
      // lazy path; an unsupported one still gets truthful data.
      setVisible(true);
      return;
    }
    const observer = new IntersectionObserver(
      (entries) => {
        if (!entries.some((entry) => entry.isIntersecting)) return;
        setVisible(true);
        observer.disconnect();
      },
      { rootMargin: '160px 0px' },
    );
    observer.observe(node.closest('[data-crowd-stop]') ?? node);
    return () => {
      observer.disconnect();
    };
  }, []);

  const forecast = usePlaceCrowdForecast(placeId, date, date, visible);
  return (
    <span ref={anchor}>
      <CrowdForecastQueryState
        failed={forecast.isError}
        loading={forecast.isFetching}
        series={forecast.data}
      />
      <CrowdForecastReading point={crowdPointForDate(forecast.data, date)} />
    </span>
  );
}

export function ConfirmStopsStep({
  draft,
  onTogglePick,
  onSubmit,
  onEdit,
  isSubmitting,
}: ConfirmStopsStepProps) {
  const { locale, t } = useI18n();

  const days = tripDays(draft).filter((date) => stopsOn(draft, date).length > 0);

  /** `10.4/일` — the same header the manual step draws. */
  function dayLabel(date: string): string {
    const parsed = new Date(`${date}T00:00:00Z`);
    const weekday = new Intl.DateTimeFormat(locale, {
      weekday: 'short',
      timeZone: 'UTC',
    }).format(parsed);
    return `${String(parsed.getUTCMonth() + 1)}.${String(parsed.getUTCDate())}/${weekday}`;
  }

  function meta(stop: DraftStop): string {
    // categoryName · regionName, as the frame's `관광지 · 종로구`. Never
    // categoryCode: BA-022 calls the code opaque and provider-derived, and a
    // null name means "show no category" rather than "unknown".
    return [stop.place.categoryName, stop.place.regionName]
      .filter((part): part is string => typeof part === 'string' && part.length > 0)
      .join(' · ');
  }

  return (
    <>
      <div className={styles.screen}>
        <div className={wizard.head}>
          <h1 className={wizard.title} id="wizard-heading">
            {t('confirm.title')}
          </h1>
          <p className={wizard.lead}>{t('confirm.lead')}</p>
        </div>

        {/* The promise the picks make. Two lines in the frame, and both are
          statements about server behaviour rather than reassurance. */}
        <p className={styles.hint}>
          {t('confirm.hint1')}
          <br />
          {t('confirm.hint2')}
        </p>

        {days.map((date) => {
          const headingId = `confirm-day-${date}`;
          return (
            <section aria-labelledby={headingId} className={styles.day} key={date}>
              <h2 className={styles.dayHead} id={headingId}>
                <span className={styles.dayName}>
                  {t('manual.day', { n: tripDays(draft).indexOf(date) + 1 })}
                </span>
                <span className={styles.dayDate}>{dayLabel(date)}</span>
              </h2>

              <ol className={styles.stops}>
                {stopsOn(draft, date).map((stop, index) => (
                  <li className={styles.stop} key={stop.key}>
                    {/* Numbered dot on the spine. Decorative — a screen reader
                      counts the list items itself. The frame colours a picked
                      stop's dot differently, which data-picked carries. */}
                    <span
                      aria-hidden="true"
                      className={styles.dot}
                      data-picked={stop.mustVisit}
                    >
                      {index + 1}
                    </span>
                    <div
                      className={styles.card}
                      data-crowd-stop
                      data-picked={stop.mustVisit}
                    >
                      <span className={styles.text}>
                        <span className={styles.row1}>
                          <span className={styles.name}>{stop.place.name}</span>
                          <span className={styles.daypart}>
                            {t(`manual.daypart.${stop.daypart}` as MessageKey)}
                          </span>
                        </span>
                        {meta(stop) ? (
                          <span className={styles.meta}>{meta(stop)}</span>
                        ) : null}
                        {/* CMP-ATT-001: the place's own credit, verbatim. The
                          forecast below carries a second one for its own
                          dataset; neither stands in for the other
                          (SOURCE_CATALOG, provenance primitives). */}
                        {stop.place.sourceAttribution ? (
                          <DataAttribution
                            compact
                            provenance={stop.place.sourceAttribution}
                          />
                        ) : null}
                        <LazyStopCrowd date={date} placeId={stop.place.id} />
                      </span>
                      {/* A toggle, so it announces its own state rather than
                        relying on the pin glyph — which is colour-and-shape
                        only information otherwise. The name carries the place
                        because every row's control is otherwise identical. */}
                      <button
                        type="button"
                        className={styles.pick}
                        aria-pressed={stop.mustVisit}
                        aria-label={t('confirm.pickNamed', { place: stop.place.name })}
                        onClick={() => {
                          onTogglePick(stop.key);
                        }}
                      >
                        {stop.mustVisit ? (
                          <IconPinVisitFilled size={16} />
                        ) : (
                          <IconPinVisit size={16} />
                        )}
                      </button>
                    </div>
                  </li>
                ))}
              </ol>
            </section>
          );
        })}
      </div>

      <BottomCta
        fixed
        label={t('confirm.next')}
        disabled={isSubmitting}
        onClick={onSubmit}
        secondary={
          <button
            type="button"
            className={styles.edit}
            disabled={isSubmitting}
            onClick={onEdit}
          >
            {t('confirm.edit')}
          </button>
        }
      />
    </>
  );
}
