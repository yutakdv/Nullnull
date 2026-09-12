package io.nullnull.trip.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What a trip's items allow the trip itself to become.
 *
 * <p>The one rule here is the date-range shrink: ERD §11 and the UpdateTripRequest description both
 * say a shrink is REFUSED ENTIRELY while any item, or any DATE/RESERVATION lock, would fall outside
 * the new range. Nothing is moved and nothing is deleted to make room - a lock the user placed is
 * not something the server may quietly step around (invariant 7), and a partial shrink would leave
 * the trip in a state the user never asked for.
 */
public final class TripScheduleRules {

    private TripScheduleRules() {
    }

    /**
     * @return every reason the range cannot become {@code range}, empty when it can. All of them,
     *         not the first: a caller fixing one date at a time would otherwise need a round trip
     *         per conflicting item.
     */
    public static List<TripValidationException.FieldViolation> shrinkConflicts(
            TripDateRange range, List<TripItem> items) {
        List<TripValidationException.FieldViolation> violations = new ArrayList<>();
        if (items == null || items.isEmpty()) {
            return violations;
        }
        List<TripItem> outside = items.stream().filter(item -> !range.contains(item.date())).toList();
        if (!outside.isEmpty()) {
            violations.add(new TripValidationException.FieldViolation("endDate", "ItemOutsideRange",
                    outside.size() + " scheduled item(s) fall outside the new date range"));
        }
        List<TripItem> pinned = items.stream()
                .filter(TripItem::pinsADate)
                .filter(item -> !range.contains(item.pinnedDate()))
                .toList();
        if (!pinned.isEmpty()) {
            // Reported separately: an item merely sitting outside could be moved by the user, while a
            // DATE or RESERVATION lock is a commitment - a booking - that the range must accommodate.
            violations.add(new TripValidationException.FieldViolation("endDate", "LockedDateOutsideRange",
                    pinned.size() + " item(s) hold a DATE or RESERVATION lock outside the new range"));
        }
        return violations;
    }

    /** The contract's per-day and per-trip caps, checked together so one answer covers both. */
    public static void requireWithinCaps(List<TripItem> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        if (items.size() > TripItem.MAX_PER_TRIP) {
            throw new TripValidationException("seedItems", "Size",
                    "a trip holds at most " + TripItem.MAX_PER_TRIP + " items");
        }
        Map<LocalDate, Integer> perDay = new HashMap<>();
        for (TripItem item : items) {
            int count = perDay.merge(item.date(), 1, Integer::sum);
            if (count > TripItem.MAX_PER_DAY) {
                throw new TripValidationException("seedItems", "Size",
                        "a day holds at most " + TripItem.MAX_PER_DAY + " items");
            }
        }
    }

    /**
     * Positions must be unique within a day. Gaps are allowed - the contract types position as an
     * ordinal, not an index - but two items claiming the same slot is an order the client cannot
     * render deterministically.
     */
    public static void requireDistinctPositions(List<TripItem> items) {
        if (items == null) {
            return;
        }
        Map<LocalDate, List<Integer>> perDay = new HashMap<>();
        for (TripItem item : items) {
            List<Integer> positions = perDay.computeIfAbsent(item.date(), key -> new ArrayList<>());
            if (positions.contains(item.position())) {
                throw new TripValidationException("seedItems[].position", "Duplicate",
                        "two items claim the same position on one day");
            }
            positions.add(item.position());
        }
    }

    /** Every seeded item must sit inside the trip's own range. */
    public static void requireInsideRange(TripDateRange range, List<TripItem> items) {
        if (items == null) {
            return;
        }
        for (TripItem item : items) {
            if (!range.contains(item.date())) {
                throw new TripValidationException("seedItems[].date", "OutsideRange",
                        "a seeded item falls outside the trip date range");
            }
        }
    }
}
