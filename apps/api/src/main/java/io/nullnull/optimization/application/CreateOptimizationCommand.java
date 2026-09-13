package io.nullnull.optimization.application;

import io.nullnull.optimization.domain.OptimizationScope;
import io.nullnull.trip.domain.TripValidationException;
import java.time.LocalDate;
import java.util.UUID;

/**
 * createOptimization's body, validated before anything is written.
 *
 * <p>The shape rules are the operation's own sentence: "ITEM requires exactly targetItemId, DAY
 * requires exactly targetDate, and TRIP accepts neither". Exactly is the word that matters - a DAY
 * request carrying an item id is refused rather than ignored, because accepting it would silently
 * run something other than what was asked.
 *
 * <p>This is the shape gate only. Whether the scope is available at all is a different question with
 * a different answer ({@code OptimizationCapability}), and they are kept apart deliberately: a
 * well-formed DAY request passes every rule here and is still refused, so a test that only proves the
 * shape rules would leave the availability gate unproven.
 */
public record CreateOptimizationCommand(OptimizationScope scope, UUID targetItemId, LocalDate targetDate,
        long inputTripVersion, boolean includeCandidates, String objective) {

    /** The contract's only objective value, and its default. */
    public static final String REDUCE_CROWD = "REDUCE_CROWD";

    public CreateOptimizationCommand {
        if (scope == null) {
            throw new TripValidationException("scope", "NotNull", "scope is required");
        }
        switch (scope) {
            case ITEM -> {
                if (targetItemId == null) {
                    throw new TripValidationException("targetItemId", "NotNull",
                            "an ITEM optimization requires targetItemId");
                }
                if (targetDate != null) {
                    throw new TripValidationException("targetDate", "Unsupported",
                            "an ITEM optimization does not take targetDate");
                }
            }
            case DAY -> {
                if (targetDate == null) {
                    throw new TripValidationException("targetDate", "NotNull",
                            "a DAY optimization requires targetDate");
                }
                if (targetItemId != null) {
                    throw new TripValidationException("targetItemId", "Unsupported",
                            "a DAY optimization does not take targetItemId");
                }
            }
            case TRIP -> {
                if (targetItemId != null || targetDate != null) {
                    throw new TripValidationException("scope", "Unsupported",
                            "a TRIP optimization takes no target");
                }
            }
        }
        if (inputTripVersion < 1) {
            throw new TripValidationException("inputTripVersion", "Min",
                    "inputTripVersion must be at least 1");
        }
        if (includeCandidates) {
            // Published as a boolean because the field is part of the request shape, refused because
            // P0 has no proposal path that reads candidates: accepting it would mean either ignoring
            // what the caller asked for or inventing a behaviour nobody has specified.
            throw new TripValidationException("includeCandidates", "Unsupported",
                    "includeCandidates=true is not supported");
        }
        objective = objective == null ? REDUCE_CROWD : objective;
        if (!REDUCE_CROWD.equals(objective)) {
            throw new TripValidationException("objective", "Unsupported",
                    "the only objective is " + REDUCE_CROWD);
        }
    }
}
