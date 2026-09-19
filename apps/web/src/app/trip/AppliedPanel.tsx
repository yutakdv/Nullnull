import styles from './AppliedPanel.module.css';

// The applied-optimization panel on the trip screen (S09-3, FE-503).
//
// Figma: `417:2412` AVAILABLE, `724:4602` submitting, `724:4730` REVERTED,
// `724:4858` EXPIRED. Those four frames are the TRIP screen, not the run
// screen — the panel is persistent (`FCR-015`), so a traveller who comes back
// the next morning still finds the undo their apply earned.
//
// The panel NEVER decides whether revert is possible. `revertAvailability` is
// a server projection and the contract is explicit about why:
//
//   "Absence means unknown support, never permission to enable undo using
//    client time. AVAILABLE is advisory: the mutation rechecks owner, version,
//    window and decision."
//
// So `undefined` renders no button — not a disabled one, and never a button
// derived from comparing `revertUntil` to this device's clock. A wrong clock
// would otherwise offer an action the server is certain to refuse.
//
// Everything is a prop. The panel does no fetching and owns no state, which is
// what lets all four states be rendered in a unit test without a server.

/** The server's own projection. `undefined` means the field was absent. */
export type RevertAvailability = 'AVAILABLE' | 'EXPIRED' | 'REVERTED' | 'NOT_APPLICABLE';

export interface AppliedPanelProps {
  /**
   * `OptimizationRun.revertAvailability`, passed through untouched.
   *
   * `undefined` is a real case the contract names ("unknown support"), and it
   * is not the same as `NOT_APPLICABLE`: one means the server did not answer,
   * the other means it answered "no". Both render without a button, so the
   * distinction does not reach the screen — but it must not be collapsed here,
   * because a later reader would otherwise see a total of three states and
   * conclude the fourth is impossible.
   */
  availability: RevertAvailability | undefined;
  /**
   * The server's sentence about what changed — `proposal.summary` on apply,
   * and the reverted equivalent afterwards.
   *
   * Rendered verbatim. Invariant 9 puts the LLM outside the set of things that
   * decide facts, and a summary this file assembled from versions and place
   * names would be exactly that: a claim about the itinerary with no source.
   */
  summary: string;
  /** `OptimizationRun.inputTripVersion` — the version the run was computed on. */
  fromVersion: number;
  /** `ApplyOptimizationDecision.resultingTripVersion`. */
  toVersion: number;
  /** Already formatted by the caller, which owns the locale. See `labels`. */
  appliedAt: string;
  /**
   * Already formatted. The deadline on AVAILABLE, the missed deadline on
   * EXPIRED, and unused on the other two.
   */
  revertUntil: string;
  /** True while the revert request is in flight. */
  submitting?: boolean;
  /**
   * How the last revert attempt failed, or `undefined` when none has.
   *
   * Figma draws no failure frame for this panel, and that absence is not
   * permission to render nothing: `417:2567` — the S09 error reference — lists
   * six codes and says they appear as "S09 Preview 위 배너", on the PREVIEW
   * screen. A revert that fails is not in that list and has nowhere else to go,
   * so it is drawn here in the two slots every other state uses (badge and
   * button) rather than as a new banner the design does not have.
   *
   * `retryable` is the caller's reading of the problem policy, not this file's
   * guess: `REVERT_WINDOW_EXPIRED` is `retry: 'none'` and `recovery: 'none'`,
   * so it must not offer a press that the server is certain to refuse, while a
   * 503 must. Collapsing the two into one "it failed" state would tell the
   * traveller to try again in the one case where trying again cannot work.
   */
  failure?: { message: string; retryable: boolean };
  /** Absent when there is nothing to press — see `availability`. */
  onRevert?: () => void;
  /**
   * Localized copy from the caller.
   *
   * Placeholders are substituted by the caller's `t()`, so the strings arrive
   * finished: this file never concatenates copy, and a locale that orders the
   * clauses differently needs no change here.
   */
  labels: {
    badgeAvailable: string;
    badgeSubmitting: string;
    badgeReverted: string;
    badgeExpired: string;
    /** "일정 v7 → v8 · 10/7 14:35 적용 · 10/8 14:35까지 되돌릴 수 있어요" */
    revisionAvailable: string;
    /** Same two versions, but the deadline has passed. */
    revisionExpired: string;
    /** v8 → v9: the revert is a NEW revision, not a rewind. */
    revisionReverted: string;
    revert: string;
    reverting: string;
    /** Why the button cannot be pressed, and where to go instead. */
    expired: string;
    /** The badge beside the result while the last attempt is unresolved. */
    badgeFailed: string;
    /** The button that replays the SAME command — see `TripAppliedPanel`. */
    retry: string;
  };
}

