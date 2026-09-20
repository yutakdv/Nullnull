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

    List<StoredArea> activeAreas(String sourceCode);
}
