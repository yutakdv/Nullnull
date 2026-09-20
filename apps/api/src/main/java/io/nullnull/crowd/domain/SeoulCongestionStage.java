package io.nullnull.crowd.domain;

import java.util.Map;

/**
 * Seoul's four published steps placed on the product's five-cell scale.
 *
 * <p><strong>The placement is decision A-060 (owner, 2026-09-20), not a derivation.</strong> Four
 * steps fit five cells in more than one way - 1·2·3·4, 2·3·4·5, 1·2·4·5 all hold - and nothing in
 * the source chooses between them. The owner chose {@code 1·2·3·4} and left cell 5 empty, for the
 * reason that Seoul's top step is unbounded: 붐빔 is "over 100%" with no ceiling, so calling it the
 * maximum of OUR scale would assert something the provider never said. An empty top cell leaves room
 * for a source that does have a ceiling, or for Seoul subdividing later.
 *
 * <p><strong>The thresholds are percentages of THAT AREA'S OWN PAST AVERAGE, not of a capacity and
 * not of anything comparable between areas</strong> (manual v8.5, 2026-04):
 *
 * <ul>
 *   <li>여유 - at or under 50% - cell "1"
 *   <li>보통 - over 50% and at or under 75% - cell "2"
 *   <li>약간 붐빔 - over 75% and at or under 100% - cell "3"
 *   <li>붐빔 - over 100%, unbounded - cell "4"
 * </ul>
 *
 * <p>That relativity is written here because the numbers read as absolutes to anyone who meets them
 * without it, and reading them as absolutes is exactly the cross-area comparison invariant 8
 * forbids: two areas both at "3" are each three quarters of their own normal, which says nothing
 * about which of them is busier.
 *
 * <p>What this class does NOT decide is how a five-cell bar renders with its top cell never filled.
 * That is FE's, on FCR-035.
 */
public final class SeoulCongestionStage {

    /** The four values SeoulCityDataValidator accepts, and nothing else reaches here. */
    private static final Map<String, String> STAGES =
            Map.of("여유", "1", "보통", "2", "약간 붐빔", "3", "붐빔", "4");

    private SeoulCongestionStage() {
    }

    /**
     * The scale cell for one published step.
     *
     * @throws IllegalArgumentException for anything else - a fifth step is provider drift, which the
     *     validator already refuses, and guessing one here would put an unreviewed stage on the scale
     */
    public static String of(String publishedStep) {
        String stage = STAGES.get(publishedStep);
        if (stage == null) {
            throw new IllegalArgumentException("not a published Seoul congestion step");
        }
        return stage;
    }

    /** The cells A-060 assigns; cell 5 is deliberately absent. */
    public static Map<String, String> mapping() {
        return STAGES;
    }
}
