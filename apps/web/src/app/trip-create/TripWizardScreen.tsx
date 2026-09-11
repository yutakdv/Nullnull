import { useRef, useState } from 'react';
import { useNavigate } from 'react-router';
import type { components } from '@nullnull/api-client';
import { useI18n } from '../../i18n/I18nProvider.js';
import type { MessageKey } from '../../i18n/messages.js';
import { BottomCta, Chip } from '../../shared/ui/index.js';
import { useCreateTrip } from '../../shared/api/index.js';
import {
  EMPTY_DRAFT,
  INTEREST_GROUPS,
  canAddInterest,
  dateError,
  selectDay,
  toCreateRequest,
  toggleInterest,
  type WizardDraft,
} from './wizard.js';
import styles from './TripWizardScreen.module.css';

type PlanningLevel = components['schemas']['PlanningLevel'];

// Figma: S02-1 dates `438:3012`, S02-2 interests `438:3108`,
// S02-3 planning level `438:3134`.
//
// FR-TRC-01/02/03. Steps 1-3 are a local draft — FIGMA_HANDOFF marks them so —
// and the only server call is createTrip at the end, which carries an
// Idempotency-Key because a repeated submit must not create a second trip
// (invariant 6).
//
// The draft lives in component state rather than the URL: it is edit buffer,
// which .claude/rules/frontend.md keeps feature-local.

const LEVELS: PlanningLevel[] = ['NOTHING', 'MUST_VISIT_ONLY', 'MOSTLY_PLANNED'];
const DOW = ['일', '월', '화', '수', '목', '금', '토'];

/** ISO date for a day in the given month grid, or null for a padding cell. */
function isoDate(year: number, month: number, day: number): string {
  return `${String(year)}-${String(month + 1).padStart(2, '0')}-${String(day).padStart(2, '0')}`;
}

