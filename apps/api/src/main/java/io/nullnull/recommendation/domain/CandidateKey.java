package io.nullnull.recommendation.domain;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Identity of one recommendation candidate. Related places use the place only; slot and ITEM
 * proposals add a trip-local date and optional time (§3.2). A time without a date is invalid.
 * Feed candidates are keyed by post id and are not represented here.
 */
public record CandidateKey(UUID placeId, LocalDate date, LocalTime time) {

    public CandidateKey {
        Objects.requireNonNull(placeId, "placeId");
        if (time != null && date == null) {
            throw new IllegalArgumentException("time requires a date");
        }
    }

    public static CandidateKey ofPlace(UUID placeId) {
        return new CandidateKey(placeId, null, null);
    }

    public static CandidateKey ofDate(UUID placeId, LocalDate date) {
        return new CandidateKey(placeId, Objects.requireNonNull(date, "date"), null);
    }

    public static CandidateKey ofDateTime(UUID placeId, LocalDate date, LocalTime time) {
        return new CandidateKey(placeId, Objects.requireNonNull(date, "date"),
                Objects.requireNonNull(time, "time"));
    }

    public boolean hasDate() {
        return date != null;
    }

    public boolean hasTime() {
        return time != null;
    }
}
