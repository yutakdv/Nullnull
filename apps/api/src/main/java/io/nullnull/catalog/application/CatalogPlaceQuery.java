package io.nullnull.catalog.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Read projection boundary for active, canonical places only. */
public interface CatalogPlaceQuery {

    /**
     * One page of matches, resuming after {@code after}.
     *
     * @param after the last hit of the previous page, or null for the first page
     */
    List<CatalogPlaceSearchHit> search(CatalogPlaceSearchRequest request, PageKey after, int fetchLimit,
            Instant observedAt);

    /**
     * One hit, carrying the value the DATABASE ordered it by.
     *
     * <p>The search orders by {@code lower(...)} of the localized name under the server's collation,
     * which is not what {@link String#toLowerCase} produces under a Java locale. Recomputing the key
     * here would let the cursor disagree with its own ORDER BY and skip rows - the defect BA-027
     * exists to remove - so the sort value is read back rather than derived.
     */
    record CatalogPlaceSearchHit(CatalogPlaceSummary summary, String sortName) { }

    /** The row a search page ended on, in the terms {@code lower(name) ASC, id ASC} sorts by. */
    record PageKey(String sortName, UUID placeId) { }

    Optional<CatalogPlaceDetail> find(UUID requestedPlaceId, String locale, Instant observedAt);

    /**
     * Requested id to the canonical place {@link #find} would answer for it, for every requested id
     * that {@code find} would answer at all - one statement for the whole list.
     *
     * <p>The filter is {@code find}'s: one hop through {@code canonical_place_id}, then ACTIVE and
     * coordinate-complete. A requested id that {@code find} would not answer is absent, and so is
     * the reason: unknown, not active and without coordinates are one answer here, as they are one
     * 404 there. Nothing else of the place is read, because a caller that only needs to know which
     * place an id means should not pay for its localized name, references and media.
     */
    Map<UUID, UUID> readableCanonicalIds(List<UUID> requestedPlaceIds);

    /**
     * Active canonical summaries for the given ids, for callers that embed places in another
     * resource (a feed card, a post's linked places). One statement rather than N: a per-place
     * lookup is what makes an embedding caller reach for a shared cache.
     *
     * <p>Ids that resolve to nothing are simply absent from the result. A deprecated id resolves to
     * its canonical row, the same as {@link #find}, so an embedding never renders a stale duplicate.
     */
    List<CatalogPlaceSummary> summaries(List<UUID> placeIds, String locale, Instant observedAt);

    /**
     * The first {@code limit} active, coordinate-complete canonical places ordered by id ascending -
     * the pool a draft preview is composed from (REC-CON-04).
     *
     * <p>Ordered by id rather than by anything that means "better", because the pool is not a ranking:
     * the order only has to be stable, so the same catalog always yields the same pool and a caller
     * can ask for one more row to learn whether it truncated. The filter is the search path's, so a
     * place a user could not find by searching is not proposed to them either.
     */
    List<CatalogPlaceSummary> activePool(int limit, String locale, Instant observedAt);

    /**
     * One media asset by id, for a caller that already holds the reference - a post's cover
     * (A-024, V021), which social stores as {@code posts.cover_asset_id} and cannot read itself.
     *
     * <p>Unlike the place thumbnail, this applies no redistribution or expiry filter: a full
     * {@link CatalogMediaAsset} carries {@code redistributionAllowed} and {@code expiresAt} as
     * fields, so the caller is told the truth instead of being handed a silent null. The one thing
     * it does require is a servable URL - an asset with no {@code served_url} has no {@code url} to
     * project, and the contract's MediaAsset requires one - so that row resolves to empty and the
     * caller decides what an unservable reference means.
     */
    Optional<CatalogMediaAsset> mediaAsset(UUID assetId);

    /**
     * {@code thumbnailAttribution} is the ready-to-render credit for {@code thumbnailUrl}, or null
     * when the reviewed licence requires none. A summary already only carries a thumbnail whose
     * licence allows redistribution, but redistributable is not the same as creditless: without this
     * field a card could serve an attribution-required image with no way to name its source.
     */
    record CatalogPlaceSummary(UUID id, String name, String categoryCode, String regionCode,
            String categoryName, String regionName, String thumbnailUrl, String thumbnailAttribution,
            String address, CatalogSourceAttribution sourceAttribution) {
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
