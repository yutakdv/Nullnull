import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router';
import type { components } from '@nullnull/api-client';
import { useI18n } from '../../i18n/I18nProvider.js';
import type { MessageKey } from '../../i18n/messages.js';
import { BottomCta, Chip, IconArrowRight, NavBar } from '../../shared/ui/index.js';
import {
  useAddTripCandidate,
  useCreateTrip,
  usePreviewTripDraft,
  useUpdatePreferences,
} from '../../shared/api/index.js';
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
import {
  ATTEMPT_CHANGED,
  claimCreate,
  claimSave,
  isCreating,
  isSaving,
  readAttempt,
  releaseCreate,
  releaseSave,
  writeAttempt,
  type PendingPick,
} from './wizard-attempt.js';
import { ConfirmStopsStep } from './ConfirmStopsStep.js';
import { InputMethodStep } from './InputMethodStep.js';
import { ManualStopsStep } from './ManualStopsStep.js';
import { MustVisitStep } from './MustVisitScreen.js';
import { RecommendedDraftStep } from './RecommendedDraftStep.js';
import { recommendedSeedItems } from './recommended-draft.js';
import styles from './TripWizardScreen.module.css';

type PlaceSummary = components['schemas']['PlaceSummary'];
type PlanningLevel = components['schemas']['PlanningLevel'];
type SeedTripItem = components['schemas']['SeedTripItem'];
type TripDetail = components['schemas']['TripDetail'];
type TripDraftPreview = components['schemas']['TripDraftPreview'];

// Figma: S02-1 dates `438:3012`, S02-2 interests `438:3108`,
// S02-3 planning level `438:3134`.
//
// FR-TRC-01/02/03. Steps 1-3 are a local draft — FIGMA_HANDOFF marks them so.
// NOTHING requests a read-only recommendation before createTrip; every branch
// creates only on explicit confirmation. createTrip carries an Idempotency-Key
// because a repeated submit must not create a second trip (invariant 6).
//
// The draft lives in component state rather than the URL: it is edit buffer,
// which .claude/rules/frontend.md keeps feature-local.

