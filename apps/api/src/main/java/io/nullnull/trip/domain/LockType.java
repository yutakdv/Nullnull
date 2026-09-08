package io.nullnull.trip.domain;

/**
 * The four independent itinerary locks (docs/api/README.md §8, CLAUDE.md invariant 7). They are never
 * auto-released and one never implies another.
 */
public enum LockType {
    MUST_VISIT,
    DATE,
    TIME,
    RESERVATION
}
