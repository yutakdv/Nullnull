package io.nullnull.trip.domain;

/**
 * Where a candidate was saved from. Only POST names a post; the rest carry none.
 *
 * <p>{@code TRIP_SEED} is the one nobody chooses: it marks a place that arrived with the trip itself,
 * through createTrip's seedItems. It exists because restoring such an item as a candidate has to say
 * something true about where the place came from, and the four that were here are all wrong for it -
 * calling it SEARCH would invent a provenance the user never produced.
 */
public enum CandidateSourceType {
    POST,
    SEARCH,
    LIVE,
    IMPORT,
    TRIP_SEED;

    public static CandidateSourceType of(String value) {
        for (CandidateSourceType type : values()) {
            if (type.name().equals(value)) {
                return type;
            }
        }
        throw new TripValidationException("source.type", "Enum",
                "source type must be one of " + java.util.Arrays.toString(values()));
    }
}
