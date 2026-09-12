package io.nullnull.trip.domain;

/** {@code PlanningLevel} in docs/api/openapi.yaml: how much the traveller has already decided. */
public enum PlanningLevel {
    NOTHING,
    MUST_VISIT_ONLY,
    MOSTLY_PLANNED;

    public static PlanningLevel of(String value) {
        for (PlanningLevel level : values()) {
            if (level.name().equals(value)) {
                return level;
            }
        }
        throw new IllegalArgumentException("unknown planning level");
    }
}