/**
 * Which frame to draw: Figma's four, plus the failure state it does not draw.
 *
 * `submitting` wins over the server's value because the request is in flight
 * against exactly that value: the panel showed AVAILABLE, the traveller
 * pressed, and until the response lands AVAILABLE is still what the server
 * last said. Reading `availability` here would flicker the button back to
 * pressable mid-request.
 *
 * `failed` sits directly under `submitting` and above the server's field for
 * the same reason, one step later: the request has landed and it failed, while
 * `revertAvailability` still holds the value the panel was drawn from before
 * the press. Letting that field win would return the panel to a pressable
 * AVAILABLE and erase the failure — the traveller would see the button they
 * just pressed, unchanged, with no word about what happened.
 */
function frameFor(
  availability: RevertAvailability | undefined,
  submitting: boolean,
  failed: boolean,
): 'available' | 'submitting' | 'failed' | 'reverted' | 'expired' | 'none' {
  if (submitting) return 'submitting';
  // Only where there was something to undo. A failure reported against
  // REVERTED or an absent field would otherwise conjure a panel out of a
  // state that draws none, which is the "발화할 수 없는 단언"'s mirror: a
  // frame nothing can reach.
  if (failed && availability === 'AVAILABLE') return 'failed';
  if (availability === 'AVAILABLE') return 'available';
  if (availability === 'REVERTED') return 'reverted';
  if (availability === 'EXPIRED') return 'expired';
  // NOT_APPLICABLE and an absent field both land here. The contract's
  // precedence list gives NOT_APPLICABLE two causes — no APPLY decision, and a
  // trip version that has moved since — and Figma draws no frame that tells
  // them apart, so neither does this.
  return 'none';
}

export function AppliedPanel({
  availability,
  summary,
  fromVersion,
  toVersion,
  appliedAt,
  revertUntil,
  submitting = false,
  failure,
  onRevert,
  labels,
}: AppliedPanelProps) {
  const frame = frameFor(availability, submitting, failure !== undefined);
  if (frame === 'none') return null;

  const badge = {
    available: labels.badgeAvailable,
    submitting: labels.badgeSubmitting,
    failed: labels.badgeFailed,
    reverted: labels.badgeReverted,
    expired: labels.badgeExpired,
  }[frame];

  const revision = {
    available: labels.revisionAvailable,
    submitting: labels.revisionAvailable,
    // The revision line still describes what the apply did. The failure is
    // about the undo attempt, not about the revision, and it gets its own line
    // below rather than overwriting this one.
    failed: labels.revisionAvailable,
    reverted: labels.revisionReverted,
    expired: labels.revisionExpired,
  }[frame];

  return (
    <section
      aria-label={badge}
      className={styles.panel}
      data-state={frame}
      data-from-version={fromVersion}
      data-to-version={toVersion}
      data-applied-at={appliedAt}
      data-revert-until={revertUntil}
    >
      <p className={styles.resultRow}>
        <span className={styles.result}>{summary}</span>
        <span className={styles.badge} data-state={frame}>
          {badge}
        </span>
      </p>

      <p className={styles.revision}>{revision}</p>

      {/* The server's own words for what went wrong, verbatim and announced.
          `role="alert"` because the failure arrives after a press rather than
          on load: a traveller using a screen reader pressed 되돌리기 and needs
          to learn the schedule did NOT change without going hunting for it. */}
      {frame === 'failed' && failure ? (
        <p className={styles.failure} role="alert">
          {failure.message}
        </p>
      ) : null}

      {/* REVERTED alone has no button: there is nothing left to undo, and the
          frame drops to 62px because of it. EXPIRED keeps a disabled one that
          says where to go instead — a control that vanished would leave the
          traveller looking for an undo that used to be there.

          A failure keeps the control for the same reason, and whether it is
          pressable is the problem policy's answer rather than this file's:
          `retryable` false is REVERT_WINDOW_EXPIRED's `retry: 'none'`, where a
          press cannot succeed and offering one would be a lie. */}
      {frame === 'reverted' ? null : (
        <button
          className={styles.revert}
          disabled={frame === 'failed' ? !failure?.retryable : frame !== 'available'}
          onClick={onRevert}
          type="button"
        >
          {frame === 'expired'
            ? labels.expired
            : frame === 'submitting'
              ? labels.reverting
              : frame === 'failed'
                ? labels.retry
                : labels.revert}
        </button>
      )}
    </section>
  );
}
