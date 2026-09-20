package io.nullnull.live.application;

import java.util.List;
import java.util.UUID;

/** Writes and reads the areas a live source publishes. */
public interface LiveAreaStore {

    /** One area as the provider names it. {@code externalId} is AREA_CD, {@code name} is AREA_NM. */
    record AreaUpsert(String externalId, String name) {}

    /** A stored area. {@code centroid} is absent because no source publishes one (see V044). */
    record StoredArea(UUID id, String externalId, String name, String status) {}

    /**
     * Makes the published set the stored set for one source, in one transaction.
     *
     * @return the areas that are ACTIVE afterwards, in external id order
     */
    List<StoredArea> replaceAreas(String sourceCode, List<AreaUpsert> published);

    /**
     * Makes one area present and ACTIVE, retiring nothing.
     *
     * <p>Separate from {@link #replaceAreas} because the two answer different questions. That one is
     * told the whole published list and may therefore conclude that an absent area is gone; this one
     * is told about a single area and knows nothing about the rest. Reusing the list form for one
     * area would retire every other area the source has - the 2026-03 notice moved 120 of them, and
     * a collection of one would have read as a list of one.
     */
    StoredArea upsertArea(String sourceCode, AreaUpsert area);

    List<StoredArea> activeAreas(String sourceCode);
}
