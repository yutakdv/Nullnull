package io.nullnull.operations.application;

import java.util.List;
import java.util.Map;

/**
 * The published vocabulary of {@code CapabilityStatus.name} for {@code getDemoReadiness}.
 *
 * <p>docs/api/openapi.yaml types that field as a bare {@code string} with no enum, so the value set is
 * a server decision. It is pinned here, and by
 * {@code DemoCapabilityQueryTest.theVocabularyIsPinned}, rather than being spelled inline at a call
 * site, because the Frontend maps these names to screens: adding or renaming one is a FE-facing
 * contract change and is listed as an FE review item on the BA-003 card. The only
 * documentary source for the names is FUNCTIONAL_INVENTORY {@code FR-OPS-02}
 * ("live/replay/optimization별 상태"), so the set is exactly those three and nothing invented beyond
 * them.
 *
 * <p><strong>This is not the {@code /health/ready} namespace.</strong> That one lists infrastructure
 * probes - {@code database}, {@code jobs}, {@code recommendation} - which answer "should this task stay
 * in the load balancer". These are product capabilities, which answer "can a visitor use this feature".
 * The two lists deliberately share no name: a load balancer has no business learning what a product
 * feature is called, and the Frontend must not end up keyed on an infrastructure component that a later
 * slice renames or splits.
 */
public final class DemoCapabilities {

    /** Real-time crowd data for an area (B10 / BA-070). */
    public static final String LIVE = "live";

    /** Recorded observations replayed as a demo dataset (B03). */
    public static final String REPLAY = "replay";

    /** Preview-first itinerary optimization (B06). */
    public static final String OPTIMIZATION = "optimization";

    /** The whole set, in response order. */
    public static final List<String> NAMES = List.of(LIVE, REPLAY, OPTIMIZATION);

    /**
     * The documented environment flag that would turn each capability on
     * (docs/operations/ENVIRONMENT.md §6). The name is quoted in the capability detail and in the
     * startup failure, so an operator reading either one knows which variable to change.
     */
    public static final Map<String, String> FLAG_VARIABLES = Map.of(
            LIVE, "FEATURE_LIVE_DATA",
            REPLAY, "FEATURE_REPLAY_MODE",
            OPTIMIZATION, "FEATURE_OPTIMIZATION_ITEM");

    private DemoCapabilities() {
    }
}
