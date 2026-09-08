package io.nullnull.recommendation.domain.related;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Mirrors {@code RelatedRankRequest}: every relation row this API found for one source place, plus the
 * categories they are compared against. {@code lookupOutcome} is how that lookup ended, so a failed
 * source or a running verification job is answered as UNKNOWN/CHECKING and never as "no related
 * places". The body carries no owner, session or raw itinerary text.
 *
 * <p>The three cross-field rules below describe a hydration bug on this side, not a user input: a
 * source category for another place, a relation that starts somewhere else, or a repeated category
 * would make the service rank evidence for a place the caller never asked about.
 */
public record RelatedRankRequest(Instant evaluatedAt, UUID sourcePlaceId, PlaceCategoryIn sourceCategory,
        List<RelationCandidateIn> candidates, List<PlaceCategoryIn> categories, LookupOutcome lookupOutcome) {

    /** How this API's relation lookup ended. JOB_RUNNING is only for a real verification job. */
    public enum LookupOutcome { COMPLETE, SOURCE_FAILED, JOB_RUNNING }

    public static final int MAX_CANDIDATES = 2000;
    public static final int MAX_CATEGORIES = 2000;

    public RelatedRankRequest {
        Objects.requireNonNull(evaluatedAt, "evaluatedAt");
        Objects.requireNonNull(sourcePlaceId, "sourcePlaceId");
        Objects.requireNonNull(sourceCategory, "sourceCategory");
        Objects.requireNonNull(lookupOutcome, "lookupOutcome");
        candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
        categories = List.copyOf(Objects.requireNonNull(categories, "categories"));
        requireAtMost(candidates.size(), MAX_CANDIDATES, "candidates");
        requireAtMost(categories.size(), MAX_CATEGORIES, "categories");
        if (!sourceCategory.placeId().equals(sourcePlaceId)) {
            throw new IllegalArgumentException("sourceCategory must describe the source place");
        }
        for (RelationCandidateIn candidate : candidates) {
            if (!candidate.sourcePlaceId().equals(sourcePlaceId)) {
                throw new IllegalArgumentException("every candidate must start at the request's source place");
            }
        }
        Set<UUID> places = new HashSet<>();
        for (PlaceCategoryIn category : categories) {
            if (!places.add(category.placeId())) {
                throw new IllegalArgumentException("categories must not carry two rows for the same place");
            }
        }
    }

    private static void requireAtMost(int size, int cap, String name) {
        if (size > cap) {
            throw new IllegalArgumentException("at most " + cap + " " + name + " per request");
        }
    }
}
