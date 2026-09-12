package io.nullnull.trip.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * {@code TripInterest} in docs/api/openapi.yaml: an interest code and how strongly it applies.
 *
 * <p>Which codes are supported is {@link InterestVocabulary}, settled by FCR-020. Before that it
 * accepted any non-blank string, because inventing a vocabulary would have produced either codes no
 * chip can send or chips the server rejects.
 *
 * <p>What IS decided: the ERD's primary key {@code (trip_id, interest_code)} means one weight per
 * code. The contract's `uniqueItems: true` compares whole objects and therefore lets
 * {@code {code:"FOOD",weight:1}} and {@code {code:"FOOD",weight:5}} through together; the database
 * would reject that pair, so it is rejected here with a field error rather than as a constraint
 * violation the caller cannot read.
 */
public record TripInterest(String code, int weight) {

    public static final int MAX_INTERESTS = 20;
    public static final int MAX_CODE_LENGTH = 100;
    public static final int MIN_WEIGHT = 1;
    public static final int MAX_WEIGHT = 5;

    public TripInterest {
        Objects.requireNonNull(code, "code");
        if (code.isBlank()) {
            throw new TripValidationException("interests[].code", "NotBlank",
                    "interest code must not be blank");
        }
        if (code.length() > MAX_CODE_LENGTH) {
            throw new TripValidationException("interests[].code", "Size",
                    "interest code must be at most " + MAX_CODE_LENGTH + " characters");
        }
        InterestVocabulary.require(code);
        if (weight < MIN_WEIGHT || weight > MAX_WEIGHT) {
            throw new TripValidationException("interests[].weight", "Range",
                    "weight must be between " + MIN_WEIGHT + " and " + MAX_WEIGHT);
        }
    }

    /**
     * Validates a whole submitted list. An empty list is valid - the wizard allows "no interests" and
     * the contract's minItems is 0 - so emptiness is never an error here.
     */
    public static List<TripInterest> validated(List<TripInterest> submitted) {
        List<TripInterest> interests = submitted == null ? List.of() : submitted;
        if (interests.size() > MAX_INTERESTS) {
            throw new TripValidationException("interests", "Size",
                    "at most " + MAX_INTERESTS + " interests are allowed");
        }
        Set<String> seen = new LinkedHashSet<>();
        List<TripInterest> validated = new ArrayList<>(interests.size());
        for (TripInterest interest : interests) {
            Objects.requireNonNull(interest, "interest");
            if (!seen.add(interest.code())) {
                // Not "duplicate object": two entries with the same code and different weights are
                // the case the contract's uniqueItems misses and the ERD's primary key forbids.
                throw new TripValidationException("interests[].code", "Duplicate",
                        "each interest code may appear at most once");
            }
            validated.add(interest);
        }
        return List.copyOf(validated);
    }
}
