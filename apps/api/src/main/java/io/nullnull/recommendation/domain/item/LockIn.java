package io.nullnull.recommendation.domain.item;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Objects;

/**
 * Mirrors {@code LockIn}. The four lock types are independent and are never auto-released; each type
 * carries exactly its own fields, and the service rejects a lock that carries any other.
 */
public record LockIn(LockType type, LocalDate date, LocalTime startTime, LocalTime endTime,
        Integer toleranceMinutes) {

    public enum LockType { MUST_VISIT, DATE, TIME, RESERVATION }

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
}
