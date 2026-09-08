package io.nullnull.trip.domain;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Objects;

/**
 * One lock a user placed on a trip item. Presence of a lock means {@code locked = true}; each type
 * carries exactly its own fields (docs/api/README.md §8).
 */
public sealed interface ItemLock {

    LockType type();

    /** The place must stay in the trip; a temporal move keeps it, so this lock never blocks one. */
    record MustVisit() implements ItemLock {

        @Override
        public LockType type() {
            return LockType.MUST_VISIT;
        }
    }

    /** The item stays on this date. */
    record Date(LocalDate date) implements ItemLock {

        public Date {
            Objects.requireNonNull(date, "date");
        }

        @Override
        public LockType type() {
            return LockType.DATE;
        }
    }

    /** The item starts at this local time, give or take {@code toleranceMinutes} (0..180). */
    record Time(LocalTime startTime, int toleranceMinutes) implements ItemLock {

        public Time {
            Objects.requireNonNull(startTime, "startTime");
            if (toleranceMinutes < 0 || toleranceMinutes > 180) {
                throw new IllegalArgumentException("toleranceMinutes must be 0..180");
            }
        }

        @Override
        public LockType type() {
            return LockType.TIME;
        }
    }

    /** A booking. {@code endTime} may be null; then only the date and the start time are pinned. */
    record Reservation(LocalDate date, LocalTime startTime, LocalTime endTime) implements ItemLock {

        public Reservation {
            Objects.requireNonNull(date, "date");
            Objects.requireNonNull(startTime, "startTime");
            if (endTime != null && endTime.isBefore(startTime)) {
                throw new IllegalArgumentException("endTime before startTime");
            }
        }

        @Override
        public LockType type() {
            return LockType.RESERVATION;
        }
    }
}
