package io.nullnull.importer.application;

import io.nullnull.catalog.application.CatalogPlaceProjectionService;
import io.nullnull.catalog.application.CatalogPlaceQuery.CatalogPlaceSummary;
import io.nullnull.identity.application.OwnerContext;
import io.nullnull.importer.domain.ImportDraft;
import io.nullnull.importer.domain.ImportDraftItem;
import io.nullnull.importer.domain.UnresolvedToken;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A draft with the place summaries its ids resolve to.
 *
 * <p>One lookup for the whole draft: items and token suggestions are hydrated together, because a
 * per-place call is what makes a caller reach for a cache the publication gate cannot see.
 *
 * <p>An id that resolves to nothing is simply absent from the map, and the response renders that as
 * {@code place: null} - the same shape as an entry the parser never mapped. That is deliberate: a
 * place retired between parse and remap is, from the traveller's side, exactly an entry with no place
 * yet, and remapping it is the fix either way.
 */
public record ImportDraftView(ImportDraft draft, Map<UUID, CatalogPlaceSummary> places) {

    public ImportDraftView {
        Objects.requireNonNull(draft, "draft");
        places = Map.copyOf(Objects.requireNonNull(places, "places"));
    }

    public static ImportDraftView of(ImportDraft draft, CatalogPlaceProjectionService projection,
            OwnerContext owner) {
        List<UUID> ids = new ArrayList<>();
        for (ImportDraftItem item : draft.content().items()) {
            if (item.placeId() != null && !ids.contains(item.placeId())) {
                ids.add(item.placeId());
            }
        }
        for (UnresolvedToken token : draft.unresolved()) {
            for (UUID suggestion : token.suggestionPlaceIds()) {
                if (!ids.contains(suggestion)) {
                    ids.add(suggestion);
                }
            }
        }
        Map<UUID, CatalogPlaceSummary> byId = new LinkedHashMap<>();
        if (!ids.isEmpty()) {
            projection.embeddedSummaries(owner, ids).forEach(summary -> byId.put(summary.id(), summary));
        }
        return new ImportDraftView(draft, byId);
    }

    public CatalogPlaceSummary place(UUID placeId) {
        return placeId == null ? null : places.get(placeId);
    }
}
