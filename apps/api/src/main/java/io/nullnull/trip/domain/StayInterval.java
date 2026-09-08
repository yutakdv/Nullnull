package io.nullnull.trip.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Objects;

/**
 * The one place that adds a stay length to a start time. Every caller needs the same answer to the
 * same question — where does a stay that may run past midnight end — and a second copy of that
 * arithmetic is how the lock check, the opening window and the neighbour overlap start disagreeing.
 * It lives in the trip module because the lock validator shares it with the recommendation
 * re-validation, and mirrors {@code apps/ai/src/nullnull_ai/item/filters.py::_add} and {@code ::_stay}
 * one for one.
 *
 * <p>Any fixed date works: a stay is measured inside one calendar day, never across one.
 */
public final class StayInterval {

    private static final LocalDate EPOCH = LocalDate.of(2000, 1, 1);

    private static final LocalDateTime DAY_END = LocalDateTime.of(EPOCH.plusDays(1), LocalTime.MIDNIGHT);

    /**
     * Mirrors {@code _add}: where the stay ends as a time of day, and whether it got there by leaving
     * its own date. A caller that only reads {@code time} would see a stay ending at 01:00 the next day
     * as a stay ending at 01:00 in the morning.
     */
    public record End(LocalTime time, boolean wrappedPastMidnight) {

        public End {
            Objects.requireNonNull(time, "time");
        }
    }

    /**
     * Mirrors {@code _stay}: the stay as a half-open interval on its own date. Comparing datetimes
     * instead of times keeps a stay that runs past midnight from folding back into the morning; for
     * overlap purposes such a stay occupies {@code [start, 24:00)} of the date it starts on.
     */
    public record Stay(LocalDateTime beginsAt, LocalDateTime endsAt) {

        public Stay {
            Objects.requireNonNull(beginsAt, "beginsAt");
            Objects.requireNonNull(endsAt, "endsAt");
        }
    }

    private StayInterval() {
    }

    /** The end of the stay, reported together with the wrap so neither caller can drop it. */
    public static End endOf(LocalTime start, int durationMinutes) {
        LocalDateTime end = LocalDateTime.of(EPOCH, Objects.requireNonNull(start, "start")).plusMinutes(durationMinutes);
        return new End(end.toLocalTime(), !end.toLocalDate().equals(EPOCH));
    }

    /** The stay as an interval, clamped to the end of its own date rather than rejected. */
    public static Stay of(LocalTime start, int durationMinutes) {
        LocalDateTime beginsAt = LocalDateTime.of(EPOCH, Objects.requireNonNull(start, "start"));
        LocalDateTime end = beginsAt.plusMinutes(durationMinutes);
        return new Stay(beginsAt, end.isAfter(DAY_END) ? DAY_END : end);
    }
}
