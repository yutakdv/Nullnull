package io.nullnull.catalog.api;

import io.nullnull.catalog.application.CatalogPlaceProjectionService;
import io.nullnull.catalog.application.CatalogPlaceProjectionService.CatalogPlaceSearchPage;
import io.nullnull.catalog.application.CatalogPlaceQuery.CatalogExternalReferenceView;
import io.nullnull.catalog.application.CatalogPlaceQuery.CatalogMediaAsset;
import io.nullnull.catalog.application.CatalogPlaceQuery.CatalogPlaceDetail;
import io.nullnull.catalog.application.CatalogPlaceQuery.CatalogPlaceSummary;
import io.nullnull.catalog.application.CatalogPlaceQuery.CatalogPlaceTextProvenance;
import io.nullnull.catalog.application.CatalogPlaceQuery.CatalogSourceAttribution;
import io.nullnull.catalog.application.CatalogPlaceQuery.CatalogTextFieldProvenance;
import io.nullnull.catalog.application.CatalogPlaceSearchRequest;
import io.nullnull.catalog.application.CatalogRelationProjectionService;
import io.nullnull.catalog.application.CatalogRelationProjectionService.CatalogRelatedPlace;
import io.nullnull.catalog.application.CatalogRelationProjectionService.CatalogRelatedPlaces;
import io.nullnull.catalog.application.CatalogRelationProjectionService.CatalogRelationProvenance;
import io.nullnull.identity.application.OwnerContext;
import io.nullnull.shared.http.NullnullOperation;
import io.nullnull.shared.http.NullnullOperation.Security;
import io.nullnull.shared.problem.ApiException;
import io.nullnull.shared.problem.ProblemCode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** C3 public shapes for the approved {@code searchPlaces} and {@code getPlace} operations. */
@RestController
public class PlaceController {

    private final CatalogPlaceProjectionService places;
    private final CatalogRelationProjectionService relations;

