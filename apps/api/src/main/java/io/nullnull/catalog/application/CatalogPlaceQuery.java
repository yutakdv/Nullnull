package io.nullnull.catalog.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read projection boundary for active, canonical places only. */
public interface CatalogPlaceQuery {

    List<CatalogPlaceSummary> search(CatalogPlaceSearchRequest request, long offset, int fetchLimit,
            Instant observedAt);

    Optional<CatalogPlaceDetail> find(UUID requestedPlaceId, String locale, Instant observedAt);

    /**
     * Active canonical summaries for the given ids, for callers that embed places in another
     * resource (a feed card, a post's linked places). One statement rather than N: a per-place
     * lookup is what makes an embedding caller reach for a shared cache.
     *
     * <p>Ids that resolve to nothing are simply absent from the result. A deprecated id resolves to
     * its canonical row, the same as {@link #find}, so an embedding never renders a stale duplicate.
     */
    List<CatalogPlaceSummary> summaries(List<UUID> placeIds, String locale, Instant observedAt);

    record CatalogPlaceSummary(UUID id, String name, String categoryCode, String regionCode,
            String categoryName, String regionName, String thumbnailUrl, String address,
            CatalogSourceAttribution sourceAttribution) {
    }

    record CatalogPlaceDetail(UUID id, String name, String categoryCode, String regionCode,
            String categoryName, String regionName, String thumbnailUrl, String address, String description,
            BigDecimal latitude, BigDecimal longitude, List<CatalogExternalReferenceView> externalReferences,
            CatalogMediaAsset thumbnailAsset, CatalogSourceAttribution sourceAttribution) {
    }

    /**
     * The approved credit for the source this place was collected from, read from the reviewed
     * registry revision the place's external reference points at. A place with no external reference,
     * or a source whose revision carries no approved credit text, projects no attribution at all
     * rather than a partial one.
     */
    record CatalogSourceAttribution(String source, String sourceDisplayName, long sourceRegistryVersion,
            String attribution, String officialUrl, String licenseUrl, String license) {
    }

    record CatalogExternalReferenceView(String source, String externalId, Instant verifiedAt) {
    }

    record CatalogMediaAsset(UUID id, String url, String mediaType, String alt, String licenseSource,
            String licenseName, String licenseUrl, Instant licenseReviewedAt, boolean attributionRequired,
            String attributionText, boolean redistributionAllowed, Instant expiresAt) {
    }
}
