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
            String thumbnailUrl, String address) {
    }

    record CatalogPlaceDetail(UUID id, String name, String categoryCode, String regionCode,
            String thumbnailUrl, String address, String description, BigDecimal latitude, BigDecimal longitude,
            List<CatalogExternalReferenceView> externalReferences, CatalogMediaAsset thumbnailAsset) {
    }

    record CatalogExternalReferenceView(String source, String externalId, Instant verifiedAt) {
    }

    record CatalogMediaAsset(UUID id, String url, String mediaType, String alt, String licenseSource,
            String licenseName, String licenseUrl, Instant licenseReviewedAt, boolean attributionRequired,
            String attributionText, boolean redistributionAllowed, Instant expiresAt) {
    }
}
