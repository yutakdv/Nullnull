package io.nullnull.trip.domain;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The interest codes a trip may carry.
 *
 * <p>FCR-020 settled this: the canon is the Figma chip list at {@code 438:3108}, and Frontend's
 * {@code wizard.ts} already sends exactly these thirteen. Until it was settled the server accepted
 * any non-blank string, because inventing a vocabulary would have produced either codes no chip
 * can send or chips the server rejects.
 *
 * <p>The two groups are how the SCREEN arranges them, not a second axis in the data: the contract's
 * {@code TripInterest[]} is flat and a request carries no group, so this is one set. Grouping them
 * here would imply a per-group rule - a cap, a required choice - that nothing has decided.
 */
public final class InterestVocabulary {

    /** "Who with", as the wizard's first chip group. */
    private static final Set<String> COMPANIONS =
            Set.of("ALONE", "FRIENDS", "PARTNER", "SPOUSE", "KIDS", "PARENTS");

    /** Travel style, the second group. */
    private static final Set<String> STYLES = Set.of("LANDMARKS", "RELAXED", "CULTURE", "NATURE",
            "FOOD", "LOCAL_VIBE", "ACTIVITY");

    /**
     * The only weight the wizard sends, and the midpoint of the contract's 1..5.
     *
     * <p>Not enforced here. The chip screen collects membership rather than strength, so it sends a
     * neutral 3 for every chip; but the contract allows 1..5 and another surface may one day mean
     * something by it. Refusing anything else would freeze a decision about strength that this
     * vocabulary does not own.
     */
    public static final int NEUTRAL_WEIGHT = 3;

    private static final Set<String> ALL = allCodes();

    private InterestVocabulary() {
    }

    public static Set<String> codes() {
        return ALL;
    }

    /** @throws TripValidationException naming the field, never echoing the rejected value. */
    public static void require(String code) {
        if (code == null || !ALL.contains(code)) {
            throw new TripValidationException("interests[].code", "Unsupported",
                    "interest code is not one of the " + ALL.size() + " supported codes");
        }
    }

    private static Set<String> allCodes() {
        Set<String> codes = new LinkedHashSet<>(COMPANIONS);
        codes.addAll(STYLES);
        return Set.copyOf(codes);
    }
}
