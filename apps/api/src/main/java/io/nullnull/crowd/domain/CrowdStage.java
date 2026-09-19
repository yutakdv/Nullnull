package io.nullnull.crowd.domain;

import java.util.List;
import java.util.Set;

/**
 * The product's crowd-stage scale, as {@code CrowdMetric.ordinalLevel} publishes it: five steps, the
 * digit strings "1" (least crowded) to "5" (most crowded). Five is the owner's decision of 09-19
 * (FCR-035), taken from the Figma bar's five cells.
 *
 * <p>A stage is only ever what a source published on this scale. It is never derived from a point's
 * {@code value}: the KTO series is relative to each place's own peak, and cutting it into steps would
 * use thresholds nobody measured (invariant 8). CrowdVocabularyContractTest keeps this list and the
 * contract's enum the same.
 */
public final class CrowdStage {

    public static final List<String> SCALE = List.of("1", "2", "3", "4", "5");

    /**
     * Sources whose stored stage may be served as a stage: none yet. A stage says "this source published
     * this step of our scale", and no source has a mapping onto it that anyone reviewed - KTO publishes a
     * relative index with no steps at all, and its writer stores NULL. So a stored stage is not passed
     * through as evidence nobody approved: it is served as no stage, with SCHEMA_DRIFT (BA-023-T23).
     *
     * <p>The slice that adds a producer adds its source code here together with the mapping that was
     * reviewed. Until then this empty set is what makes the contract's "null today" true of the server
     * and not only of the one writer.
     */
    public static final Set<String> SOURCES_WITH_REVIEWED_SCALE = Set.of();

    private CrowdStage() {
    }

    public static boolean onScale(String level) {
        return level != null && SCALE.contains(level);
    }

    /** Whether a stored stage may be served as one: on the scale, from a source reviewed for it. */
    public static boolean publishable(String sourceCode, String level) {
        return onScale(level) && SOURCES_WITH_REVIEWED_SCALE.contains(sourceCode);
    }
}
