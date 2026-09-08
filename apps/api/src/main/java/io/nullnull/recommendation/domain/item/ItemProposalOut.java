package io.nullnull.recommendation.domain.item;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Mirrors {@code ItemProposalOut}: one ranked preview. The service sends the decimals as strings, so
 * they arrive as exact {@link BigDecimal} values with no float round trip. A preview never modifies a
 * trip; the user applies it.
 */
public record ItemProposalOut(int rank, LocalDate date, LocalTime startTime, Instant beforeInstant,
        Instant afterInstant, BigDecimal score, BigDecimal improvement, BigDecimal relief, BigDecimal changeCost,
        UUID beforeSnapshotId, UUID afterSnapshotId, Map<String, Boolean> lockChecks) {

    public ItemProposalOut {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(beforeInstant, "beforeInstant");
        Objects.requireNonNull(afterInstant, "afterInstant");
        Objects.requireNonNull(score, "score");
        Objects.requireNonNull(improvement, "improvement");
        Objects.requireNonNull(relief, "relief");
        Objects.requireNonNull(changeCost, "changeCost");
        Objects.requireNonNull(beforeSnapshotId, "beforeSnapshotId");
        Objects.requireNonNull(afterSnapshotId, "afterSnapshotId");
        lockChecks = Map.copyOf(Objects.requireNonNull(lockChecks, "lockChecks"));
        if (rank < 1) {
            throw new IllegalArgumentException("rank starts at 1");
        }
    }
}
