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
