package io.nullnull.live.domain;

import io.nullnull.crowd.domain.SourceState;
import java.util.Collection;
import java.util.List;

/**
 * The one {@code LiveAreaResult.mode} that stands for a whole page of areas.
 *
 * <p>The contract types the field as {@code SourceState} and says nothing else, and no other place in
 * this repository folds a set of states into one - so the rule is chosen here and this comment is why
 * it is the one it is.
 *
 * <p><strong>The weakest state present wins.</strong> A page labelled LIVE that contains one stale
 * area tells the reader every reading on it is current, and one of them is not. Invariant 6 is that
 * live, stale, replay and absent are distinguishable in the API and on screen; a page-level label that
 * rounds up erases that distinction for exactly the readings it matters for. Rounding down never
 * claims something untrue - it under-promises for the fresh areas, and each area still carries its own
 * state in its own {@code crowd.provenance}.
 *
 * <p>An empty page is UNAVAILABLE, not LIVE. "Nothing to report" is not a live reading, and the
 * alternative would make a working server and a silent provider look the same.
 *
 * <p>FORECAST and QUALITATIVE are absent from the ranking because this path cannot produce them: this
 * is the area list, which serves current observations, and a forecast point is a different question
 * with a targetAt. If a later slice serves them here, it adds them to this list deliberately rather
 * than discovering that {@code indexOf} returned -1.
 */
public final class LiveResultMode {

    /** Weakest first. The first state present in a page is that page's mode. */
    private static final List<SourceState> WEAKEST_FIRST = List.of(
            SourceState.UNAVAILABLE, SourceState.STALE, SourceState.REPLAY, SourceState.LIVE);

    private LiveResultMode() {
    }

    public static SourceState of(Collection<SourceState> areaStates) {
        if (areaStates == null || areaStates.isEmpty()) {
            return SourceState.UNAVAILABLE;
        }
        for (SourceState candidate : WEAKEST_FIRST) {
            if (areaStates.contains(candidate)) {
                return candidate;
            }
        }
        // A state this path is not supposed to serve reached the page. Answering UNAVAILABLE is the
        // only safe fold: guessing where it ranks would be inventing an ordering nobody reviewed.
        return SourceState.UNAVAILABLE;
    }
}
