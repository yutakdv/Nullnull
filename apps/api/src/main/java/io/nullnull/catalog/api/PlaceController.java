package io.nullnull.catalog.api;

import io.nullnull.catalog.application.CatalogPlaceProjectionService;
import io.nullnull.catalog.application.CatalogPlaceProjectionService.CatalogPlaceSearchPage;
import io.nullnull.catalog.application.CatalogPlaceQuery.CatalogExternalReferenceView;
import io.nullnull.catalog.application.CatalogPlaceQuery.CatalogMediaAsset;
import io.nullnull.catalog.application.CatalogPlaceQuery.CatalogPlaceDetail;
import io.nullnull.catalog.application.CatalogPlaceQuery.CatalogPlaceSummary;
import io.nullnull.catalog.application.CatalogPlaceSearchRequest;
import io.nullnull.identity.application.OwnerContext;
import io.nullnull.shared.http.NullnullOperation;
import io.nullnull.shared.http.NullnullOperation.Security;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** C3 public shapes for the approved {@code searchPlaces} and {@code getPlace} operations. */
@RestController
public class PlaceController {

    private final CatalogPlaceProjectionService places;

    public PlaceController(CatalogPlaceProjectionService places) {
        this.places = places;
    }

    @PostMapping(value = "/places/search", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @NullnullOperation(id = "searchPlaces", security = Security.SESSION)
    public PlaceSearchPageResponse search(OwnerContext owner, @RequestBody PlaceSearchBody body) {
        CatalogPlaceSearchPage page = places.search(owner,
                CatalogPlaceSearchRequest.of(body == null ? null : body.query(), body == null ? null : body.locale(),
                        body == null ? null : body.regionCode(), body == null ? null : body.cursor(),
                        body == null ? null : body.limit()));
        return new PlaceSearchPageResponse(page.items().stream().map(PlaceSummaryResponse::from).toList(),
                new CursorPageResponse(page.nextCursor(), page.hasMore()));
    }

    @GetMapping(value = "/places/{placeId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @NullnullOperation(id = "getPlace", security = Security.SESSION)
    public PlaceDetailResponse detail(OwnerContext owner, @PathVariable UUID placeId) {
        return PlaceDetailResponse.from(places.detail(owner, placeId));
    }

    public record PlaceSearchBody(String query, String locale, String regionCode, String cursor, Integer limit) {
    }

    public record PlaceSearchPageResponse(List<PlaceSummaryResponse> items, CursorPageResponse page) {
    }

    public record CursorPageResponse(String nextCursor, boolean hasMore) {
    }

    public record PlaceSummaryResponse(UUID id, String name, String categoryCode, String regionCode,
            String thumbnailUrl, String address) {
        static PlaceSummaryResponse from(CatalogPlaceSummary source) {
            return new PlaceSummaryResponse(source.id(), source.name(), source.categoryCode(), source.regionCode(),
                    source.thumbnailUrl(), source.address());
        }
    }

    public record PlaceDetailResponse(UUID id, String name, String categoryCode, String regionCode,
            String thumbnailUrl, MediaAssetResponse thumbnailAsset, String address, String description,
            GeoPointResponse location, List<ExternalReferenceResponse> externalRefs) {
        static PlaceDetailResponse from(CatalogPlaceDetail source) {
            return new PlaceDetailResponse(source.id(), source.name(), source.categoryCode(), source.regionCode(),
                    source.thumbnailUrl(), MediaAssetResponse.from(source.thumbnailAsset()), source.address(),
                    source.description(), new GeoPointResponse(source.latitude(), source.longitude()),
                    source.externalReferences().stream().map(ExternalReferenceResponse::from).toList());
        }
    }

    public record GeoPointResponse(BigDecimal latitude, BigDecimal longitude) {
    }

    public record ExternalReferenceResponse(String source, String externalId, Instant verifiedAt) {
        static ExternalReferenceResponse from(CatalogExternalReferenceView source) {
            return new ExternalReferenceResponse(source.source(), source.externalId(), source.verifiedAt());
        }
    }

    public record MediaAssetResponse(UUID id, String url, String mediaType, String alt, AssetLicenseResponse license,
            boolean attributionRequired, String attributionText, boolean redistributionAllowed, Instant expiresAt) {
        static MediaAssetResponse from(CatalogMediaAsset source) {
            if (source == null) {
                return null;
            }
            return new MediaAssetResponse(source.id(), source.url(), source.mediaType(), source.alt(),
                    new AssetLicenseResponse(source.licenseSource(), source.licenseName(), source.licenseUrl(),
                            source.licenseReviewedAt()),
                    source.attributionRequired(), source.attributionText(), source.redistributionAllowed(),
                    source.expiresAt());
        }
    }

    public record AssetLicenseResponse(String source, String name, String url, Instant reviewedAt) {
    }
}
