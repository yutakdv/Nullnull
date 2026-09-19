import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router';
import type { components } from '@nullnull/api-client';
import { useI18n } from '../../i18n/I18nProvider.js';
import type { MessageKey } from '../../i18n/messages.js';
import { BottomCta, Chip, NavBar } from '../../shared/ui/index.js';
import { useCreateTrip, useUpdatePreferences } from '../../shared/api/index.js';
import {
  EMPTY_DRAFT,
  INTEREST_GROUPS,
  addMustVisit,
  addStop,
  canAddInterest,
  dateError,
  nextAfterPlanning,
  removeMustVisit,
  removeStop,
  selectDay,
  setStopDaypart,
  toCreateRequest,
  toggleInterest,
  toggleStopMustVisit,
  type WizardDraft,
} from './wizard.js';
import { clearSnapshot, readSnapshot, writeSnapshot } from './wizard-storage.js';
import { ConfirmStopsStep } from './ConfirmStopsStep.js';
import { InputMethodStep } from './InputMethodStep.js';
import { ManualStopsStep } from './ManualStopsStep.js';
import { MustVisitStep } from './MustVisitScreen.js';
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
/**
 * The weekday headers, in the active locale.
 *
 * These were the literal ['일','월',…], so an English user read a Korean
 * calendar — the one place in the wizard that never went through i18n. Derived
 * from Intl rather than added as seven message keys: the names ARE the locale's
 * data, and a hand-kept copy is a second source that can drift from it.
 *
 * 2021-01-03 is a Sunday, which is the column this grid starts on.
 */
function weekdayNames(locale: string): string[] {
  const format = new Intl.DateTimeFormat(locale, { weekday: 'short' });
  return Array.from({ length: 7 }, (_, index) =>
    format.format(new Date(Date.UTC(2021, 0, 3 + index))),
  );
}

/** ISO date for a day in the given month grid, or null for a padding cell. */
function isoDate(year: number, month: number, day: number): string {
  return `${String(year)}-${String(month + 1).padStart(2, '0')}-${String(day).padStart(2, '0')}`;
}

