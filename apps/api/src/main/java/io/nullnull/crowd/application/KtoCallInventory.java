package io.nullnull.crowd.application;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * The KTO operations one release actually called and got a usable answer from, read from the
 * call-audit (CMP-KTO-002, CMP-KTO-006). The submission's API list has to equal this set
 * (docs/contest/SUBMISSION_RUNBOOK.md), and until this existed that set was assembled by hand.
 *
 * <p>What counts is what {@code scripts/check_actual_call_evidence.py} counts, so the two cannot
 * disagree about what "actually used" means: a call of this release only, answered OK and validated
 * OK, from a KTO source, and not a replay. Rejected calls and replays are counted separately rather
 * than dropped silently - a release whose calls were all rejected must read as that, not as empty.
 */
public record KtoCallInventory(String release, List<Operation> operations, int rejectedCalls,
        int replayCalls) {

    public KtoCallInventory {
        Objects.requireNonNull(release, "release");
        operations = List.copyOf(operations);
    }

    /** One endpoint of one KTO source that answered usably, with how often and when. */
    public record Operation(String sourceCode, String endpointKey, int calls, Instant firstCalledAt,
            Instant lastCalledAt) {
    }
}
