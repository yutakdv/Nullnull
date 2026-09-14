package io.nullnull.catalog.application;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * What an operator asks the opening-hours curation script to record, as written in the input file.
 *
 * <p>A-031 settled that P0's curated content is made by an operations script rather than a migration
 * or a writing endpoint, and A-032 settled that a human reading of opening hours is trusted for
 * P30D. This is that script's input. Every place is named by an id the catalog already has - the
 * plan cannot invent a place - and every reading names the page it was read from, because
 * {@code place_hours_observations} refuses a row that cannot be re-read.
 *
 * <p>What the file can say about not knowing is the part that matters. A day it does not mention
 * gets no window at all, which the evaluator reads as unverified; a place it mentions with
 * {@code AMBIGUOUS} records that someone looked and could not settle it. Those are different facts
 * and the file can state both, so a curator never has to choose between inventing a day and dropping
 * the place.
 */
public record CuratedHoursPlan(List<CuratedPlaceHours> places) {

    private static final Pattern HTTPS = Pattern.compile("^https://\\S+$");

    public CuratedHoursPlan {
        places = List.copyOf(Objects.requireNonNull(places, "places"));
        if (places.isEmpty()) {
            throw new CurationException("the plan lists no places");
        }
        if (places.stream().map(CuratedPlaceHours::placeId).distinct().count() != places.size()) {
            throw new CurationException("two entries in the plan name the same place");
        }
    }

    /**
     * One place's reading.
     *
     * <p>{@code observedAt} is when the curator read the page, not when the script runs: the staleness
     * clock starts at the reading, so running the file again next month must not make a month-old
     * reading look fresh.
     */
    public record CuratedPlaceHours(java.util.UUID placeId, String evidenceUrl, Instant observedAt,
            Outcome outcome, List<CuratedWindow> windows) {

        public CuratedPlaceHours {
            Objects.requireNonNull(placeId, "placeId");
            Objects.requireNonNull(observedAt, "observedAt");
            Objects.requireNonNull(outcome, "outcome");
            if (evidenceUrl == null || !HTTPS.matcher(evidenceUrl).matches()) {
                throw new CurationException("place " + placeId + " needs an https page it was read from");
            }
            windows = List.copyOf(Objects.requireNonNull(windows, "windows"));
            if (windows.stream().map(CuratedWindow::date).distinct().count() != windows.size()) {
                throw new CurationException("place " + placeId + " states two windows for one date");
            }
            // OBSERVED means the reading settled something, so it has to say what. Without this an
            // OBSERVED entry with no windows would be indistinguishable from AMBIGUOUS, and the
            // difference between "could not tell" and "told us nothing" would stop being writable.
            if (outcome == Outcome.OBSERVED && windows.isEmpty()) {
                throw new CurationException("place " + placeId + " is OBSERVED but states no window");
            }
            if (outcome != Outcome.OBSERVED && !windows.isEmpty()) {
                throw new CurationException("place " + placeId + " states windows under outcome " + outcome);
            }
        }
    }

    /** What the reading established about one date. Never "unknown": an unmentioned date is that. */
    public record CuratedWindow(LocalDate date, State state, LocalTime opensAt, LocalTime closesAt) {

        public CuratedWindow {
            Objects.requireNonNull(date, "date");
            Objects.requireNonNull(state, "state");
            if (state == State.CLOSED && (opensAt != null || closesAt != null)) {
                throw new CurationException("a closed day on " + date + " carries no times");
            }
            if (state == State.OPEN) {
                if (opensAt == null || closesAt == null) {
                    throw new CurationException("an open day on " + date + " needs both times");
                }
                // The same rule OpeningWindowIn enforces (D-REC-18): P0 has no overnight window, and
                // a file that states one would be rejected by the table halfway down the run.
                if (!closesAt.isAfter(opensAt)) {
                    throw new CurationException("on " + date + " closesAt must be after opensAt");
                }
            }
        }
    }

    /** The stored vocabulary, so a plan cannot record an outcome the table would refuse. */
    public enum Outcome { OBSERVED, NO_INFORMATION, AMBIGUOUS }

    /** OPEN or CLOSED only, matching place_hours_windows: absence is how the file says "unread". */
    public enum State { OPEN, CLOSED }

    /** Rejects the file, before the importer opens a transaction. */
    public static final class CurationException extends RuntimeException {
        public CurationException(String message) {
            super(message);
        }
    }
}
