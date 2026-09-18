import type { components } from '@nullnull/api-client';
import { DataAttribution, MetricDelta } from '../../shared/ui/index.js';
import { changeRows, crowdComparison, type ChangeRow } from './preview.js';
import styles from './ProposalCard.module.css';

// One proposal from a READY optimization run (S12, FE-503, FR-OPT-04).
//
// Invariant 3 governs what this is: a preview. Nothing here mutates the trip,
// and the card deliberately carries no apply control — the decision belongs to
// the bar above it, which is a later slice.
//
// Invariant 8 governs what it may SHOW, and that rule is held by the shape of
// `crowdComparison()` rather than by conditions in this file. See `renderCrowd`.

type OptimizationProposal = components['schemas']['OptimizationProposal'];
type TripItemState = components['schemas']['TripItemState'];
type ValidationCheck = OptimizationProposal['validation']['checks'][number];

export interface ProposalCardProps {
  proposal: OptimizationProposal;
  /**
   * Whether the decision bar acts on this proposal.
   *
   * Omitted when there is nothing to choose between. A single proposal rendered
   * as a selectable control tells the user a choice exists and then offers one
   * option, which reads as something missing rather than as the only answer —
   * so the screen passes neither this nor `onSelect` in that case, and the card
   * renders as plain content.
   */
  selected?: boolean;
  /** Present only when selecting is possible. See `selected`. */
  onSelect?: (proposalId: string) => void;
  /** Localized copy from the caller; the shared components keep Korean defaults. */
  labels: {
    crowdLabel: string;
    /** Shown when the comparison is blocked. One sentence for every reason. */
    comparisonUnavailable: string;
    changesTitle: string;
    /** `{count}` is replaced with the number of changes. */
    changeCount: string;
    move: string;
    add: string;
    remove: string;
    constraintsOk: string;
    constraintsBroken: string;
    licenseTerms: string;
  };
}

/** `13:00:00` → `13:00`. The seconds are always zero and cost width on a card. */
function clockTime(startTime: string | null | undefined): string | null {
  if (!startTime) return null;
  const [hours, minutes] = startTime.split(':');
  return hours && minutes ? `${hours}:${minutes}` : startTime;
}

/** `2026-10-04` + `13:00:00` → `10/4 13:00`, or just the date when untimed. */
function whenLabel(state: TripItemState): string {
  const [, month, day] = state.date.split('-');
  const date =
    month && day ? `${String(Number(month))}/${String(Number(day))}` : state.date;
  const time = clockTime(state.startTime);
  return time ? `${date} ${time}` : date;
}

function ChangeList({
  rows,
  labels,
}: {
  rows: ChangeRow[];
  labels: Pick<ProposalCardProps['labels'], 'move' | 'add' | 'remove'>;
}) {
  return (
    <ul className={styles.changes}>
      {rows.map((row) => (
        <li className={styles.change} key={`${row.kind}-${row.itemId}`}>
          <span className={styles.changeKind} data-kind={row.kind}>
            {labels[row.kind]}
          </span>
          {/* Each row draws only the sides it has. `changeRows` already dropped
              the nulls the contract fixes for ADD and REMOVE, so there is no
              `before ?? '—'` here inventing a value for a side that does not
              exist. */}
          {row.kind === 'move' ? (
            <span className={styles.changeWhen}>
              <span className={styles.before}>{whenLabel(row.before)}</span>
              <span aria-hidden="true">→</span>
              <span className={styles.after}>{whenLabel(row.after)}</span>
            </span>
          ) : row.kind === 'add' ? (
            <span className={styles.changeWhen}>
              <span className={styles.after}>{whenLabel(row.after)}</span>
            </span>
          ) : (
            <span className={styles.changeWhen}>
              <span className={styles.before}>{whenLabel(row.before)}</span>
            </span>
          )}
        </li>
      ))}
    </ul>
  );
}