export function TripWizardScreen() {
  const { locale, t } = useI18n();
  const navigate = useNavigate();
  // Restored from sessionStorage on the first render, not in an effect
  // (FR-TRC-12). An effect would paint step 1 with empty dates first and then
  // replace it, which is a visible flash of the exact state the recovery exists
  // to prevent — and on the calendar step it would also reset the month.
  // `useState`'s initializer runs once, before the first paint.
  const restored = useRef(readSnapshot()).current;
  const [step, setStep] = useState(restored?.step ?? 1);
  const [draft, setDraft] = useState<WizardDraft>(restored?.draft ?? EMPTY_DRAFT);
  // The step the heading was last moved to, so focus follows a CHANGE rather
  // than a render.
  //
  // The first render must not steal focus — a page that grabs it on load moves
  // the caret out from under someone who was already typing, and a screen
  // reader does not need to be told where it just landed.
  //
  // Holding the step value and not a "have we mounted" boolean, because
  // StrictMode runs effects twice in development: a boolean flips on the first
  // pass and the second pass then reads it as a real step change and takes
  // focus. Measured — the heading was focused on mount with the boolean
  // version. Comparing the step survives any number of extra runs.
  // Seeded with the RESTORED step, not 1: after a reload the heading must not
  // be focused on the first paint, and `focusedStep` is what distinguishes a
  // step CHANGE from a render. Hardcoding 1 here would make a recovery onto
  // step 3 look like a move from 1 to 3 and steal focus on load — the thing the
  // comment below says must not happen.
  const focusedStep = useRef(restored?.step ?? 1);
  const [month, setMonth] = useState(() => new Date());

  // One effect rather than a write beside each of the fifteen setStep/setDraft
  // call sites: a rule enforced in one place cannot be forgotten at the
  // sixteenth, and this runs after the state it saves is the state on screen.
  // The pasted itinerary is not here to exclude — it never enters this
  // component (ImportPasteScreen holds it, on its own route), which is why the
  // canary test checks every Storage rather than trusting that shape.
  useEffect(() => {
    writeSnapshot({ step, draft });
  }, [step, draft]);
  const createTrip = useCreateTrip();
  // Points the owner's 내 여행 tab at whatever this wizard creates (BA-011).
  const setActiveTrip = useUpdatePreferences();
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

  // `using` is the draft to send, for a caller that has just changed it:
  // setDraft is queued, so `draft` here is still the previous render's value
  // and a caller that cleared something would send it anyway. 건너뛰기 on the
  // manual step is exactly that case — it must not carry the stops it just
  // dropped — and passing the draft explicitly says so at the call site
  // instead of depending on when React applies the update.
  //
  // REQUIRED, not defaulted, and the callers below pass `draft` by hand. A
  // default made this silently wrong: the steps hand `onSubmit` straight to a
  // DOM button, so React calls it with the click EVENT, which filled `using`
  // and made dateError read a MouseEvent — toCreateRequest returned null and
  // the press did nothing at all, with no error shown. The prop is typed
  // `() => void`, which happily accepts a function that ignores its argument,
  // so TypeScript could not see it.
  function submit(using: WizardDraft) {
    // The browser's zone: the trip is planned where the user is, and the
    // contract defaults to Asia/Seoul only when nothing is supplied.
    const timezone = Intl.DateTimeFormat().resolvedOptions().timeZone;
    const request = toCreateRequest(using, timezone);
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
          // The draft became a trip, so it stops being a draft. Without this,
          // starting a second trip would reopen the finished one and the new
          // trip would inherit the first one's dates.
          clearSnapshot();
          // The trip just created becomes the owner's active one, which is what
          // the 내 여행 tab resolves to (AppShell). BA-011 stores the pointer
          // but never sets it on its own — `owners.active_trip_id` is only ever
          // written by this PATCH and cleared by the trip's own ON DELETE SET
          // NULL — so without this call a traveller can own four trips and the
          // tab still has nowhere to go.
          //
          // Best effort, and deliberately not awaited: the trip EXISTS, and
          // navigation must not wait on a preference write or fail because of
          // one. A rejection leaves the pointer where it was and the tab falls
          // back, which is the same state as before this call.
          setActiveTrip.mutate({ activeTripId: trip.id });
          void navigate(`/trip/${trip.id}`, { replace: true });
        },
      },
    );
  }

  // Going back a step, and out of the flow from the first one.
  //
  // The steps are component state rather than routes, so browser Back leaves
  // /start entirely and takes the draft with it — reproduced in a browser:
  // picking 9/15-9/18, continuing, then pressing Back landed on the previously
  // visited page, and returning to /start showed step 1 with no dates. There
  // was no in-screen way back either, so a mistyped date range could only be
  // fixed by redoing the whole wizard. FIGMA_HANDOFF's rule for this flow is
  // that moving back preserves what was entered, and `wizard.back` was already
  // translated in both locales for a control that had never been rendered.
  function goBack() {
    if (step > 1) {
      setStep(step - 1);
      return;
    }
    void navigate('/feed');
  }

  // Moves focus to the new step's heading (BA-070-T5, FE-102).
  //
  // The steps are component state rather than routes, so nothing resets focus
  // when one replaces another: the whole panel is torn down and rebuilt while
  // focus stays on whatever the old step left it on — and since the control
  // that was pressed is gone, the browser drops it to `<body>`. Measured in a
  // browser before this existed: steps 1, 2 and 3 all reported
  // `document.activeElement === BODY`. Two costs, and neither is visible in a
  // screenshot:
  //
  //   - a screen reader says nothing. The DOM was replaced, focus did not
  //     move, and there is no live region — so the user is told neither that
  //     the step changed nor what the new one asks.
  //   - a keyboard user starts from the top of the document on every step. Six
  //     steps means six walks back down.
  //
  // The HEADING and not the first control. The first focusable in step 2 is
  // the back button — an icon button whose text content is empty — so focusing
  // "the first thing" would announce "Previous step" to someone who just moved
  // forward. The heading says which step this is, which is what a person needs
  // before they can answer it. `tabindex="-1"` is what makes an `h1`
  // programmatically focusable without adding it to the Tab order.
  //
  // Found by id rather than held in a ref: each step renders its own `<h1>`, so
  // a ref would point at the previous step's element on the render where it
  // matters, and two of the six steps draw their heading from a child
  // component this file cannot ref into.
  //
  // `queueMicrotask` for the reason TripScreen.tsx:199-203 gives — the new DOM
  // has to exist before it can take focus. The direction differs (that one
  // returns focus to a trigger it kept a ref to; this one moves it to an
  // element that did not exist a moment ago), so only the timing is borrowed.
  useEffect(() => {
    if (focusedStep.current === step) return;
    focusedStep.current = step;
    queueMicrotask(() => {
      const heading = document.getElementById('wizard-heading');
      if (!heading) return;
      // Set here rather than in the JSX so the attribute exists only on the
      // heading that is actually being focused, and so a step whose heading
      // lives in a child component gets it too.
      heading.setAttribute('tabindex', '-1');
      heading.focus();
    });
  }, [step]);

  return (
    <section className={styles.screen} aria-labelledby="wizard-heading">
      <NavBar backLabel={t('wizard.back')} onBack={goBack} />
      {/* The confirm step names itself 마지막 rather than STEP 6: the frame
          says so, and a number implies a seventh step that does not exist. */}
      <p className={styles.step}>
        {step === 6 ? t('confirm.step') : `${t('wizard.step')} ${String(step)}`}
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
            {weekdayNames(locale).map((day) => (
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
            // The answer decides what follows: MUST_VISIT_ONLY goes to step 4
            // and the other two create the trip. All three used to call
            // submit(), so "꼭 가고 싶은 곳만 정했어요" made the same trip as
            // "아직 하나도 없어요" and never asked which places (#185).
            onClick={() => {
              // Step 4 is whichever branch step 3 was answered with: the
              // must-visit picker (S02-4B) or the input-method choice
              // (S02-4C `400:1201`). Only NOTHING creates the trip from here,
              // because it is the one answer that says there is nothing more
              // to collect.
              const next = nextAfterPlanning(draft);
              if (next === 'must-visit' || next === 'method') {
                setStep(4);
                return;
              }
              submit(draft);
            }}
            secondary={
              // The paste path (FE-104, `401:1221`) stays reachable from here
              // as well: MOSTLY_PLANNED routes to it above, and this keeps it
              // available to someone who answered differently but arrived with
              // an itinerary in hand. The `400:1201` branch screen that would
              // hold both input methods is still open in FCR-018.
              <button
                type="button"
                className={styles.later}
                onClick={() => {
                  void navigate('/start/import');
                }}
              >
                {t('import.start')}
              </button>
            }
          />
        </>
      ) : null}

      {/* S02-4B `438:3158`, reached only from MUST_VISIT_ONLY. The picks live in
          the draft, so stepping back to 3 and forward again keeps them — the
          same promise FIGMA_HANDOFF makes for steps 1-3. */}
      {/* S02-4C `400:1201`: the branch 거의 다 세우고 왔어요 takes. Held as a
          step rather than a route so the dates and interests collected above
          survive the choice — sending the traveller to `/start/import` would
          hand them a screen that starts from EMPTY_DRAFT. */}
      {step === 4 && nextAfterPlanning(draft) === 'method' ? (
        <InputMethodStep
          onManual={() => {
            setStep(5);
          }}
          onPaste={() => {
            void navigate('/start/import');
          }}
        />
      ) : null}

      {/* S02-4C-C `438:3199`: the manual half of the input-method branch.
          Before this existed, InputMethodStep's 직접 입력 called setStep(5)
          and nothing rendered there, so choosing it landed on a blank
          screen — a reachable dead end of exactly the kind #185 is about. */}
      {step === 5 ? (
        <ManualStopsStep
          draft={draft}
          onAddStop={(date, place) => {
            // The key is minted here rather than inside addStop so the rule
            // stays a pure function: same draft in, same draft out.
            const key = crypto.randomUUID();
            setDraft((current) => addStop(current, date, place, key));
          }}
          onRemoveStop={(key) => {
            setDraft((current) => removeStop(current, key));
          }}
          onSetDaypart={(key, daypart) => {
            setDraft((current) => setStopDaypart(current, key, daypart));
          }}
          onSubmit={() => {
            // On to the confirm step (S02-5C) rather than straight to the
            // server: that screen reads the itinerary back and is where the
            // must-visit picks are made. With nothing entered there is nothing
            // to confirm and no place to pick, so that case creates the trip
            // from here instead of showing an empty page.
            if (draft.stops.length === 0) {
              submit(draft);
              return;
            }
            setStep(6);
          }}
          onSkip={() => {
            // An answer, not a cancel: the traveller says there is nothing to
            // carry over, so the stops entered so far must NOT be sent. The
            // cleared draft is passed to submit rather than only stored,
            // because setDraft is queued and submit would otherwise read the
            // stops it is meant to drop.
            const cleared = { ...draft, stops: [] };
            setDraft(cleared);
            submit(cleared);
          }}
          isSubmitting={createTrip.isPending}
        />
      ) : null}

      {/* S02-5C `438:3259`: read the itinerary back and pick what must stay.
          FIGMA_HANDOFF:154 describes this as a summary, which is how its Pick
          toggle went uncounted — it is an input screen. */}
      {step === 6 ? (
        <ConfirmStopsStep
          draft={draft}
          onTogglePick={(key) => {
            setDraft((current) => toggleStopMustVisit(current, key));
          }}
          onSubmit={() => {
            submit(draft);
          }}
          onEdit={() => {
            // 다시 고칠래요 goes back to the entry step with everything intact,
            // picks included — the same promise the wizard makes at every other
            // step. It is not a cancel and drops nothing.
            setStep(5);
          }}
          isSubmitting={createTrip.isPending}
        />
      ) : null}

      {step === 4 && nextAfterPlanning(draft) === 'must-visit' ? (
        <MustVisitStep
          endDate={draft.endDate}
          picked={draft.mustVisit}
          onAdd={(place) => {
            setDraft((current) => addMustVisit(current, place));
          }}
          onRemove={(placeId) => {
            setDraft((current) => removeMustVisit(current, placeId));
          }}
          onSubmit={() => {
            submit(draft);
          }}
          onSkip={() => {
            // A real answer, not a cancel: the traveller says there are no
            // must-visit places, so the trip is created without any. Clearing
            // first keeps that honest — pressing 건너뛰기 after picking some
            // must not quietly carry them.
            //
            // The cleared draft is passed rather than only stored, for the
            // reason submit() states. It makes no difference to the request
            // today, because toCreateRequest drops mustVisit either way, and
            // it is written this way so it does not start mattering silently
            // when #180's wiring gives the picks somewhere to go.
            const cleared = { ...draft, mustVisit: [] };
            setDraft(cleared);
            submit(cleared);
          }}
          isSubmitting={createTrip.isPending}
          startDate={draft.startDate}
        />
      ) : null}
    </section>
  );
}
