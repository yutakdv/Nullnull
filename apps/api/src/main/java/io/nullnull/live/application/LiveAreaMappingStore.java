package io.nullnull.live.application;

import io.nullnull.live.domain.LiveAreaMapping;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The reviewed links between canonical places and live areas (V044 {@code seoul_live_area_maps}).
 *
 * <p>A separate port from {@link LiveAreaStore} because the two answer different questions about
 * different subjects. That one is told what a provider publishes and owns the area's own lifecycle -
 * a rename updates a row, a disappearance retires one. This one is told nothing by any provider:
 * every row here is a human review decision about where one of OUR places sits, and no collector run
 * may write it. Sharing a port would put a provider's list and a reviewer's judgement behind one
 * interface and invite a collector to "sync" the mappings too.
 *
 * <p><strong>An absent entry is the answer, not a missing answer.</strong> V044 says it in the
 * table's own comment: the contract's {@code LivePlace.mappingType} carries NONE for a place no area
 * covers, and that is expressed by having no row rather than by a row saying "no mapping", so that
 * "is this place covered" cannot have two answers that disagree. Neither method below invents an
 * entry for an id it did not find, and {@link io.nullnull.live.domain.LiveCoverage#decide} reads the
 * absence as NONE.
 */
public interface LiveAreaMappingStore {

    /**
     * Every reviewed mapping into one area, keyed by the place it names.
     *
     * <p>Iteration order is by place id, so the page an area serves is a function of the stored rows
     * and not of a query plan. The caller may not re-sort into an order the reader cannot reproduce.
     */
    Map<UUID, LiveAreaMapping> forArea(UUID liveAreaId);

    /**
     * The reviewed mappings for these places, keyed by place. Ids with no mapping are absent from
     * the result rather than present with a null, for the reason above.
     */
    Map<UUID, LiveAreaMapping> forPlaces(List<UUID> placeIds);
}