export function ProposalCard({
  proposal,
  selected,
  onSelect,
  labels,
}: ProposalCardProps) {
  const rows = changeRows(proposal.changes);
  const comparison = crowdComparison(proposal);
  const failed = proposal.validation.checks.filter(
    (check: ValidationCheck) => !check.passed,
  );
  const selectable = onSelect !== undefined;

  return (
    // `role="radio"` and not a checkbox or a plain button: the run takes ONE
    // decision, so the proposals are mutually exclusive and a screen reader
    // should say "1 of 3" rather than announce three independent toggles. The
    // screen owns the surrounding `radiogroup`.
    //
    // The attributes appear only when selecting is possible. A non-selectable
    // card is an `<article>` with no role and no tabindex, which is what the
    // single-proposal case needs — see `selected` above.
    <article
      aria-checked={selectable ? selected === true : undefined}
      className={selected === true ? `${styles.card} ${styles.selected}` : styles.card}
      onClick={selectable ? () => onSelect(proposal.id) : undefined}
      onKeyDown={
        selectable
          ? (event) => {
              // Space and Enter, which is what a radio answers to. Without this
              // the card is reachable by Tab and does nothing when pressed,
              // which is worse than not being focusable at all.
              if (event.key !== ' ' && event.key !== 'Enter') return;
              event.preventDefault();
              onSelect(proposal.id);
            }
          : undefined
      }
      role={selectable ? 'radio' : undefined}
      tabIndex={selectable ? 0 : undefined}
    >
      {/* The server's sentence, verbatim. It is generated from the change set
          it describes, so rewriting or truncating it here would state something
          the optimizer did not. */}
      <p className={styles.summary}>{proposal.summary}</p>

      <section aria-label={labels.changesTitle} className={styles.section}>
        <h3 className={styles.sectionTitle}>
          {labels.changeCount.replace('{count}', String(rows.length))}
        </h3>
        <ChangeList labels={labels} rows={rows} />
      </section>

      {/* Invariant 8, held structurally.

          `crowdComparison` returns either a delta WITH its provenance or a
          blocked reason with no delta to reach for. The `shown` branch renders
          both together and there is no expression here that can produce the
          number on its own — dropping the attribution would mean deleting a
          variable the same branch destructured, not quietly omitting a line.

          The alternative — reading `proposal.metrics.crowdDelta` and checking a
          boolean beside it — is what lets a later edit keep the figure and lose
          the credit. That is why this file never touches `proposal.metrics`. */}
      {comparison.kind === 'shown' ? (
        <div className={styles.section}>
          <MetricDelta
            direction={
              comparison.delta < 0
                ? 'improved'
                : comparison.delta > 0
                  ? 'worsened'
                  : 'unchanged'
            }
            eligible
            label={labels.crowdLabel}
            value={formatDelta(comparison.delta)}
          />
          <DataAttribution
            compact
            provenance={comparison.provenance}
            showLicense
            termsLabel={labels.licenseTerms}
          />
        </div>
      ) : (
        <div className={styles.section}>
          {/* One sentence for every blocked reason, and the server's code is
              never shown.

              `comparisonReasonCode` is a free-form string in the contract — no
              enum, maxLength 100 — and the fixtures already carry three
              different values (SAME_METRIC_AND_ISSUE, SAME_SOURCE_SCOPE_SET,
              MISSING_PROVENANCE). A key built from the code would render the
              literal token for any value added after this build shipped, which
              is the bug `failureMessage` in OptimizationRunScreen measured as
              "[undefined]" on screen. ReplaceSheet made the same call for the
              same reason: it maps to fixed copy rather than echoing the code.

              The distinction the user needs is "we cannot compare these", not
              which of the server's internal predicates said so.

              `reason` is always passed, so MetricDelta's own Korean fallback
              (`reason ?? '확인 불가'`) is unreachable FROM THIS SCREEN. That is
              a property of this call site, not of the component — another
              caller that omits `reason` would still hit it. */}
          <MetricDelta
            eligible={false}
            label={labels.crowdLabel}
            reason={labels.comparisonUnavailable}
          />
        </div>
      )}

      <p className={styles.validation} data-ok={failed.length === 0}>
        {failed.length === 0 ? labels.constraintsOk : labels.constraintsBroken}
      </p>
    </article>
  );
}

/**
 * The delta as a signed figure.
 *
 * Signed rather than an absolute value with a word: the arrow beside it says
 * the direction, and a bare "47" next to a down arrow reads as "47 less" only
 * if you already know the unit. Zero keeps no sign.
 */
function formatDelta(delta: number): string {
  if (delta > 0) return `+${String(delta)}`;
  return String(delta);
}
