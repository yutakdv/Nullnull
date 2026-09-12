package io.nullnull.trip.domain;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A trip's inclusive local date range and its timezone, validated once so nothing downstream has to
 * re-derive the rules.
 *
 * <p>The days a trip shows are DERIVED from this range rather than stored. That is what makes the
 * created trip deterministic: one day per calendar date, in order, every time, with no stored list
 * that can fall out of step with the range after an edit.
 *
 * <p>Dates here are local calendar dates, not instants. A timezone with a DST transition inside the
 * range therefore adds or removes no days - the 2026-03-08 that loses an hour in America/New_York is
 * still exactly one date. Storing instants would have made day count depend on the offset, which is
 * the bug this shape avoids rather than handles.
 */
public record TripDateRange(LocalDate startDate, LocalDate endDate, ZoneId timezone) {

    /** Inclusive, so a 30-day trip spans start..start+29. createTrip documents this bound. */
    public static final int MAX_DAYS = 30;

    public TripDateRange {
        Objects.requireNonNull(startDate, "startDate");
        Objects.requireNonNull(endDate, "endDate");
        Objects.requireNonNull(timezone, "timezone");
        if (endDate.isBefore(startDate)) {
            throw new TripValidationException("endDate", "DateRangeReversed",
                    "endDate must not be before startDate");
        }
        if (dayCount(startDate, endDate) > MAX_DAYS) {
            throw new TripValidationException("endDate", "DateRangeTooLong",
                    "the range must not exceed " + MAX_DAYS + " calendar days");
        }
    }

    /**
     * Parses the wire form. A timezone the JVM does not know is a rejected request rather than a
     * failure later: deriving days, and every future instant, needs a zone that actually resolves.
     */
    public static TripDateRange of(LocalDate startDate, LocalDate endDate, String timezone) {
        return new TripDateRange(startDate, endDate, zone(timezone));
    }

    public static ZoneId zone(String timezone) {
        if (timezone == null || timezone.isBlank()) {
            throw new TripValidationException("timezone", "NotBlank", "timezone is required");
        }
        try {
            return ZoneId.of(timezone);
        } catch (java.time.DateTimeException unknown) {
            // The message names the field, never the rejected value: docs/api/README.md keeps
            // provider and client input out of Problem text.
            throw new TripValidationException("timezone", "UnknownTimeZone",
                    "timezone must be a known IANA zone id");
        }
    }

    /** Every calendar date in the range, ascending. Never empty: start == end is one day. */
    public List<LocalDate> days() {
        List<LocalDate> days = new ArrayList<>(dayCount(startDate, endDate));
        for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
            days.add(date);
        }
        return List.copyOf(days);
    }

    public int dayCount() {
        return dayCount(startDate, endDate);
    }

    public boolean contains(LocalDate date) {
        return date != null && !date.isBefore(startDate) && !date.isAfter(endDate);
    }

    private static int dayCount(LocalDate startDate, LocalDate endDate) {
        long span = endDate.toEpochDay() - startDate.toEpochDay() + 1;
        // Bounded by MAX_DAYS above; the cast is safe only because the caller checks that first.
        return (int) Math.min(span, Integer.MAX_VALUE);
    }
}
