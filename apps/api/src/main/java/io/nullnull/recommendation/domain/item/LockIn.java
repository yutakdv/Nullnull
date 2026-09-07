package io.nullnull.recommendation.domain.item;

import io.nullnull.trip.domain.ItemLock;
import io.nullnull.trip.domain.LockType;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Objects;

/**
 * Mirrors {@code LockIn}. The four lock types are independent and are never auto-released; each type
 * carries exactly its own fields, and the service rejects a lock that carries any other. The type is
 * the trip module's {@link LockType}, so the wire contract and the validator that trip mutations use
 * cannot drift apart.
 */
public record LockIn(LockType type, LocalDate date, LocalTime startTime, LocalTime endTime,
        Integer toleranceMinutes) {

    public LockIn {
        Objects.requireNonNull(type, "type");
    }

    public static LockIn mustVisit() {
        return new LockIn(LockType.MUST_VISIT, null, null, null, null);
    }

    public static LockIn date(LocalDate date) {
        return new LockIn(LockType.DATE, Objects.requireNonNull(date, "date"), null, null, null);
    }

    public static LockIn time(LocalTime startTime, int toleranceMinutes) {
        return new LockIn(LockType.TIME, null, Objects.requireNonNull(startTime, "startTime"), null, toleranceMinutes);
    }

    public static LockIn reservation(LocalDate date, LocalTime startTime, LocalTime endTime) {
        return new LockIn(LockType.RESERVATION, Objects.requireNonNull(date, "date"),
                Objects.requireNonNull(startTime, "startTime"), endTime, null);
    }

    /**
     * The trip-module lock this row stands for. A lock that carries a field belonging to another type
     * is rejected here exactly as the service rejects it, so a malformed lock can never be silently
     * evaluated as a weaker one.
     */
    public ItemLock toItemLock() {
        return switch (type) {
            case MUST_VISIT -> {
                requireAbsent(date == null && startTime == null && endTime == null && toleranceMinutes == null);
                yield new ItemLock.MustVisit();
            }
            case DATE -> {
                requireAbsent(startTime == null && endTime == null && toleranceMinutes == null);
                yield new ItemLock.Date(require(date, "date"));
            }
            case TIME -> {
                requireAbsent(date == null && endTime == null);
                yield new ItemLock.Time(require(startTime, "startTime"), require(toleranceMinutes, "toleranceMinutes"));
            }
            case RESERVATION -> {
                requireAbsent(toleranceMinutes == null);
                yield new ItemLock.Reservation(require(date, "date"), require(startTime, "startTime"), endTime);
            }
        };
    }

    private static void requireAbsent(boolean onlyOwnFields) {
        if (!onlyOwnFields) {
            throw new IllegalArgumentException("a lock carries exactly the fields of its own type");
        }
    }

    private static <T> T require(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " is required for this lock type");
        }
        return value;
    }
}
