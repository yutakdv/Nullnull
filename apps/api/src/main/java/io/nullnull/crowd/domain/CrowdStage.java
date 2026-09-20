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
     * Sources whose stored stage may be served as a stage, each with the mapping that was reviewed.
     *
     * <p>A stage says "this source published this step of our scale". Without a reviewed mapping a
     * stored stage is evidence nobody approved, so it is served as no stage with SCHEMA_DRIFT
     * (BA-023-T23) - and that is still what happens to every source not named here. KTO is one of
     * them: it publishes a relative index with no steps at all and its writer stores NULL.
     *
     * <p><strong>SEOUL_CITYDATA is the first, by owner decision A-060 (2026-09-20).</strong> Its
     * mapping is {@link SeoulCongestionStage}, which is where the four steps, their thresholds and
     * the fact that those thresholds are relative to each area's own past average are written. This
     * set carries the name; that class carries the meaning, because a set of strings cannot hold the
     * part a reader needs in order not to misread the numbers as absolutes.
     */
    public static final Set<String> SOURCES_WITH_REVIEWED_SCALE = Set.of("SEOUL_CITYDATA");

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
