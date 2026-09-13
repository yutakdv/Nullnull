package io.nullnull.optimization.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Where a run is, and which way it may still move.
 *
 * <p>ERD §2 states the graph: {@code QUEUED → RUNNING → READY → APPLIED|KEPT}, {@code APPLIED →
 * REVERTED}, and from any non-terminal status additionally {@code FAILED|EXPIRED}. Writing the
 * successors here rather than checking them at each call site is what makes "run 상태가 역행하지
 * 않는다" a property of the type: a transition that is not listed cannot be performed at all, so a
 * later reader of a run can trust that its status only ever moved forwards.
 *
 * <p>The decision statuses ({@code APPLIED}, {@code KEPT}, {@code REVERTED}) are listed because they
 * are part of the graph a run may walk; the slice that records decisions is BA-052, and this type
 * says what it is allowed to do before it exists.
 */
public enum OptimizationStatus {

    QUEUED,
    RUNNING,
    READY,
    APPLIED,
    KEPT,
    REVERTED,
    FAILED,
    EXPIRED;

    private static final Set<OptimizationStatus> TERMINAL =
            EnumSet.of(APPLIED, KEPT, REVERTED, FAILED, EXPIRED);

    public boolean terminal() {
        return TERMINAL.contains(this);
    }

    /** True while the worker still owes an answer, which is also when a poll gets a Retry-After. */
    public boolean pending() {
        return this == QUEUED || this == RUNNING;
    }

    /**
     * Whether {@code next} is reachable from this status.
     *
     * <p>REVERTED is reachable only from APPLIED - a KEEP records intent and changes no trip, so
     * there is nothing to take back - and no terminal status leads anywhere.
     */
    public boolean canMoveTo(OptimizationStatus next) {
        if (next == null || next == this) {
            return false;
        }
        return switch (this) {
            case QUEUED -> next == RUNNING || next == FAILED || next == EXPIRED;
            case RUNNING -> next == READY || next == FAILED || next == EXPIRED;
            case READY -> next == APPLIED || next == KEPT || next == FAILED || next == EXPIRED;
            case APPLIED -> next == REVERTED;
            case KEPT, REVERTED, FAILED, EXPIRED -> false;
        };
    }

    public static OptimizationStatus of(String value) {
        for (OptimizationStatus status : values()) {
            if (status.name().equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("unknown optimization status: " + value);
    }
}
