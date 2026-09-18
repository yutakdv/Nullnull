import { useState } from 'react';
import { useI18n } from '../../i18n/I18nProvider.js';
import {
  useLatestTripOptimization,
  useOptimization,
  useRevertOptimizationDecision,
} from '../../shared/api/index.js';
import { AppliedPanel } from './AppliedPanel.js';
import {
  appliedDecision,
  formatInstant,
  panelVersions,
  revertDecision,
  shouldReadRun,
} from './applied-revert.js';

// The undo panel's data, kept out of TripScreen (S09-3, FE-504).
//
// Two reads, in sequence:
//
//   1. listOptimizationHistory(tripId, limit 1) — names the trip's last run.
//      Its rows carry no revert state at all: OptimizationHistoryItem is
//      `additionalProperties: false` over nine fields and holds neither
//      `revertAvailability` nor the decision id.
//   2. getOptimization(runId) — the run itself, which carries both.
//
// The second is conditional because the trip screen is the most-opened screen
// in the app and most visits have nothing to undo. `shouldReadRun` is that
// gate, and its own file documents the one thing it must never become.

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

  const history = useLatestTripOptimization(tripId);
  const latest = history.data?.items[0];

  // THE 24H CHECK HERE DECIDES WHETHER TO SEND THE SECOND REQUEST, NEVER
  // WHETHER TO ENABLE THE BUTTON. The contract is explicit that absence of
  // revertAvailability is "never permission to enable undo using client time"
  // — so the panel renders from the server's value alone, and this clock only
  // avoids a request that would come back NOT_APPLICABLE anyway.
  const worthReading = shouldReadRun(latest, Date.now());
  const runId = worthReading && latest ? latest.runId : null;

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

  return (
    <AppliedPanel
      // Straight from the server. The panel has no other source for this, by
      // design — see its own header.
      availability={detail.revertAvailability}
      summary={apply.proposalId ? (detail.proposals[0]?.summary ?? '') : ''}
      fromVersion={versions.from}
      toVersion={versions.to}
      appliedAt={appliedAt}
      revertUntil={revertUntil}
      submitting={revert.isPending}
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
      }}
    />
  );
}
