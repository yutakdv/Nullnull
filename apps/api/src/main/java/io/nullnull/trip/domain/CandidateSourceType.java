package io.nullnull.trip.domain;

/** Where a candidate was saved from. Only POST names a post; the rest carry none. */
public enum CandidateSourceType {
    POST,
    SEARCH,
    LIVE,
    IMPORT;

    public static CandidateSourceType of(String value) {
        for (CandidateSourceType type : values()) {
            if (type.name().equals(value)) {
                return type;
            }
        }
        throw new TripValidationException("source.type", "Enum",
                "source type must be POST, SEARCH, LIVE or IMPORT");
    }
}
