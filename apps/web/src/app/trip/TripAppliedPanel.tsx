import { useState } from 'react';
import { useI18n } from '../../i18n/I18nProvider.js';
import {
  isProblem,
  useTripOptimizationHistory,
  useOptimization,
  useRevertOptimizationDecision,
} from '../../shared/api/index.js';
import { AppliedPanel } from './AppliedPanel.js';
import {
  appliedDecision,
  formatInstant,
  latestDecidedRun,
  panelVersions,
  revertDecision,
} from './applied-revert.js';

// The undo panel's data, kept out of TripScreen (S09-3, FE-504).
//
// Two reads, in sequence:
//
//   1. listOptimizationHistory(tripId) — a page of the trip's recent runs,
//      newest-first. Its rows carry no revert state at all:
//      OptimizationHistoryItem is `additionalProperties: false` over nine
//      fields and holds neither `revertAvailability` nor the decision id, so
//      this step can only NAME a run, never describe one.
//   2. getOptimization(runId) — the run itself, which carries both.
//
// Step one used to ask for a single row and take it. That read "the trip's
// last run" as "the run the panel is about", and the two part company the
// moment anything happens after the apply: a second optimization puts a
// RUNNING row at position 0 and the undo panel disappeared while its 24-hour
// window was still open. `latestDecidedRun` scans instead, and the contract's
// own example for this operation — five rows, RUNNING and READY above the
// APPLIED one — is exactly the case that could never render before.
//
// The second read is conditional because the trip screen is the most-opened
// screen in the app and most visits have nothing to undo. `shouldReadRun` is
// that gate, and its own file documents the one thing it must never become.

export interface TripAppliedPanelProps {
  tripId: string;
  /** The trip's current ETag, which If-Match requires. */
  etag: string | null;
}

export function TripAppliedPanel({ tripId, etag }: TripAppliedPanelProps) {
  const { locale, t } = useI18n();
  // Minted per attempt rather than per press: the contract allows the revert
  // once, so a retry after a 503 must replay the first command rather than
  // queue a second. Held in state so a re-render does not mint a new one
  // mid-flight.
  const [idempotencyKey] = useState(() => crypto.randomUUID());

  const history = useTripOptimizationHistory(tripId);

  // THE 24H CHECK HERE DECIDES WHETHER TO SEND THE SECOND REQUEST, NEVER
  // WHETHER TO ENABLE THE BUTTON. The contract is explicit that absence of
  // revertAvailability is "never permission to enable undo using client time"
  // — so the panel renders from the server's value alone, and this clock only
  // avoids a request that would come back NOT_APPLICABLE anyway.
  //
  // That check now runs over the page rather than over row 0 alone, which
  // changes which run is asked about but not what the answer is allowed to do:
  // the undo is still the server's to grant.
  const latest = latestDecidedRun(history.data?.items, Date.now());
  const runId = latest?.runId ?? null;

  const run = useOptimization(runId);
  const revert = useRevertOptimizationDecision(runId, tripId);

  const detail = run.data;
  if (!detail) return null;

  const apply = appliedDecision(detail.decisions);
  const versions = panelVersions(detail.decisions, detail.inputTripVersion);
  if (!apply || !versions) return null;

  const reverted = revertDecision(detail.decisions);
  const appliedAt = formatInstant(apply.decidedAt, locale) ?? '';
  const revertUntil = formatInstant(apply.revertUntil, locale) ?? '';
  const revertedAt = formatInstant(reverted?.decidedAt, locale) ?? '';

  const numbers = { from: versions.from, to: versions.to };

  // How the last revert attempt failed, translated once here so the panel
  // stays presentational.
  //
  // `retryable` comes from the problem policy rather than from a guess: the
  // table gives REVERT_WINDOW_EXPIRED `retry: 'none'` and `recovery: 'none'`,
  // and a retry button on that code would offer a press the server has already
  // refused for good. Anything else — a 503, a dropped connection — keeps the
  // press, because the command is replayable and the key below makes replaying
  // it safe.
  //
  // A transport failure is not a Problem: `isProblem` is false when the request
  // never reached the server, so `code` is undefined and it lands in the
  // retryable branch, which is the correct reading of an offline attempt.
  const revertProblem = isProblem(revert.error) ? revert.error : null;
  const failure = revert.isError
    ? {
        message:
          revertProblem?.code === 'REVERT_WINDOW_EXPIRED'
            ? t('trip.applied.failed.expired')
            : t('trip.applied.failed.retryable'),
        retryable: revertProblem?.code !== 'REVERT_WINDOW_EXPIRED',
      }
    : undefined;

  return (
    <AppliedPanel
      // Straight from the server. The panel has no other source for this, by
      // design — see its own header.
      availability={detail.revertAvailability}
      failure={failure}
      summary={apply.proposalId ? (detail.proposals[0]?.summary ?? '') : ''}
      fromVersion={versions.from}
      toVersion={versions.to}
      appliedAt={appliedAt}
      revertUntil={revertUntil}
      submitting={revert.isPending}
      // The retry button presses THIS, unchanged, and that is the whole
      // mechanism: `idempotencyKey` is minted once per mount (see the top of
      // this file), so a second press after a failure replays the first
      // command instead of queuing a second revert. Minting a key here — the
      // obvious-looking place, once a retry exists — would turn one retried
      // revert into two distinct ones and break invariant 6. There is no
      // separate onRetry for exactly that reason.
      onRevert={() => {
        revert.mutate({ decisionId: apply.id, etag, idempotencyKey });
      }}
      labels={{
        badgeAvailable: t('trip.applied.badge.available'),
        badgeSubmitting: t('trip.applied.badge.submitting'),
        badgeReverted: t('trip.applied.badge.reverted'),
        badgeExpired: t('trip.applied.badge.expired'),
        revisionAvailable: t('trip.applied.revision.available', {
          ...numbers,
          appliedAt,
          revertUntil,
        }),
        revisionExpired: t('trip.applied.revision.expired', {
          ...numbers,
          appliedAt,
          revertUntil,
        }),
        revisionReverted: t('trip.applied.revision.reverted', {
          ...numbers,
          revertedAt,
          restored: versions.restored,
        }),
        revert: t('trip.applied.revert', { from: versions.from }),
        reverting: t('trip.applied.reverting'),
        expired: t('trip.applied.expired'),
        badgeFailed: t('trip.applied.badge.failed'),
        retry: t('trip.applied.retry'),
      }}
    />
  );
}