export function TripWizardScreen() {
  const { locale, t } = useI18n();
  const navigate = useNavigate();
  const [step, setStep] = useState(1);
  const [draft, setDraft] = useState<WizardDraft>(EMPTY_DRAFT);
  const [month, setMonth] = useState(() => new Date());
  const createTrip = useCreateTrip();
  // The key for the request in flight, held across retries of THAT request.
  // Keyed by the request body so it rotates exactly when the draft changes:
  // pressing 만들기 again after a failure replays the first attempt, while
  // editing the dates or the planning level makes it a new command. Minting
  // one per press would let a retry after a lost response create a second
  // trip (invariant 6).
  const submitKey = useRef<{ for: string; key: string } | null>(null);

  const year = month.getFullYear();
  const monthIndex = month.getMonth();
  const firstWeekday = new Date(year, monthIndex, 1).getDay();
  const daysInMonth = new Date(year, monthIndex + 1, 0).getDate();
  const dateProblem = dateError(draft);

  function shiftMonth(by: number) {
    setMonth(new Date(year, monthIndex + by, 1));
  }

  function submit() {
    // The browser's zone: the trip is planned where the user is, and the
    // contract defaults to Asia/Seoul only when nothing is supplied.
    const timezone = Intl.DateTimeFormat().resolvedOptions().timeZone;
    const request = toCreateRequest(draft, timezone);
    if (!request) return;
    const fingerprint = JSON.stringify(request);
    if (submitKey.current?.for !== fingerprint) {
      submitKey.current = { for: fingerprint, key: crypto.randomUUID() };
    }
    createTrip.mutate(
      { request, idempotencyKey: submitKey.current.key },
      {
        onSuccess: (trip) => {
          submitKey.current = null;
          void navigate(`/trip/${trip.id}`, { replace: true });
        },
      },
    );
  }

  return (
    <section className={styles.screen} aria-labelledby="wizard-heading">
      <p className={styles.step}>
        {t('wizard.step')} {step}
      </p>

      {step === 1 ? (
        <>
          <div className={styles.head}>
            <h1 className={styles.title} id="wizard-heading">
              {t('wizard.dates.title')}
            </h1>
            <p className={styles.lead}>{t('wizard.dates.lead')}</p>
          </div>

          <div className={styles.month}>
            <button
              type="button"
              className={styles.monthNav}
              aria-label={t('wizard.dates.prevMonth')}
              onClick={() => {
                shiftMonth(-1);
              }}
            >
              ‹
            </button>
            <span className={styles.monthLabel}>
              {new Intl.DateTimeFormat(locale, { year: 'numeric', month: 'long' }).format(
                month,
              )}
            </span>
            <button
              type="button"
              className={styles.monthNav}
              aria-label={t('wizard.dates.nextMonth')}
              onClick={() => {
                shiftMonth(1);
              }}
            >
              ›
            </button>
          </div>

          <div className={styles.grid} role="group" aria-labelledby="wizard-heading">
            {DOW.map((day) => (
              <span className={styles.dow} key={day}>
                {day}
              </span>
            ))}
            {Array.from({ length: firstWeekday }, (_, i) => (
              <span className={styles.empty} key={`pad-${String(i)}`} />
            ))}
            {Array.from({ length: daysInMonth }, (_, i) => {
              const date = isoDate(year, monthIndex, i + 1);
              const isStart = draft.startDate === date;
              const isEnd = draft.endDate === date;
              const between =
                draft.startDate !== null &&
                draft.endDate !== null &&
                date > draft.startDate &&
                date < draft.endDate;
              return (
                <button
                  type="button"
                  key={date}
                  className={[
                    styles.day,
                    isStart || isEnd ? styles.dayEdge : '',
                    between ? styles.dayBetween : '',
                  ]
                    .filter(Boolean)
                    .join(' ')}
                  aria-pressed={isStart || isEnd || between}
                  onClick={() => {
                    setDraft((current) => selectDay(current, date));
                  }}
                >
                  {i + 1}
                </button>
              );
            })}
          </div>

          {dateProblem === 'tooLong' ? (
            <p className={styles.hint} role="alert">
              {t('wizard.dates.tooLong')}
            </p>
          ) : null}

          <BottomCta
            label={
              dateProblem === null && draft.startDate && draft.endDate
                ? `${draft.startDate} – ${draft.endDate}`
                : t('wizard.dates.pick')
            }
            disabled={dateProblem !== null}
            onClick={() => {
              setStep(2);
            }}
          />
        </>
      ) : null}

      {step === 2 ? (
        <>
          <div className={styles.head}>
            <h1 className={styles.title} id="wizard-heading">
              {t('wizard.interests.title1')}
              <br />
              {t('wizard.interests.title2')}
            </h1>
            <p className={styles.lead}>{t('wizard.interests.lead')}</p>
          </div>

          {INTEREST_GROUPS.map((group) => (
            <div className={styles.group} key={group.id}>
              <span className={styles.groupLabel} id={`group-${group.id}`}>
                {t(`wizard.interests.${group.id}` as MessageKey)}
              </span>
              <ul className={styles.chips} aria-labelledby={`group-${group.id}`}>
                {group.codes.map((code) => {
                  const selected = draft.interests.includes(code);
                  return (
                    <li key={code}>
                      <Chip
                        label={t(`interest.${code}` as MessageKey)}
                        selected={selected}
                        disabled={!selected && !canAddInterest(draft)}
                        onClick={() => {
                          setDraft((current) => toggleInterest(current, code));
                        }}
                      />
                    </li>
                  );
                })}
              </ul>
            </div>
          ))}

          <BottomCta
            label={t('wizard.next')}
            onClick={() => {
              setStep(3);
            }}
            secondary={
              <button
                type="button"
                className={styles.later}
                onClick={() => {
                  // Zero interests is valid; the contract allows minItems 0.
                  setDraft((current) => ({ ...current, interests: [] }));
                  setStep(3);
                }}
              >
                {t('wizard.interests.later')}
              </button>
            }
          />
        </>
      ) : null}

      {step === 3 ? (
        <>
          <div className={styles.head}>
            <h1 className={styles.title} id="wizard-heading">
              {t('wizard.planning.title1')}
              <br />
              {t('wizard.planning.title2')}
            </h1>
            <p className={styles.lead}>{t('wizard.planning.lead')}</p>
          </div>

          <ul className={styles.options}>
            {LEVELS.map((level) => {
              const selected = draft.planningLevel === level;
              return (
                <li key={level}>
                  <button
                    type="button"
                    className={`${styles.option} ${selected ? styles.optionSelected : ''}`}
                    aria-pressed={selected}
                    onClick={() => {
                      setDraft((current) => ({ ...current, planningLevel: level }));
                    }}
                  >
                    <span className={styles.optionText}>
                      <span className={styles.optionTitle}>
                        {t(`wizard.planning.${level}.title` as MessageKey)}
                      </span>
                      <span className={styles.optionBody}>
                        {t(`wizard.planning.${level}.body` as MessageKey)}
                      </span>
                    </span>
                    <span aria-hidden="true">→</span>
                  </button>
                </li>
              );
            })}
          </ul>

          {createTrip.isError ? (
            <p className={styles.hint} role="alert">
              {t('wizard.createFailed')}
            </p>
          ) : null}

          <BottomCta
            label={createTrip.isPending ? t('wizard.creating') : t('wizard.next')}
            // Blocked while in flight: a second submit would be a second trip,
            // which the Idempotency-Key guards against but need not be tested by
            // the user (.claude/rules/frontend.md on duplicate submits).
            disabled={draft.planningLevel === null || createTrip.isPending}
            onClick={submit}
          />
        </>
      ) : null}
    </section>
  );
}
