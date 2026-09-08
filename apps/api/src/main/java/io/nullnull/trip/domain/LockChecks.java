package io.nullnull.trip.domain;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Evaluates every lock independently and reports each verdict, so a validation summary can list all
 * constraint checks. Manual edits, replacements and the APPLY re-validation share this validator with
 * the optimizer, which is why it lives in the trip module and knows nothing about recommendations.
 *
 * <p>Locks are evaluated in the fixed order MUST_VISIT → DATE → TIME → RESERVATION regardless of the
 * order the caller supplied, because callers read {@code reasonCodes.get(0)} as <em>the</em> reason a
 * slot was refused: with the caller's order, one slot breaking two locks would report a different
 * code depending on how the trip happened to store them. This mirrors
 * {@code apps/ai/src/nullnull_ai/item/filters.py::lock_checks} exactly.
 *
 * <p>MUST_VISIT always passes here because a temporal move keeps the place. RESERVATION pins the date
 * and the start time (conservative reading of "예약 날짜·시간 범위 유지", D-REC-8); a known stay must
 * also end inside the reservation window without running past midnight.
 */
public final class LockChecks {

    /** Any fixed date works: the stay end is compared on a calendar day, never across one. */
    private static final LocalDate EPOCH = LocalDate.of(2000, 1, 1);

    public static final String DATE_LOCKED = "DATE_LOCKED";
    public static final String TIME_LOCKED = "TIME_LOCKED";
    public static final String RESERVATION_LOCKED = "RESERVATION_LOCKED";

    /**
     * @param passed      one entry per supplied lock, iterated in the fixed lock order
     * @param reasonCodes the codes of the failing locks, in the same fixed order
     */
    public record Result(Map<LockType, Boolean> passed, List<String> reasonCodes) {

        public Result {
            Objects.requireNonNull(passed, "passed");
            Map<LockType, Boolean> ordered = new EnumMap<>(LockType.class);
            ordered.putAll(passed);
            passed = Collections.unmodifiableMap(ordered);
            reasonCodes = List.copyOf(Objects.requireNonNull(reasonCodes, "reasonCodes"));
        }

        public boolean satisfied() {
            return reasonCodes.isEmpty();
        }
    }

    private LockChecks() {
    }

    /** The internal reason code a failing lock of this type reports. */
    public static String reasonCodeOf(LockType type) {
        return switch (type) {
            case DATE -> DATE_LOCKED;
            case TIME -> TIME_LOCKED;
            case RESERVATION -> RESERVATION_LOCKED;
            case MUST_VISIT -> throw new IllegalArgumentException("MUST_VISIT cannot fail a temporal move");
        };
    }

    /**
     * @param locks           at most one lock per type, in any order
     * @param proposedDate    the date the item would move to
     * @param proposedTime    the local start time it would take, or null when it stays untimed
     * @param durationMinutes the item's verified stay length, or null when it is unknown
     */
    public static Result evaluate(List<ItemLock> locks, LocalDate proposedDate, LocalTime proposedTime,
            Integer durationMinutes) {
        Objects.requireNonNull(locks, "locks");
        Objects.requireNonNull(proposedDate, "proposedDate");
        Map<LockType, ItemLock> byType = new EnumMap<>(LockType.class);
        for (ItemLock lock : locks) {
            if (byType.put(lock.type(), lock) != null) {
                throw new IllegalArgumentException("at most one lock per type");
            }
        }
        Map<LockType, Boolean> passed = new EnumMap<>(LockType.class);
        List<String> reasonCodes = new ArrayList<>();
        // EnumMap iterates in declaration order, which is the fixed evaluation order.
        byType.forEach((type, lock) -> {
            boolean ok = isSatisfied(lock, proposedDate, proposedTime, durationMinutes);
            passed.put(type, ok);
            if (!ok) {
                reasonCodes.add(reasonCodeOf(type));
            }
        });
        return new Result(passed, reasonCodes);
    }

    private static boolean isSatisfied(ItemLock lock, LocalDate proposedDate, LocalTime proposedTime,
            Integer durationMinutes) {
        return switch (lock) {
            case ItemLock.MustVisit ignored -> true;
            case ItemLock.Date date -> date.date().equals(proposedDate);
            case ItemLock.Time time -> proposedTime != null
                    && minutesBetween(time.startTime(), proposedTime) <= time.toleranceMinutes();
            case ItemLock.Reservation reservation -> reservation.date().equals(proposedDate)
                    && reservation.startTime().equals(proposedTime)
                    && stayFits(reservation, proposedTime, durationMinutes);
        };
    }

    /** Whole minutes apart, truncated toward zero so the tolerance stays symmetric around the lock. */
    private static long minutesBetween(LocalTime a, LocalTime b) {
        return Math.abs(Duration.between(a, b).toSeconds()) / 60;
    }

    /**
     * A known stay must end inside the window; an unknown one is judged by the duration filter instead.
     * The end is computed on a calendar day so a stay that runs past midnight is seen as wrapping
     * rather than folding back into the morning.
     */
    private static boolean stayFits(ItemLock.Reservation reservation, LocalTime proposedTime, Integer durationMinutes) {
        if (reservation.endTime() == null || durationMinutes == null || proposedTime == null) {
            return true;
        }
        LocalDateTime end = LocalDateTime.of(EPOCH, proposedTime).plusMinutes(durationMinutes);
        boolean wrapped = !end.toLocalDate().equals(EPOCH);
        return !wrapped && !end.toLocalTime().isAfter(reservation.endTime());
    }
}