    public PlaceController(CatalogPlaceProjectionService places, CatalogRelationProjectionService relations) {
        this.places = places;
        this.relations = relations;
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

    /**
     * {@code at} and {@code source} are published query parameters with no reading code here, so they
     * are refused rather than accepted and dropped. A filter that silently does nothing answers a
     * different question than the one asked - {@code source} would return unfiltered results and
     * {@code at} would answer for now instead of then, both without saying so. Refusal is reversible
     * the day either is implemented; a silent answer is not (docs/api/README.md §1).
     */
    @GetMapping(value = "/places/{placeId}/related", produces = MediaType.APPLICATION_JSON_VALUE)
    @NullnullOperation(id = "listRelatedPlaces", security = Security.SESSION)
    public RelatedPlaceResultResponse related(OwnerContext owner, @PathVariable UUID placeId,
            @RequestParam(name = "at", required = false) String at,
            @RequestParam(name = "source", required = false) String source) {
        if (at != null || source != null) {
            throw new ApiException(ProblemCode.INVALID_REQUEST,
                    "This operation does not support the at or source filter yet.");
        }
        return RelatedPlaceResultResponse.from(relations.relatedPlaces(owner, placeId));
    }

    public record PlaceSearchBody(String query, String locale, String regionCode, String cursor, Integer limit) {
    }

    public record PlaceSearchPageResponse(List<PlaceSummaryResponse> items, CursorPageResponse page) {
    }

    public record CursorPageResponse(String nextCursor, boolean hasMore) {
    }

    public record PlaceSummaryResponse(UUID id, String name, String categoryCode, String regionCode,
            String categoryName, String regionName, String thumbnailUrl, String thumbnailAttribution,
            String address, SourceAttributionResponse sourceAttribution,
            PlaceTextProvenanceResponse textProvenance) {
        /**
         * Public because PlaceSummary is ONE contract schema that several resources embed - a feed
         * card's primaryPlace and a post's places are the same shape as a search result. A second
         * mapping in the embedding module would drift, and the field most likely to be dropped in
         * the copy is sourceAttribution, which is the one a KTO-derived place may not appear without.
         */
        public static PlaceSummaryResponse from(CatalogPlaceSummary source) {
            return new PlaceSummaryResponse(source.id(), source.name(), source.categoryCode(), source.regionCode(),
                    source.categoryName(), source.regionName(), source.thumbnailUrl(),
                    source.thumbnailAttribution(), source.address(),
                    SourceAttributionResponse.from(source.sourceAttribution()),
                    PlaceTextProvenanceResponse.from(source.textProvenance()));
        }
    }

    public record PlaceDetailResponse(UUID id, String name, String categoryCode, String regionCode,
            String categoryName, String regionName, String thumbnailUrl, MediaAssetResponse thumbnailAsset,
            String address, String description, GeoPointResponse location,
            List<ExternalReferenceResponse> externalRefs, SourceAttributionResponse sourceAttribution,
            PlaceTextProvenanceResponse textProvenance) {
        /**
         * Public for the same reason {@link PlaceSummaryResponse#from} is, and the second embedder
         * has now arrived: {@code LivePlaceDetail.place} is this same {@code PlaceDetail} schema, so
         * getPlace and getLivePlace either share one mapping or drift. The field a copy drops first
         * is sourceAttribution, which is the one a KTO-derived place may not appear without.
         */
        public static PlaceDetailResponse from(CatalogPlaceDetail source) {
            return new PlaceDetailResponse(source.id(), source.name(), source.categoryCode(), source.regionCode(),
                    source.categoryName(), source.regionName(), source.thumbnailUrl(),
                    MediaAssetResponse.from(source.thumbnailAsset()), source.address(), source.description(),
                    new GeoPointResponse(source.latitude(), source.longitude()),
                    source.externalReferences().stream().map(ExternalReferenceResponse::from).toList(),
                    SourceAttributionResponse.from(source.sourceAttribution()),
                    PlaceTextProvenanceResponse.from(source.textProvenance()));
        }
    }

    public record PlaceTextProvenanceResponse(TextFieldProvenanceResponse name,
            TextFieldProvenanceResponse address, TextFieldProvenanceResponse description) {
        static PlaceTextProvenanceResponse from(CatalogPlaceTextProvenance source) {
            if (source == null) {
                return new PlaceTextProvenanceResponse(null, null, null);
            }
            return new PlaceTextProvenanceResponse(TextFieldProvenanceResponse.from(source.name()),
                    TextFieldProvenanceResponse.from(source.address()),
                    TextFieldProvenanceResponse.from(source.description()));
        }
    }

    public record TextFieldProvenanceResponse(String locale, SourceAttributionResponse sourceAttribution) {
        static TextFieldProvenanceResponse from(CatalogTextFieldProvenance source) {
            return source == null ? null : new TextFieldProvenanceResponse(source.locale(),
                    SourceAttributionResponse.from(source.sourceAttribution()));
        }
    }

    /** The server-owned credit for the place's source; the client displays {@code attribution} verbatim. */
    public record RelatedPlaceResultResponse(UUID sourcePlaceId, String state, String reason,
            List<RelatedPlaceResponse> items) {
        /**
         * Public for the same reason as the two above: {@code LivePlaceDetail.related} embeds this
         * whole {@code RelatedPlaceResult}, so listRelatedPlaces and getLivePlace project relation
         * state, reason and provenance through one mapping. A second copy would be a second place
         * for NONE and CHECKING to appear, and BA-024-T7 pins that neither is ever emitted.
         */
        public static RelatedPlaceResultResponse from(CatalogRelatedPlaces result) {
            return new RelatedPlaceResultResponse(result.sourcePlaceId(), result.state().name(),
                    result.reason(), result.items().stream().map(RelatedPlaceResponse::from).toList());
        }
    }

    /**
     * {@code crowd} is always null and is sent rather than omitted. A CrowdMetric needs a full
     * provenance of its own and the comparison rules read it, so synthesising one from a relation is
     * exactly the fabricated evidence invariant 8 exists to prevent - and a present null says that
     * out loud where an absent field would leave a reader guessing whether it was merely forgotten.
     */
    public record RelatedPlaceResponse(PlaceSummaryResponse place, String relation, String relationReason,
            Object crowd, DataProvenanceResponse provenance) {
        static RelatedPlaceResponse from(CatalogRelatedPlace item) {
            return new RelatedPlaceResponse(PlaceSummaryResponse.from(item.place()), item.relation(),
                    item.relationReason(), null, DataProvenanceResponse.from(item.provenance()));
        }
    }

    /** Every field the contract requires, including the ones this source has no value for. */
    public record DataProvenanceResponse(String source, String sourceDisplayName, long sourceRegistryVersion,
            String sourceState, Instant observedAt, Instant targetAt, String freshness, Instant fetchedAt,
            Instant staleAt, Double confidence, String license, String officialUrl, String licenseUrl,
            String attribution, String metricDefinition, String normalizationVersion, List<String> qualityFlags,
            String forecastIssueId, String comparisonAxis, boolean comparisonEligible, String comparisonReasonCode,
            String comparisonGroupId, UUID collectorRunId, UUID snapshotSetId, Integer observedAtSkewSeconds,
            String scope, String scopeLabel, String mappingType, boolean fallbackUsed, UUID provenanceId) {
        static DataProvenanceResponse from(CatalogRelationProvenance p) {
            return new DataProvenanceResponse(p.source(), p.sourceDisplayName(), p.sourceRegistryVersion(),
                    p.sourceState(), p.observedAt(), p.targetAt(), p.freshness(), p.fetchedAt(), p.staleAt(),
                    p.confidence(), p.license(), p.officialUrl(), p.licenseUrl(), p.attribution(),
                    p.metricDefinition(), p.normalizationVersion(), p.qualityFlags(), p.forecastIssueId(),
                    p.comparisonAxis(), p.comparisonEligible(), p.comparisonReasonCode(), p.comparisonGroupId(),
                    p.collectorRunId(), p.snapshotSetId(), p.observedAtSkewSeconds(), p.scope(), p.scopeLabel(),
                    p.mappingType(), p.fallbackUsed(), p.provenanceId());
        }
    }

    public record SourceAttributionResponse(String source, String sourceDisplayName, long sourceRegistryVersion,
            String attribution, String officialUrl, String licenseUrl, String license) {
        static SourceAttributionResponse from(CatalogSourceAttribution source) {
            if (source == null) {
                return null;
            }
            return new SourceAttributionResponse(source.source(), source.sourceDisplayName(),
                    source.sourceRegistryVersion(), source.attribution(), source.officialUrl(), source.licenseUrl(),
                    source.license());
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
        /**
         * Public for the same reason {@link PlaceSummaryResponse#from} is: MediaAsset is ONE contract
         * schema that several resources embed - a place's thumbnailAsset and a post's coverAsset are
         * the same shape. A second mapping in the embedding module would drift, and the field most
         * likely to be dropped in the copy is the licence, which is the whole point of carrying the
         * asset rather than a bare URL.
         */
        public static MediaAssetResponse from(CatalogMediaAsset source) {
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
