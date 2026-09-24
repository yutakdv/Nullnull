import type { components } from '@nullnull/api-client';
import {
  DataAttribution,
  MetricDelta,
  PlaceAttribution,
  sourceContext,
  unitCredits,
} from '../../shared/ui/index.js';
import {
  changeRows,
  crowdComparison,
  moveKind,
  type ChangeRow,
  type ProposalPlaces,
} from './preview.js';
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
  /**
   * Whether this card is the group's one Tab stop (roving tabindex). The
   * screen moves focus between cards with the arrow keys (FE-503-T3).
   */
  tabbable?: boolean;
  /**
   * The places the changes name, from the trip the screen holds
   * (`proposalPlaces`). Null while that trip is still loading.
   */
  places?: ProposalPlaces | null;
  /** Localized copy from the caller; the shared components keep Korean defaults. */
  labels: {
    crowdLabel: string;
    /**
     * "감소"/"증가", read after the figure by a screen reader (#279 하2).
     *
     * Not decoration: the arrow is `aria-hidden` and the figure lost its sign,
     * so these two words are the whole of the direction for anyone not seeing
     * the glyph. `unchanged` needs none — there is no direction to name.
     */
    crowdDown: string;
    crowdUp: string;
    /** Shown when the comparison is blocked. One sentence for every reason. */
    comparisonUnavailable: string;
    changesTitle: string;
    /** `{count}` is replaced with the number of changes. */
    changeCount: string;
    /**
     * One per kind of move (#279 하3). A single `move` label said "시간 변경"
     * for a change of day; which one a row gets is decided by `moveKind` from
     * the two sides, not by the server's `operation`.
     */
    moveDate: string;
    moveTime: string;
    moveOrder: string;
    add: string;
    remove: string;
    constraintsOk: string;
    constraintsBroken: string;
    licenseTerms: string;
    /** Shown while the trip that supplies the named places' credits loads. */
    placeCreditPending: string;
    /** Shown when none of the named places has a credit the card can draw. */
    placeCreditMissingAll: string;
    /** Shown when some of the named places have one and some do not. */
    placeCreditMissingSome: string;
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
  labels: Pick<
    ProposalCardProps['labels'],
    'moveDate' | 'moveTime' | 'moveOrder' | 'add' | 'remove'
  >;
}) {
  return (
    <ul className={styles.changes}>
      {rows.map((row) => {
        // A move names the field that moved; add and remove name themselves.
        // `data-kind` stays the row's shape, because the CSS styles the three
        // shapes and a move looks the same whichever field it moved.
        const kind = row.kind === 'move' ? moveKind(row.before, row.after) : row.kind;
        return (
          <li className={styles.change} key={`${row.kind}-${row.itemId}`}>
            <span className={styles.changeKind} data-kind={row.kind}>
              {labels[kind]}
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
        );
      })}
    </ul>
  );
}

