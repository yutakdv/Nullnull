package io.nullnull.recommendation.domain.slot;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Objects;

/**
 * Mirrors {@code SlotOut} and the public {@code CandidateMatchResult.slots[]}: one trip date, a
 * nullable {@code suggestedTime} that P0 never fills, the verdict, and the reason code the FE renders
 * when the date is not eligible. The gateway checks those two invariants on the way in.
 */
public record SlotOut(LocalDate date, LocalTime suggestedTime, boolean eligible, String reasonCode) {

    public SlotOut {
        Objects.requireNonNull(date, "date");
    }
}
