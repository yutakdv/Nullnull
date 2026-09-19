package io.nullnull.catalog.application;

import io.nullnull.catalog.application.CatalogPlaceQuery.CatalogPlaceDetail;
import io.nullnull.catalog.application.CatalogPlaceQuery.CatalogPlaceSummary;
import io.nullnull.identity.application.OwnerContext;
import io.nullnull.identity.application.OwnerPreferencesService;
import io.nullnull.shared.cursor.CursorClaims;
import io.nullnull.shared.cursor.CursorException;
import io.nullnull.shared.cursor.CursorSortKey;
import io.nullnull.shared.problem.ApiException;
import io.nullnull.shared.problem.ProblemCode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * C3's public projection is deliberately separated from C2 provider ingestion. This service only
 * reads canonical, reviewable records that are already in the local catalog and never calls KTO.
 */
@Service
public class CatalogPlaceProjectionService {

    private static final String SNAPSHOT_ID = "catalog-v1";
    private static final int SORT_VERSION = 1;

    private final CatalogPlaceQuery catalog;
    private final CatalogPublicationProperties publication;
    private final OwnerPreferencesService preferences;
    private final Clock clock;

    public CatalogPlaceProjectionService(CatalogPlaceQuery catalog, CatalogPublicationProperties publication,
            OwnerPreferencesService preferences, Clock clock) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.publication = Objects.requireNonNull(publication, "publication");
        this.preferences = Objects.requireNonNull(preferences, "preferences");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Transactional(readOnly = true)
    public CatalogPlaceSearchPage search(OwnerContext owner, CatalogPlaceSearchRequest request) {
        publication.requirePublicProjection();
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(request, "request");
        Instant now = clock.instant();
        String ownerBinding = publication.ownerBinding(owner.ownerId());
        CatalogPlaceQuery.PageKey after = after(request, now, ownerBinding);
        List<CatalogPlaceQuery.CatalogPlaceSearchHit> candidates =
                catalog.search(request, after, request.limit() + 1, now);
        boolean hasMore = candidates.size() > request.limit();
        List<CatalogPlaceQuery.CatalogPlaceSearchHit> hits =
                hasMore ? candidates.subList(0, request.limit()) : candidates;
        // The last hit of THIS page, named by the value the database sorted it by. A place that
        // becomes publishable ahead of the reader must not push what they have seen back at them.
        String nextCursor = hasMore
                ? nextCursor(request, now, ownerBinding, hits.get(hits.size() - 1))
                : null;
        return new CatalogPlaceSearchPage(
                hits.stream().map(CatalogPlaceQuery.CatalogPlaceSearchHit::summary).toList(),
                nextCursor, hasMore);
    }

    @Transactional(readOnly = true)
    public CatalogPlaceDetail detail(OwnerContext owner, UUID requestedPlaceId) {
        publication.requirePublicProjection();
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(requestedPlaceId, "requestedPlaceId");
        String locale = preferences.get(owner).locale();
        return catalog.find(requestedPlaceId, locale, clock.instant())
                .orElseThrow(() -> new ApiException(ProblemCode.NOT_FOUND, "The requested place is unavailable."));
    }

    /**
     * Which canonical place each requested id means, for the ids {@link #detail} would answer - many
     * at once, behind the same publication gate. An id {@code detail} would answer 404 for is absent
     * from the map; the caller decides what that means for its own response.
     *
     * <p>No owner, because {@code detail} reads the owner only for the locale of the text it
     * projects, and this projects no text.
     */
    @Transactional(readOnly = true)
    public Map<UUID, UUID> readableCanonicalIds(List<UUID> requestedPlaceIds) {
        publication.requirePublicProjection();
        Objects.requireNonNull(requestedPlaceIds, "requestedPlaceIds");
        return catalog.readableCanonicalIds(List.copyOf(requestedPlaceIds));
    }

    /**
     * Summaries for places another resource embeds, behind the SAME publication gate as searchPlaces
     * and getPlace.
     *
     * <p>The gate exists because the canonical catalog is KTO-derived and BA-021-T3's staging
     * call evidence does not exist yet. A caller that read {@link CatalogPlaceQuery} directly would
     * serve exactly that data through a different operation and the fail-closed decision would mean
     * nothing - so embedding callers come through here.
     */
    /**
     * The gate on its own, for a caller that has to fail closed before it writes.
     *
     * <p>createTrip with seed items is the case: the items cannot be shown while the catalog is
     * unpublished, and finding that out after persisting would leave the trip created and the stored
     * idempotent response replaying a 503. Exposed here rather than letting another module read
     * {@link CatalogPublicationProperties}, so the decision stays owned by this service.
     */
    public void requirePublicProjection() {
        publication.requirePublicProjection();
    }

    @Transactional(readOnly = true)
    public List<CatalogPlaceSummary> embeddedSummaries(OwnerContext owner, List<UUID> placeIds) {
        publication.requirePublicProjection();
        Objects.requireNonNull(owner, "owner");
        if (placeIds == null || placeIds.isEmpty()) {
            return List.of();
        }
        return catalog.summaries(List.copyOf(placeIds), preferences.get(owner).locale(), clock.instant());
    }

    private CatalogPlaceQuery.PageKey after(CatalogPlaceSearchRequest request, Instant now, String ownerBinding) {
        if (request.cursor() == null) {
            return null;
        }
        CursorClaims claims = publication.cursorCodec().decode(request.cursor(), now, ownerBinding,
                request.cursorContext());
        if (!SNAPSHOT_ID.equals(claims.snapshotId()) || claims.sortVersion() != SORT_VERSION) {
            throw new CursorException(ProblemCode.CURSOR_INVALID);
        }
        CursorSortKey key = CursorSortKey.decode(claims.sortKey());
        return new CatalogPlaceQuery.PageKey(key.value(), key.id());
    }

    private String nextCursor(CatalogPlaceSearchRequest request, Instant now, String ownerBinding,
            CatalogPlaceQuery.CatalogPlaceSearchHit last) {
        return publication.cursorCodec().encode(new CursorClaims(SNAPSHOT_ID,
                new CursorSortKey(last.sortName(), last.summary().id()).encode(), ownerBinding,
                request.cursorContext(), SORT_VERSION, now.plus(publication.cursorTtl()), publication.keyId()));
    }

    public record CatalogPlaceSearchPage(List<CatalogPlaceSummary> items, String nextCursor, boolean hasMore) {
    }
}