export function ProposalCard({
  proposal,
  selected,
  onSelect,
  labels,
  places = null,
  tabbable = false,
}: ProposalCardProps) {
  const rows = changeRows(proposal.changes);
  const comparison = crowdComparison(proposal);
  const failed = proposal.validation.checks.filter(
    (check: ValidationCheck) => !check.passed,
  );
  const constraintsPreserved =
    proposal.validation.allConstraintsPreserved && failed.length === 0;
  const selectable = onSelect !== undefined;
  // A selectable card is a radio inside the screen's radiogroup, and a
  // radiogroup must own its radios directly — an `<article>` between them
  // would stand in the way. Only the single, non-selectable card is an article.
  const Card = selectable ? 'div' : 'article';

  return (
    <Card
      className={selected === true ? `${styles.card} ${styles.selected}` : styles.card}
    >
      {/* `role="radio"` and not a checkbox or a plain button: the run takes ONE
          decision, so the proposals are mutually exclusive and a screen reader
          should say "1 of 3" rather than announce three independent toggles.
          The screen owns the surrounding `radiogroup`.

          The attributes appear only when selecting is possible — see
          `selected` above.

          The radio holds what the proposal SAYS and nothing that links out: a
          radio's children are presentational, so a credit link inside it is no
          link to a screen reader, and the radio takes its Enter, Space and
          click. The credits are its sibling below (FE-603-T11). */}
      <div
        aria-checked={selectable ? selected === true : undefined}
        className={styles.choice}
        onClick={selectable ? () => onSelect(proposal.id) : undefined}
        onKeyDown={
          selectable
            ? (event) => {
                // Space and Enter, which is what a radio answers to. Without
                // this the card is reachable by Tab and does nothing when
                // pressed, which is worse than not being focusable at all.
                if (event.key !== ' ' && event.key !== 'Enter') return;
                event.preventDefault();
                onSelect(proposal.id);
              }
            : undefined
        }
        role={selectable ? 'radio' : undefined}
        tabIndex={selectable ? (tabbable ? 0 : -1) : undefined}
      >
        {/* The server's sentence, verbatim. It is generated from the change
            set it describes, so rewriting or truncating it here would state
            something the optimizer did not. */}
        <p className={styles.summary}>{proposal.summary}</p>

        <section aria-label={labels.changesTitle} className={styles.section}>
          <h3 className={styles.sectionTitle}>
            {labels.changeCount.replace('{count}', String(rows.length))}
          </h3>
          <ChangeList labels={labels} rows={rows} />
        </section>

        {/* Invariant 8, held structurally.

            `crowdComparison` returns either a delta WITH its provenance or a
            blocked reason with no delta to reach for. The figure here and its
            credit in the credits below both come off that one value and the
            same `shown` test; there is no expression that can produce the
            number without the provenance that has to be printed with it.

            The alternative — reading `proposal.metrics.crowdDelta` and checking
            a boolean beside it — is what lets a later edit keep the figure and
            lose the credit. That is why this file never touches
            `proposal.metrics`. */}
        {comparison.kind === 'shown' ? (
          <div className={styles.section}>
            {/* Arrow, word and figure all come off the same sign, read once.
                The word is what a screen reader gets in place of the arrow, so
                a second reading of `delta` here could put them out of step —
                the glyph saying down while the announcement said up. */}
            <MetricDelta
              direction={deltaDirection(comparison.delta)}
              directionLabel={
                comparison.delta < 0
                  ? labels.crowdDown
                  : comparison.delta > 0
                    ? labels.crowdUp
                    : undefined
              }
              eligible
              label={labels.crowdLabel}
              value={formatDelta(comparison.delta)}
            />
          </div>
        ) : (
          <div className={styles.section}>
            {/* One sentence for every blocked reason, and the server's code is
                never shown.

                `comparisonReasonCode` is a free-form string in the contract —
                no enum, maxLength 100 — and the fixtures already carry three
                different values (SAME_METRIC_AND_ISSUE, SAME_SOURCE_SCOPE_SET,
                MISSING_PROVENANCE). A key built from the code would render the
                literal token for any value added after this build shipped,
                which is the bug `failureMessage` in OptimizationRunScreen
                measured as "[undefined]" on screen. ReplaceSheet made the same
                call for the same reason: it maps to fixed copy rather than
                echoing the code.

                The distinction the user needs is "we cannot compare these",
                not which of the server's internal predicates said so.

                `reason` is always passed, so MetricDelta's own Korean fallback
                (`reason ?? '확인 불가'`) is unreachable FROM THIS SCREEN. That
                is a property of this call site, not of the component — another
                caller that omits `reason` would still hit it. */}
            <MetricDelta
              eligible={false}
              label={labels.crowdLabel}
              reason={labels.comparisonUnavailable}
            />
          </div>
        )}

        <p className={styles.validation} data-ok={constraintsPreserved}>
          {constraintsPreserved ? labels.constraintsOk : labels.constraintsBroken}
        </p>
      </div>

      {/* CMP-ATT-001: the summary names places, so the card credits them — in
          both comparison branches, not only beside a figure. While the trip
          that supplies them loads, and for any named place it cannot supply,
          the card says so rather than showing the name with nothing under it
          (`proposalPlaces`). The forecast's credit follows, only when its
          figure is shown; reading the same words as a KTO place credit, it
          names its source beside it (FE-603-T7). */}
      <div className={styles.credits}>
        <PlaceAttribution compact place={places?.places ?? []} />
        {places === null ? (
          <p className={styles.creditNote}>{labels.placeCreditPending}</p>
        ) : places.missing > 0 ? (
          <p className={styles.creditNote}>
            {places.places.length === 0
              ? labels.placeCreditMissingAll
              : labels.placeCreditMissingSome}
          </p>
        ) : null}
        {comparison.kind === 'shown' ? (
          <DataAttribution
            compact
            context={sourceContext(
              comparison.provenance,
              unitCredits(places?.places ?? []),
              true,
            )}
            provenance={comparison.provenance}
            showLicense
            termsLabel={labels.licenseTerms}
          />
        ) : null}
      </div>
    </Card>
  );
}

/**
 * The delta as a magnitude. The direction is the arrow's job, and the
 * `directionLabel`'s.
 *
 * THIS USED TO BE SIGNED, and the reason it gave was:
 *
 *   "Signed rather than an absolute value with a word: the arrow beside it says
 *    the direction, and a bare 47 next to a down arrow reads as 47 less only if
 *    you already know the unit."
 *
 * That sentence names the arrow as the thing that carries direction and then
 * adds a sign that carries it again — a rehearsal read the result as "↓ -55"
 * and reported the two as colliding (#279 하2). The premise was right and the
 * conclusion did not follow from it. It is quoted rather than deleted because
 * the half that IS right still binds: something has to say the direction, so
 * dropping the sign is only safe together with the word that replaces it.
 *
 * That word is `directionLabel` on MetricDelta. The arrow is `aria-hidden`, so
 * before this change the minus sign was the only direction a screen reader
 * got; removing it alone would have taken direction away from exactly the
 * readers who could not see the arrow. The two edits are one change.
 */
function formatDelta(delta: number): string {
  return String(Math.abs(delta));
}

/**
 * Which way the figure moved, for the arrow and for the announced word.
 *
 * A lower crowd reading is an improvement, which is why a negative delta is
 * `improved` rather than `worsened` — the sign is about the metric, the name is
 * about the traveller.
 */
function deltaDirection(delta: number): 'improved' | 'worsened' | 'unchanged' {
  if (delta < 0) return 'improved';
  if (delta > 0) return 'worsened';
  return 'unchanged';
}
