import { useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router';
import type { components } from '@nullnull/api-client';
import { useI18n } from '../../i18n/I18nProvider.js';
import { type MessageKey, messages } from '../../i18n/messages.js';
import {
  isProblem,
  isRunning,
  useDecideOptimization,
  useOptimization,
  useTrip,
} from '../../shared/api/index.js';
import { DecisionBar, NavBar } from '../../shared/ui/index.js';
import { decisionPhase, isStale } from './preview.js';
import { ProposalCard, type ProposalCardProps } from './ProposalCard.js';
import styles from './OptimizationRunScreen.module.css';

// Figma: S09-1 계산 중 `415:2413` (FR-OPT-03, FE-502).
//
// This screen is where a submitted run lives until it settles. Before it
// existed the route rendered a placeholder, so pressing 대안 찾아보기 landed on
// a page whose entire content was the debug string "optimization" — and every
// 최적화 결과 보기 link in the profile history went to the same place.
//
// Three rules shape it:
//
//   - Nothing here changes the itinerary. A run is a preview until the user
//     applies it, and a failure, an expiry or a still-queued run all leave the
//     trip exactly as it was (invariants 3 and 4). The screen says so in words
//     rather than leaving the user to infer it, because "계산하지 못했어요" on
//     its own reads as though something might have half-happened.
//   - Leaving is navigation, not cancellation. FCR-014 settled that P0 has no
//     cancel operation, so the control says 내 여행으로 돌아가기 and the copy
//     states that the run continues and can be reopened by URL. Naming it
//     취소 would promise an operation the contract does not have.
//   - No invented numbers. FCR-005 removed route/time copy while P0 has no
//     route provider, so the steps name crowd and locks only.
//
// The READY preview renders the server's proposals with before/after metrics,
// provenance and an APPLY/KEEP decision bar. A READY run with no proposals
// still reports that the result arrived and stops: rendering a comparison from
// an empty array would invent the value invariant 8 forbids.

type OptimizationStatus = components['schemas']['OptimizationStatus'];

/**
 * What the live region says for each run status.
 *
 * All eight are named. An earlier version fell through to `run.ready` for
 * anything that was not QUEUED, RUNNING, READY or a failure, which meant an
 * APPLIED, KEPT, REVERTED or EXPIRED run announced "대안이 준비됐어요" — a
 * finished decision presented as one still waiting to be made. Those rows are
 * reachable: the profile's optimization history links straight to them, and
 * the history fixture ships one of each.
 *
 * EXPIRED is a status, not a failure: OptimizationFailure's code enum has no
 * EXPIRED member, so an expired run legitimately arrives with failure null and
 * would otherwise have taken the fallback too.
 */
function statusMessage(status: OptimizationStatus, failed: boolean): MessageKey {
  switch (status) {
    case 'QUEUED':
      return 'run.queued';
    case 'RUNNING':
      return 'run.running';
    case 'READY':
      return 'run.ready';
    case 'APPLIED':
      return 'run.applied';
    case 'KEPT':
      return 'run.kept';
    case 'REVERTED':
      return 'run.reverted';
    case 'EXPIRED':
      return 'run.expiredStatus';
    case 'FAILED':
      return 'run.failed';
    default:
      // The union is exhausted above; this keeps a server that adds a status
      // from silently reading as one of the others.
      return failed ? 'run.failed' : 'run.loading';
  }
}

/**
 * What the HEADING says for each run status (#279 하1).
 *
 * Separate from `statusMessage` rather than reusing it: that one is a sentence
 * for a live region ("대안이 준비됐어요"), this one is a page title, and the two
 * are not interchangeable copy even where they agree on the state. Sharing one
 * key would make the heading read as an announcement.
 *
 * QUEUED and RUNNING keep the searching title — that is what the screen is
 * genuinely doing. The rest name the state the run finished in, because a
 * decided run is not still being searched for.
 */
function titleMessage(status: OptimizationStatus): MessageKey {
  switch (status) {
    case 'QUEUED':
    case 'RUNNING':
      return 'run.title';
    case 'READY':
      return 'run.title.ready';
    case 'APPLIED':
      return 'run.title.applied';
    case 'KEPT':
      return 'run.title.kept';
    case 'REVERTED':
      return 'run.title.reverted';
    case 'EXPIRED':
      return 'run.title.expired';
    case 'FAILED':
      return 'run.title.failed';
    default:
      // As in `statusMessage`: a status this build does not know keeps the
      // neutral title rather than borrowing another state's.
      return 'run.title';
  }
}

type OptimizationFailure = components['schemas']['OptimizationFailure'];

/**
 * The failure codes the contract defines, each with its own sentence.
 *
 * Takes the failure rather than the run: naming OptimizationRun in a signature
 * fails to compile with "two different types with this name exist", because
 * the run nests the OptimizationChange union — the same trap
 * useCreateOptimization documents in session.ts.
 */
/**
 * The message key for a failure, or the generic one for a code we do not know.
 *
 * The key is built from the code, so a code added to the contract after this
 * build shipped would name a message that does not exist — and `t()` returns
 * the missing value as-is, which rendered the literal string "undefined" into
 * the error screen. Measured, not assumed: a probe render of an unknown key
 * produced "[undefined]".
 *
 * Folding to the generic message instead means the server can add a failure
 * code without waiting for a matching client deploy. The user sees "it failed"
 * rather than a token, which is worse than the specific wording and much better
 * than a bug. Each known code still gets its own line.
 */
function failureMessage(failure: OptimizationFailure | null | undefined) {
  if (!failure) return null;
  const key = `run.failure.${failure.code}` as MessageKey;
  return key in messages['ko-KR'] ? key : ('run.failure.unknown' as MessageKey);
}

export function OptimizationRunScreen() {
  const { t } = useI18n();
  const navigate = useNavigate();
  const { tripId = null, runId = null } = useParams<{
    tripId: string;
    runId: string;
  }>();
  const run = useOptimization(runId);
  // The trip is read for two things and written for neither: its ETag guards
  // the decision (If-Match) and its version says whether the run still
  // describes the itinerary the user is looking at.
  const trip = useTrip(tripId);
  const decide = useDecideOptimization(runId, tripId);

  // Which proposal the bar acts on. Screen-local: it is a choice about what to
  // look at, not server state, and nothing outside this screen needs it.
  const [chosenId, setChosenId] = useState<string | null>(null);

  // One key per (proposal, decision) pair, held across retries of that same
  // command.
  //
  // The contract makes this load-bearing: a failed APPLY "records no decision
  // … so the same idempotency key can replay it". Minting a fresh key on the
  // user's retry would send a SECOND command while the first may yet have
  // landed. The key resets when the pair changes because APPLY and KEEP are
  // different commands, and so is the same decision on another proposal.
  const decisionKeys = useRef<Record<string, string>>({});

  function submitDecision(proposalId: string, decision: 'APPLY' | 'KEEP') {
    const pair = `${proposalId}:${decision}`;
    decisionKeys.current[pair] ??= crypto.randomUUID();
    decide.mutate({
      request: { proposalId, decision },
      etag: trip.data?.etag ?? null,
      idempotencyKey: decisionKeys.current[pair],
    });
  }

  function back() {
    void navigate(tripId === null ? '/feed' : `/trip/${tripId}`);
  }

  /**
   * The screen, titled for what it is showing (#279 하1).
   *
   * `titleKey` defaults to the searching title because the two callers that
   * omit it are the states before a run has been read — pending, and the error
   * branch where `detail` never arrived. Once there IS a run, its status
   * decides, which is why the last caller passes one.
   *
   * This is the same fix `statusMessage` above already made for the live
   * region, and its comment records that an APPLIED or KEPT run once announced
   * "대안이 준비됐어요". The heading kept doing it: a rehearsal reached APPLIED,
   * KEPT and FAILED and read "대안을 찾고 있어요" over all three. A screen reader
   * heard the right sentence and the screen showed the wrong one.
   */
  const frame = (body: React.ReactNode, titleKey: MessageKey = 'run.title') => (
    <section aria-labelledby="run-heading" className={styles.screen}>
      <NavBar backLabel={t('run.leave')} onBack={back} />
      <h1 className={styles.title} id="run-heading">
        {t(titleKey)}
      </h1>
      {body}
    </section>
  );

  if (run.isPending) {
    return frame(
      <p className={styles.state} role="status">
        {t('run.loading')}
      </p>,
    );
  }

  if (run.isError) {
    // An expired preview and a missing run are terminal: there is nothing to
    // retry, and the contract says an expiry "does not apply to an already
    // recorded decision", so the itinerary is untouched either way.
    const problem = isProblem(run.error) ? run.error : null;
    const expired = problem?.code === 'PREVIEW_EXPIRED';
    const missing = problem?.code === 'NOT_FOUND';
    // The catalog is closed, so a run holding proposals cannot be described:
    // a proposal's summary names the place and its provenance quotes the
    // source registry. Temporary, unlike the two above — the retry button
    // below stays, and `terminal` deliberately does not include this.
    const sourceDown = problem?.code === 'SOURCE_UNAVAILABLE';
    const terminal = expired || missing;
    return frame(
      <>
        <p className={styles.status} role="alert">
          {expired
            ? t('run.expired')
            : missing
              ? t('run.notFound')
              : sourceDown
                ? t('run.sourceUnavailable')
                : t('run.error')}
        </p>
        {expired || sourceDown ? (
          <p className={styles.note}>{t('run.unchanged')}</p>
        ) : null}
        <div className={styles.actions}>
          {terminal ? null : (
            <button
              className={styles.primary}
              onClick={() => {
                void run.refetch();
              }}
              type="button"
            >
              {t('optimize.retry')}
            </button>
          )}
          {expired ? (
            <button
              className={styles.primary}
              onClick={() => {
                void navigate(`/trip/${tripId ?? ''}/optimize`);
              }}
              type="button"
            >
              {t('run.recompute')}
            </button>
          ) : null}
          <button className={styles.secondary} onClick={back} type="button">
            {t('run.leave')}
          </button>
        </div>
      </>,
    );
  }

  const detail = run.data;
  const working = isRunning(detail.status);
  const failure = failureMessage(detail.failure);

  // `proposals` is required in the contract, so there is no `?? []` here: an
  // absent array would be a client that did not match the schema, and papering
  // over it would hide that.
  //
  // The cast is the identity problem `useCreateOptimization` documents on
  // itself. OptimizationProposal nests the OptimizationChange union, and the
  // type openapi-fetch infers for this response is structurally identical to
  // `components['schemas']['OptimizationProposal']` but not the same identity —
  // TypeScript reports "two different types with this name exist". Both sides
  // come from the same generated file, so this asserts sameness rather than
  // widening anything.
  const proposals = detail.proposals as ProposalCardProps['proposal'][];
  // A choice exists only when there is more than one thing to choose. See
  // ProposalCard's `selected` prop for why a lone card is not selectable.
  const selectable = proposals.length > 1;
  // Falls back to the server's own ranking rather than to "nothing selected":
  // rank 1 is what the optimizer put first, and an unselected bar would make
  // the user pick before they can act even when there is only one option.
  const bestRanked = [...proposals].sort((a, b) => a.rank - b.rank)[0];
  const selectedId = chosenId ?? bestRanked?.id ?? null;

  // Advisory only — the APPLY still carries If-Match and can still lose to a
  // 409. This decides what the bar OFFERS, not whether the write is safe.
  const stale = isStale(detail.inputTripVersion, trip.data?.trip.version);
  const phase = decisionPhase({
    status: detail.status,
    hasProposals: proposals.length > 0,
    stale,
    pending: decide.isPending,
    // The CODE, not just the fact of a failure. The run query above already
    // reads `problem.code` to tell an expiry from a generic error; this path
    // used to pass a bare boolean and lost that distinction, which is what
    // left a refused APPLY offering a retry that could never succeed (#279).
    refusedWith: isProblem(decide.error) ? decide.error.code : null,
  });

  const proposalLabels = {
    crowdLabel: t('run.proposal.crowd'),
    crowdDown: t('run.proposal.crowdDown'),
    crowdUp: t('run.proposal.crowdUp'),
    comparisonUnavailable: t('run.proposal.comparisonUnavailable'),
    changesTitle: t('run.proposal.changes'),
    changeCount: t('run.proposal.changeCount'),
    moveDate: t('run.proposal.moveDate'),
    moveTime: t('run.proposal.moveTime'),
    moveOrder: t('run.proposal.moveOrder'),
    add: t('run.proposal.add'),
    remove: t('run.proposal.remove'),
    constraintsOk: t('run.proposal.constraintsOk'),
    constraintsBroken: t('run.proposal.constraintsBroken'),
    licenseTerms: t('license.terms'),
  };

  return frame(
    <>
      {/* One live region for the whole run, so a screen reader hears the
          state change instead of only the first state it landed on. */}
      <p aria-live="polite" className={styles.status} role="status">
        {t(statusMessage(detail.status, failure !== null))}
      </p>

      {working ? (
        <>
          <p className={styles.lead}>{t('run.working')}</p>
          {/* FCR-005: crowd and locks only. P0 has no route provider, so a
              `경로 계산` step would describe work nothing does. */}
          <ul className={styles.steps}>
            <li className={styles.step} data-active>
              <span aria-hidden="true" className={styles.dot} />
              {t('run.step.crowd')}
            </li>
            <li className={styles.step} data-active={detail.status === 'RUNNING'}>
              <span aria-hidden="true" className={styles.dot} />
              {t('run.step.locks')}
            </li>
          </ul>
        </>
      ) : null}

      {failure ? (
        <>
          <p className={styles.lead}>{t(failure)}</p>
          {/* Invariant 4, stated: a failed run changes nothing. */}
          <p className={styles.note}>{t('run.unchanged')}</p>
        </>
      ) : null}

      {detail.status === 'READY' && proposals.length === 0 ? (
        // READY with nothing to show. BA-051 computes the proposals, so a run
        // can settle before they exist; saying so is honest, where a blank
        // panel would look broken.
        <p className={styles.note}>{t('run.readyPending')}</p>
      ) : null}

      {proposals.length > 0 ? (
        // `radiogroup` only when there is a choice. With one proposal the cards
        // carry no role and no tabindex (see ProposalCard), and wrapping them
        // in a group would announce a choice that does not exist.
        <div
          // Named by the screen's own heading rather than by a new string.
          // `run.title` is what this group is a list of, and adding a key here
          // would put a sentence in front of users that nobody reviewed — the
          // i18n file is owned elsewhere. If a dedicated label is wanted later
          // it replaces this attribute and nothing else.
          aria-labelledby={selectable ? 'run-heading' : undefined}
          className={styles.proposals}
          role={selectable ? 'radiogroup' : undefined}
        >
          {proposals.map((proposal) => (
            <ProposalCard
              key={proposal.id}
              labels={proposalLabels}
              onSelect={selectable ? setChosenId : undefined}
              proposal={proposal}
              selected={selectable ? proposal.id === selectedId : undefined}
            />
          ))}
        </div>
      ) : null}

      {/* Invariant 3's user-facing half: this is the only control in the app
          that can move an itinerary from an optimization, and it appears only
          when `decisionPhase` says a decision is open. Opening this screen,
          polling it, or choosing a card sends nothing.

          Invariant 4 is the bar's own rule: it always offers a way to decline.
          `preview` gives both buttons; `stale` and `failed` give a recovery
          action instead of a one-sided "apply anyway". */}
      {phase.kind === 'bar' ? (
        <DecisionBar
          // All nine, not the four this screen happens to show today. The
          // component falls back to its own Korean defaults for anything
          // omitted, so a partial object renders Korean inside an English app
          // — and only in the states that are hard to reach (stale, failed),
          // which is where a missing translation survives longest.
          labels={{
            apply: t('decision.apply'),
            keep: t('decision.keep'),
            applying: t('decision.applying'),
            applied: t('decision.applied'),
            staleMessage: t('decision.staleMessage'),
            staleAction: t('decision.staleAction'),
            failedMessage: t('decision.failedMessage'),
            failedAction: t('decision.failedAction'),
            groupLabel: t('decision.groupLabel'),
          }}
          onApply={
            selectedId === null
              ? undefined
              : () => {
                  submitDecision(selectedId, 'APPLY');
                }
          }
          onKeep={
            selectedId === null
              ? undefined
              : () => {
                  submitDecision(selectedId, 'KEEP');
                }
          }
          onRecover={() => {
            // The two states recover differently, and the bar's own copy says
            // so: `staleAction` is '최신 일정으로 다시 계산' and `failedAction`
            // is '다시 시도' beside "네트워크 상태를 확인하고".
            //
            // stale — re-read. The run was computed against a version the trip
            // has moved past, and nothing about resending the same decision
            // fixes that. The refetch is what tells us whether it is still
            // stale.
            if (phase.state === 'stale') {
              void run.refetch();
              void trip.refetch();
              return;
            }
            // failed — resend THE SAME command. The contract: a failed APPLY
            // "records no decision … so the same idempotency key can replay
            // it". `submitDecision` reuses the key it minted for this pair, so
            // this is a replay and not a second decision.
            //
            // Replayed from `decide.variables` — the arguments of the call that
            // failed — rather than from the current selection. Hardcoding
            // 'APPLY' here would turn a failed KEEP into an APPLY on the retry
            // button, which is the one substitution invariant 4 cannot survive:
            // the user declined and the screen would apply.
            // …but the DECISION is replayed, not the proposal. The cards stay
            // on screen while the bar shows `failed`, so the user can change
            // their mind between the failure and the retry — and if they did,
            // resending the old proposalId applies something they are no
            // longer looking at. `submitDecision` keys by (proposal, decision),
            // so the moved selection gets its own key and the server sees a new
            // command rather than a replay of the abandoned one.
            const last = decide.variables?.request;
            if (last) submitDecision(selectedId ?? last.proposalId, last.decision);
          }}
          state={phase.state}
        />
      ) : null}

      <div className={styles.actions}>
        {failure && detail.failure?.retryable ? (
          <button
            className={styles.primary}
            onClick={() => {
              void navigate(`/trip/${tripId ?? ''}/optimize`);
            }}
            type="button"
          >
            {t('run.recompute')}
          </button>
        ) : null}
        <button className={styles.secondary} onClick={back} type="button">
          {t('run.leave')}
        </button>
      </div>

      {/* FCR-014: leaving is navigation. The run is not cancelled by it, and
          the URL brings the user back to it. */}
      {working ? <p className={styles.note}>{t('run.keepsRunning')}</p> : null}
    </>,
    titleMessage(detail.status),
  );
}
