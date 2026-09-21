package io.nullnull.crowd.domain;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

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
    private static final Map<String, Supplier<Map<String, String>>> REVIEWED_MAPPINGS =
            Map.of("SEOUL_CITYDATA", SeoulCongestionStage::mapping);

    /**
     * Derived from {@link #REVIEWED_MAPPINGS} rather than listed a second time: naming a source here
     * without supplying the mapping that was reviewed for it is the shape this set exists to prevent,
     * and a separate literal would allow exactly that.
     */
    public static final Set<String> SOURCES_WITH_REVIEWED_SCALE = REVIEWED_MAPPINGS.keySet();

    private CrowdStage() {
    }

    public static boolean onScale(String level) {
        return level != null && SCALE.contains(level);
    }

    /** Whether a stored stage may be served as one: on the scale, from a source reviewed for it. */
    public static boolean publishable(String sourceCode, String level) {
        return onScale(level) && SOURCES_WITH_REVIEWED_SCALE.contains(sourceCode);
    }

    /**
     * The scale a source publishes on, for a client that must not assume every cell is reachable.
     *
     * <p>{@code CrowdMetric.ordinalLevel} alone cannot be rendered honestly: a reader that meets a
     * "3" has no way to know whether the source that produced it publishes three steps or five, so a
     * fixed "Nth of five" is wrong for every source that fills part of the scale - which today is the
     * only source that has a scale at all. Seoul publishes four and never "5" (A-060).
     *
     * <p>Empty for every source without a reviewed mapping, which is the honest answer rather than a
     * default: a scale for a source nobody reviewed would be invented, and inventing one is what
     * {@code SCHEMA_DRIFT} exists to refuse.
     *
     * <p>The cells come from the reviewed mapping itself, never from a second list. The words for
     * each step stay out: they are the provider's Korean and would travel with no
     * {@code textProvenance}, so the descriptor carries structure and the copy stays Frontend's.
     */
    public static Optional<Scale> scaleOf(String sourceCode) {
        Supplier<Map<String, String>> mapping =
                sourceCode == null ? null : REVIEWED_MAPPINGS.get(sourceCode);
        if (mapping == null) {
            return Optional.empty();
        }
        return Optional.of(new Scale(SCALE.size(),
                mapping.get().values().stream().distinct().sorted().toList()));
    }

    /**
     * How many cells the product scale has, and which of them this source can actually produce.
     *
     * @param size the product scale's cell count - what a bar has room for
     * @param publishedCells the cells this source publishes, ascending; a strict subset when the
     *     source fills part of the scale
     */
    public record Scale(int size, List<String> publishedCells) {
    }
}
