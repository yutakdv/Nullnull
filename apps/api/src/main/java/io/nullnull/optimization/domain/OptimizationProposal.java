package io.nullnull.optimization.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One alternative the optimizer offers for a run, and the evidence for offering it.
 *
 * <p>Invariant 8 lives in the constructor as well as in the table: a crowd delta is a claim that
 * two measurements were comparable, so it may exist only when the pair was eligible, and a proposal
 * that was not eligible has to say why. Mirrored here so a proposal that the database would refuse
 * cannot be built in memory and travel half the call path first.
 *
 * <p>A proposal with no changes is not a proposal, which is why the contract sets minItems to 1 and
 * why this refuses an empty list: "here is an alternative, it changes nothing" is a sentence that
 * would render as an empty diff for a traveller to approve.
 */
public record OptimizationProposal(UUID id, UUID runId, int rank, String summary,
        boolean comparisonEligible, String comparisonReasonCode, BigDecimal crowdDelta,
        Integer travelMinutesDelta, UUID beforeSnapshotId, UUID afterSnapshotId, String validationSummary,
        Instant createdAt, List<OptimizationChange> changes) {

    /** The contract's own bound, kept so an unstorable summary is refused before the write. */
    public static final int MAX_SUMMARY_LENGTH = 500;

    public OptimizationProposal {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(validationSummary, "validationSummary");
        Objects.requireNonNull(createdAt, "createdAt");
        if (rank < 1) {
            throw new IllegalArgumentException("rank starts at 1");
        }
        String trimmed = summary.strip();
        if (trimmed.isEmpty() || trimmed.length() > MAX_SUMMARY_LENGTH) {
            throw new IllegalArgumentException("summary must be 1 to " + MAX_SUMMARY_LENGTH + " characters");
        }
        if (comparisonEligible) {
            if (comparisonReasonCode != null) {
                throw new IllegalArgumentException("an eligible comparison has no reason to refuse it");
            }
        } else {
            if (comparisonReasonCode == null) {
                throw new IllegalArgumentException("an ineligible comparison must name its reason");
            }
            if (crowdDelta != null) {
                throw new IllegalArgumentException("a crowd delta claims the pair was comparable");
            }
        }
        // V034's CHECK, mirrored: the pair it compared is recorded whole or - on a row written before
        // V034 - not at all. Half a pair names a comparison with one side missing.
        if ((beforeSnapshotId == null) != (afterSnapshotId == null)) {
            throw new IllegalArgumentException("a compared pair has both of its points or neither");
        }
        changes = List.copyOf(Objects.requireNonNull(changes, "changes"));
        if (changes.isEmpty()) {
            throw new IllegalArgumentException("a proposal that changes nothing is not a proposal");
        }
    }
}