const LEVELS: PlanningLevel[] = ['NOTHING', 'MUST_VISIT_ONLY', 'MOSTLY_PLANNED'];
const PLAN_LEVEL_ICONS: Record<PlanningLevel, string> = {
  NOTHING: '/figma/plan-level-empty.svg',
  MUST_VISIT_ONLY: '/figma/plan-level-must-visit.svg',
  MOSTLY_PLANNED: '/figma/plan-level-mostly-planned.svg',
};
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
  const restoredAttempt = useRef(readAttempt()).current;
  const recovering =
    restoredAttempt?.tripId &&
    (restoredAttempt.phase === 'saving' || restoredAttempt.phase === 'failed');
  const [attempt, setAttempt] = useState(restoredAttempt);
  const [step, setStep] = useState(recovering ? 4 : (restored?.step ?? 1));
  const [draft, setDraft] = useState<WizardDraft>(
    recovering
      ? {
          ...EMPTY_DRAFT,
          planningLevel: 'MUST_VISIT_ONLY',
          mustVisit: restoredAttempt.picks.map((pick) => pick.place),
        }
      : (restored?.draft ?? EMPTY_DRAFT),
  );
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
  const focusedStep = useRef(recovering ? 4 : (restored?.step ?? 1));
  const [month, setMonth] = useState(() => new Date());

  // One effect rather than a write beside each of the fifteen setStep/setDraft
  // call sites: a rule enforced in one place cannot be forgotten at the
  // sixteenth, and this runs after the state it saves is the state on screen.
  // The pasted itinerary is not here to exclude — it never enters this
  // component (ImportPasteScreen holds it, on its own route), which is why the
  // canary test checks every Storage rather than trusting that shape.
  useEffect(() => {
    // Once this run has made its trip the draft is spent: clearSnapshot() ran
    // when createTrip answered. The step still renders at least once before
    // the trip's route commits (see `createdTrip`), and an edit there — 담기
    // after 건너뛰기, measured (FE-103-T37) — would otherwise write the draft
    // back, and the next /start would open on it.
    const phase = readAttempt()?.phase;
    if (createdTrip.current !== null || (phase && phase !== 'create-failed')) return;
    // The recommendation response is `private, no-store`, so it is never put
    // in sessionStorage. Persist step 3 while that ephemeral screen is open:
    // a refresh keeps the user's dates/interests/answer without restoring a
    // phantom step 4 that has no preview to render.
    const persistedStep = step === 4 && draft.planningLevel === 'NOTHING' ? 3 : step;
    writeSnapshot({ step: persistedStep, draft });
  }, [step, draft]);
  const createTrip = useCreateTrip();
  const previewTripDraft = usePreviewTripDraft();
  const [recommendedDraft, setRecommendedDraft] = useState<TripDraftPreview | null>(null);
  const [recommendedPicks, setRecommendedPicks] = useState<Set<string>>(() => new Set());
  // Points the owner's 내 여행 tab at whatever this wizard creates (BA-011).
  const setActiveTrip = useUpdatePreferences();
  // The must-visit picks go to the trip as candidates, after it exists (#180
  // option B, #185). No hook-level trip: the id is only known once createTrip
  // answers, so every call names it.
  const addCandidate = useAddTripCandidate(null);
  // The trip this wizard has created but not yet left for, because its picks
  // are still being saved or some could not be (#185). createTrip and the N
  // candidate writes are not one transaction (invariant 5), so this is the
  // state between them. While it is set the wizard is no longer a draft: the
  // must-visit step is what shows (see `shownStep`), it offers only to retry
  // `unsaved` or to open the trip, and there is no back control.
  const [heldTrip, setHeldTrip] = useState<{ id: string; unsaved: PendingPick[] } | null>(
    recovering
      ? {
          id: restoredAttempt.tripId as string,
          unsaved: restoredAttempt.phase === 'failed' ? restoredAttempt.pending : [],
        }
      : null,
  );
  const [savingPicks, setSavingPicks] = useState(
    Boolean(recovering && restoredAttempt.phase === 'saving'),
  );
  const [leavingTrip, setLeavingTrip] = useState(false);
  // The trip this wizard run has created, set as soon as createTrip answers.
  // submit() refuses while it is set, and the snapshot effect stops writing.
  //
  // A ref rather than `heldTrip`: state is only read back on the next render,
  // so a check on it would leave a gap between the answer and that render.
  //
  // Not only defence in depth, as this comment used to say (#185 review).
  // Once the trip is asked for, the wizard renders at least once more before
  // the trip's route commits, because React Router commits a navigation
  // inside startTransition (RouterProvider, read at 7.18). With no picks to
  // save — 건너뛰기, and every other branch — that render has the step's
  // controls enabled again, since createTrip is no longer pending. A press of
  // an exit there would mint a new key, the draft's having been dropped with
  // the answer, and this check is what refuses it (FE-103-T34, the 건너뛰기
  // case); an edit there would rewrite the cleared draft, and the snapshot
  // effect's check is what keeps it out (FE-103-T37). With picks, savePicks
  // keeps the step busy through that render instead (FE-103-T55).
  const createdTrip = useRef<string | null>(null);
  // Whether this wizard run is still mounted. submit() awaits createTrip and
  // savePicks awaits each write, and those promises outlive the component:
  // leaving /start by browser Back, which stays inside the app, unmounts the
  // wizard but not what it started.
  const mounted = useRef(true);
  useEffect(() => {
    // Set here as well as in the initialiser: StrictMode runs this cleanup
    // and the effect again on mount, and the second run must undo the first
    // cleanup.
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);

  // A browser Back/Forward remount observes the same command, even if the
  // original screen has gone. The command and failed pick keys live in tab
  // storage; this event updates another mounted instance in the same tab.
  const hadAttempt = useRef(Boolean(restoredAttempt));
  useEffect(() => {
    const sync = () => {
      const next = readAttempt();
      setAttempt(next);
      if (next?.tripId && (next.phase === 'saving' || next.phase === 'failed')) {
        setStep(4);
        setDraft((current) => ({
          ...current,
          planningLevel: 'MUST_VISIT_ONLY',
          mustVisit: next.picks.map((pick) => pick.place),
        }));
        const running = next.phase === 'saving';
        setSavingPicks(running);
        setHeldTrip({ id: next.tripId, unsaved: running ? [] : next.pending });
      } else if (!next && hadAttempt.current && createdTrip.current === null) {
        // Another instance finished after this one was mounted. Its draft was
        // spent; never let the old step-4 state issue a new create command.
        setHeldTrip(null);
        setSavingPicks(false);
        setDraft(EMPTY_DRAFT);
        setStep(1);
      }
      hadAttempt.current = Boolean(next);
    };
    window.addEventListener(ATTEMPT_CHANGED, sync);
    // Reconcile an answer that arrived between the first render and effect.
    sync();
    // A full page reload ends the old JavaScript promise. Resume the
    // unconfirmed writes under their original keys before naming failures.
    const unfinished = readAttempt();
    if (
      unfinished?.phase === 'saving' &&
      unfinished.tripId &&
      !isSaving(unfinished.tripId)
    ) {
      void savePicks(unfinished.tripId, unfinished.pending);
    }
    return () => window.removeEventListener(ATTEMPT_CHANGED, sync);
  }, []);

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
  //
  // `picks` are the must-visit places to save onto the trip once it exists.
  // Only the must-visit step passes them: they ride on the CANDIDATE, never on
  // CreateTripRequest (#180 option B), so a draft still holding picks from a
  // branch the traveller left does not send them from another one.
  async function submit(
    using: WizardDraft,
    seedItems?: SeedTripItem[],
    picks: PlaceSummary[] = [],
  ) {
    // This run already made its trip. Another createTrip would be a second
    // trip, and the first one's unsaved picks would be dropped without a word
    // (#185 review, measured: two trips). The held step is what the traveller
    // should be looking at instead, and `shownStep` puts it there — or, with
    // no picks to save, the trip, whose route is about to replace this one
    // (see `createdTrip`).
    if (createdTrip.current !== null) return;
    // The browser's zone: the trip is planned where the user is, and the
    // contract defaults to Asia/Seoul only when nothing is supplied.
    const timezone = Intl.DateTimeFormat().resolvedOptions().timeZone;
    const baseRequest = toCreateRequest(using, timezone);
    if (!baseRequest) return;
    const request = seedItems ? { ...baseRequest, seedItems } : baseRequest;
    const fingerprint = JSON.stringify(request);
    // The create request has no must-visit picks (#180 option B). Reuse its
    // key when the request is the same, but take the CURRENT selection and
    // preserve per-place keys only for picks that are still selected.
    const existing = readAttempt();
    if (existing?.tripId) return;
    const sameRequest = existing?.fingerprint === fingerprint;
    const key = sameRequest ? existing.key : crypto.randomUUID();
    const oldKeys = new Map(
      (sameRequest ? existing.picks : []).map((pick) => [pick.place.id, pick.key]),
    );
    const pendingPicks = picks.map((place) => ({
      place,
      key: oldKeys.get(place.id) ?? crypto.randomUUID(),
    }));
    if (!claimCreate(key)) return;
    writeAttempt({
      fingerprint,
      key,
      phase: 'creating',
      picks: pendingPicks,
      pending: pendingPicks,
      tripId: null,
    });
    // Continued from the PROMISE, not from a mutate() callback (#185 review).
    // TanStack Query drops mutate()'s callbacks once the component's observer
    // unsubscribes (MutationObserver.onUnsubscribe, read at 5.90.2). So
    // browser Back while the trip was being created left it made with none of
    // its picks and the draft still in storage, and coming back to /start
    // offered 이대로 채우기 on that draft under a fresh key: a second trip.
    // mutateAsync's promise settles whether or not anything still observes
    // the mutation.
    let trip: TripDetail;
    try {
      trip = await createTrip.mutateAsync({
        request,
        idempotencyKey: key,
      });
    } catch {
      releaseCreate(key);
      const current = readAttempt();
      if (current?.key === key) writeAttempt({ ...current, phase: 'create-failed' });
      // The step shows this from createTrip.isError, which the mutation sets
      // whether or not its promise is awaited. The key stays, so pressing
      // again replays this attempt.
      return;
    }
    // A response that arrived after another continuation already handled the
    // trip must not start the candidate writes again.
    if (createdTrip.current !== null) {
      releaseCreate(key);
      return;
    }
    // What follows belongs to the trip, so it happens whether or not the
    // wizard is still on screen.
    createdTrip.current = trip.id;
    // The draft became a trip, so it stops being a draft. Without this,
    // starting a second trip would reopen the finished one and the new trip
    // would inherit the first one's dates.
    clearSnapshot();
    releaseCreate(key);
    if (pendingPicks.length === 0) {
      writeAttempt(null);
      // Opening the trip belongs to the screen, and a traveller who has left
      // it is not pulled back — see the end of savePicks.
      if (mounted.current) {
        setLeavingTrip(true);
        enterTrip(trip.id);
      }
      return;
    }
    // One key per (trip, place), minted once here and kept by every retry of
    // that place: a retry after a lost response must replay the save the
    // server already made, not make a second one (invariant 6).
    void savePicks(trip.id, pendingPicks);
  }

  // Saves the picks onto the trip, one request after another.
  //
  // Sequential rather than concurrent, and not for simplicity alone: every
  // idempotent command takes its owner's row lock for its whole transaction
  // (IdempotencyGuard, `nullnull.idempotency.lock-timeout` PT3S), so the server
  // runs these one at a time whatever the client does. Firing them together
  // only adds lock waits, which on a slow day turn into failures of saves that
  // would have succeeded.
  //
  // Every pick is tried even after one fails, so the traveller is told about
  // all of them at once rather than one per retry. Only those that failed are
  // kept, with their keys, for 다시 시도.
  async function savePicks(tripId: string, picks: PendingPick[]) {
    if (!claimSave(tripId)) return;
    const currentAttempt = readAttempt();
    if (
      currentAttempt &&
      (currentAttempt.tripId === null || currentAttempt.tripId === tripId)
    ) {
      writeAttempt({ ...currentAttempt, phase: 'saving', tripId, pending: picks });
    }
    // Also reached after the wizard has gone, when createTrip answered late
    // (FE-103-T54). These two updates then do nothing — React drops updates
    // to an unmounted component — and the writes still go.
    setSavingPicks(true);
    setHeldTrip((current) => current ?? { id: tripId, unsaved: [] });
    const unsaved: PendingPick[] = [];
    for (const pick of picks) {
      try {
        await addCandidate.mutateAsync({
          tripId,
          idempotencyKey: pick.key,
          request: {
            placeId: pick.place.id,
            source: { type: 'SEARCH' },
            mustVisit: true,
          },
        });
        // On reload, only unconfirmed places need an idempotent replay. A
        // confirmed success must never be named in the failure alert.
        const progress = readAttempt();
        if (progress?.tripId === tripId && progress.phase === 'saving') {
          writeAttempt({
            ...progress,
            pending: progress.pending.filter((item) => item.place.id !== pick.place.id),
          });
        }
      } catch {
        unsaved.push(pick);
      }
    }
    if (unsaved.length === 0 && mounted.current) createdTrip.current = tripId;
    releaseSave(tripId);
    const latestAttempt = readAttempt();
    if (latestAttempt?.tripId === tripId) {
      writeAttempt(
        unsaved.length === 0
          ? null
          : { ...latestAttempt, phase: 'failed', pending: unsaved },
      );
    }
    // Gone before the writes finished. They were let run under their original
    // keys; a failed pick stays in the tab's recovery record and is announced
    // when the traveller returns to /start.
    //
    // Nor is the trip made the active one, although it exists and the
    // traveller did create it. That PATCH is half of opening the trip
    // (enterTrip), and repointing the 내 여행 tab at a trip they left before
    // it opened would move it behind their back. submit() decides the same
    // when createTrip itself answers after they left.
    if (!mounted.current) return;
    if (unsaved.length === 0) {
      setLeavingTrip(true);
      // Leaving, so `savingPicks` stays true. The wizard renders once more
      // before the trip's route commits (see `createdTrip`), and setting it
      // false first made that render offer 이대로 채우기, 건너뛰기, 담기 and
      // 빼기 again — and, after a retry that saved everything, show the
      // partial failure it had just cleared (#185 review). Held true, every
      // control on the step reads it as busy and the alert stays hidden.
      enterTrip(tripId);
      return;
    }
    setSavingPicks(false);
    setHeldTrip({ id: tripId, unsaved });
  }

  function enterTrip(tripId: string) {
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
    setActiveTrip.mutate({ activeTripId: tripId });
    void navigate(`/trip/${tripId}`, { replace: true });
  }

  function requestRecommendation(using: WizardDraft) {
    if (!using.startDate || !using.endDate) return;
    createTrip.reset();
    setStep(4);
    setRecommendedDraft(null);
    setRecommendedPicks(new Set());
    previewTripDraft.mutate(
      {
        startDate: using.startDate,
        endDate: using.endDate,
        timezone: Intl.DateTimeFormat().resolvedOptions().timeZone,
      },
      {
        onSuccess: (preview) => {
          setRecommendedDraft(preview);
        },
      },
    );
  }

  function leaveRecommendation(nextStep: number) {
    previewTripDraft.reset();
    setRecommendedDraft(null);
    setRecommendedPicks(new Set());
    setStep(nextStep);
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
      if (step === 4 && draft.planningLevel === 'NOTHING') {
        leaveRecommendation(3);
        return;
      }
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

  // A held trip pins the must-visit step, whatever `step` says (#185 review).
  // The review reached step 3 while the trip was being created; the trip then
  // arrived with a pick unsaved and step 3 had nothing to say about it — the
  // place was dropped without a word, and going on from step 3 made a second
  // trip. The back control is gone for that whole stretch now, but it goes
  // only once createTrip's pending state is published, which TanStack Query
  // does on a zero-delay timer after the press (query-core notifyManager, read
  // at 5.90.2), so a second press in that moment can still land; a test
  // reaches it with two clicks in one tick. `step` is left as it is: the held
  // state ends only by leaving /start, and not moving it writes no snapshot
  // after clearSnapshot().
  const shownStep = heldTrip ? 4 : step;
  const branch = heldTrip ? 'must-visit' : nextAfterPlanning(draft);
  const createBusy =
    createTrip.isPending || (attempt?.phase === 'creating' && isCreating(attempt.key));

  return (
    <section className={styles.screen} aria-labelledby="wizard-heading">
      <NavBar
        backLabel={t('wizard.back')}
        // Not offered from the press that creates the trip until the wizard
        // leaves (#185 review): while createTrip is in flight, then while a
        // trip is held — which starts with the first save. Every step behind
        // this one ends in a createTrip.
        onBack={createBusy || heldTrip || leavingTrip || savingPicks ? undefined : goBack}
        actions={
          <span className={styles.navStep}>
            {shownStep === 6 || (shownStep === 4 && branch === 'recommend')
              ? t('confirm.step')
              : `${t('wizard.step')} ${String(shownStep)}`}
          </span>
        }
      />
      {/* The confirm step names itself 마지막 rather than STEP 6: the frame
          says so, and a number implies a seventh step that does not exist. */}

      {shownStep === 1 ? (
        <>
          <div className={styles.datesFlow}>
            <div className={styles.datesHead}>
              <h1 className={styles.title} id="wizard-heading">
                {t('wizard.dates.title')}
              </h1>
              <p className={styles.lead}>{t('wizard.dates.lead')}</p>
            </div>

            <div className={styles.datesCalendar}>
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
                  {new Intl.DateTimeFormat(locale, {
                    year: 'numeric',
                    month: 'long',
                  }).format(month)}
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
            </div>
          </div>

          <BottomCta
            fixed
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

      {shownStep === 2 ? (
        <>
          <div className={styles.stepBody}>
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
          </div>

          <BottomCta
            fixed
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

      {shownStep === 3 ? (
        <>
          <div className={styles.stepBody}>
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
                      <img
                        alt=""
                        className={styles.optionIcon}
                        src={PLAN_LEVEL_ICONS[level]}
                      />
                      <span className={styles.optionText}>
                        <span className={styles.optionTitle}>
                          {t(`wizard.planning.${level}.title` as MessageKey)}
                        </span>
                        <span className={styles.optionBody}>
                          {t(`wizard.planning.${level}.body` as MessageKey)}
                        </span>
                      </span>
                      <IconArrowRight
                        aria-hidden="true"
                        className={styles.optionArrow}
                        size={20}
                      />
                    </button>
                  </li>
                );
              })}
            </ul>

            {createTrip.isError || attempt?.phase === 'create-failed' ? (
              <p className={styles.hint} role="alert">
                {t('wizard.createFailed')}
              </p>
            ) : null}
          </div>

          <BottomCta
            fixed
            label={createBusy ? t('wizard.creating') : t('wizard.next')}
            // Blocked while in flight: a second submit would be a second trip,
            // which the Idempotency-Key guards against but need not be tested by
            // the user (.claude/rules/frontend.md on duplicate submits).
            disabled={draft.planningLevel === null || createBusy}
            // The answer decides what follows: MUST_VISIT_ONLY opens its
            // picker, MOSTLY_PLANNED opens the input-method choice, and NOTHING
            // requests a recommendation preview. All three used to call
            // submit(), so the branch screens were skipped entirely (#185).
            onClick={() => {
              // Step 4 is whichever branch step 3 was answered with: the
              // must-visit picker (S02-4B) or the input-method choice
              // (S02-4C `400:1201`). NOTHING opens the deterministic, unsaved
              // recommendation preview; no trip exists until that screen is
              // explicitly confirmed.
              const next = nextAfterPlanning(draft);
              if (next === 'must-visit' || next === 'method') {
                setStep(4);
                return;
              }
              requestRecommendation(draft);
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
                disabled={createBusy}
                onClick={() => {
                  void navigate('/start/import', { state: { wizardDraft: draft } });
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
      {shownStep === 4 && branch === 'method' ? (
        <InputMethodStep
          onManual={() => {
            setStep(5);
          }}
          onPaste={() => {
            void navigate('/start/import', { state: { wizardDraft: draft } });
          }}
        />
      ) : null}

      {shownStep === 4 && branch === 'recommend' ? (
        <RecommendedDraftStep
          preview={recommendedDraft}
          error={previewTripDraft.error}
          loading={previewTripDraft.isPending}
          picked={recommendedPicks}
          isSubmitting={createBusy}
          createFailed={createTrip.isError || attempt?.phase === 'create-failed'}
          onTogglePick={(key) => {
            setRecommendedPicks((current) => {
              const next = new Set(current);
              if (next.has(key)) next.delete(key);
              else next.add(key);
              return next;
            });
          }}
          onSubmit={() => {
            if (!recommendedDraft || recommendedDraft.state !== 'READY') return;
            void submit(draft, recommendedSeedItems(recommendedDraft, recommendedPicks));
          }}
          onRetry={() => {
            requestRecommendation(draft);
          }}
          onChangeDates={() => {
            leaveRecommendation(1);
          }}
          onEdit={() => {
            leaveRecommendation(3);
          }}
        />
      ) : null}

      {/* S02-4C-C `438:3199`: the manual half of the input-method branch.
          Before this existed, InputMethodStep's 직접 입력 called setStep(5)
          and nothing rendered there, so choosing it landed on a blank
          screen — a reachable dead end of exactly the kind #185 is about. */}
      {shownStep === 5 ? (
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
              void submit(draft);
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
            void submit(cleared);
          }}
          isSubmitting={createBusy}
        />
      ) : null}

      {/* S02-5C `438:3259`: read the itinerary back and pick what must stay.
          FIGMA_HANDOFF:154 describes this as a summary, which is how its Pick
          toggle went uncounted — it is an input screen. */}
      {shownStep === 6 ? (
        <ConfirmStopsStep
          draft={draft}
          onTogglePick={(key) => {
            setDraft((current) => toggleStopMustVisit(current, key));
          }}
          onSubmit={() => {
            void submit(draft);
          }}
          onEdit={() => {
            // 다시 고칠래요 goes back to the entry step with everything intact,
            // picks included — the same promise the wizard makes at every other
            // step. It is not a cancel and drops nothing.
            setStep(5);
          }}
          isSubmitting={createBusy}
        />
      ) : null}

      {shownStep === 4 && branch === 'must-visit' ? (
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
            void submit(draft, undefined, draft.mustVisit);
          }}
          onSkip={() => {
            // A real answer, not a cancel: the traveller says there are no
            // must-visit places, so the trip is created without any. Clearing
            // first keeps that honest — pressing 건너뛰기 after picking some
            // must not quietly carry them.
            //
            // No picks are passed, so no candidate is written: the picks
            // reach the server only as submit's third argument, which 이대로
            // 채우기 fills and this leaves empty (#185). The cleared draft is
            // passed rather than only stored, for the reason submit() states.
            const cleared = { ...draft, mustVisit: [] };
            setDraft(cleared);
            void submit(cleared);
          }}
          isSubmitting={
            createBusy ||
            savingPicks ||
            leavingTrip ||
            (attempt?.phase === 'saving' &&
              attempt.tripId !== null &&
              isSaving(attempt.tripId))
          }
          unsaved={heldTrip?.unsaved.map((pick) => pick.place) ?? []}
          onRetryUnsaved={() => {
            // The same trip and the same keys: nothing here can create a
            // trip, and a place whose first save did land is replayed.
            if (heldTrip) void savePicks(heldTrip.id, heldTrip.unsaved);
          }}
          onOpenTrip={() => {
            // Without the unsaved places, which the traveller was just told
            // about by name.
            if (heldTrip) {
              createdTrip.current = heldTrip.id;
              writeAttempt(null);
              setLeavingTrip(true);
              enterTrip(heldTrip.id);
            }
          }}
          startDate={draft.startDate}
        />
      ) : null}
    </section>
  );
}
