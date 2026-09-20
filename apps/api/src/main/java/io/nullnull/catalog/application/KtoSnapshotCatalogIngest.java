package io.nullnull.catalog.application;

import io.nullnull.catalog.domain.CatalogExternalReference;
import io.nullnull.catalog.domain.CatalogPlace;
import io.nullnull.catalog.domain.CatalogPlaceLocalization;
import io.nullnull.catalog.domain.CatalogPlaceStatus;
import io.nullnull.crowd.application.SourceRegistryQuery;
import io.nullnull.catalog.domain.KtoPlaceSnapshot;
import io.nullnull.shared.ids.UuidV7;
import java.time.Clock;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Maps only the normalized C2 KTO detail evidence into an internal canonical candidate.
 *
 * <p>Missing category/area data is not filled with an LLM, a guessed region or a synthetic code.
 * The mapper rejects it before any place row is created. Calling this service is explicit; C2's
 * read-through gateway intentionally remains independent until the final public-provenance gate.</p>
 */
@Service
public class KtoSnapshotCatalogIngest implements CatalogIngest {

    static final String KTO_SOURCE_CODE = KtoPlaceSnapshot.SOURCE_CODE;
    static final String KTO_CONTENT_TYPE_PREFIX = "KTO_CONTENT_TYPE:";
    private static final String KOREAN_LOCALE = "ko-KR";

    private final CanonicalCatalogStore catalog;
    private final SourceRegistryQuery registry;
    private final Clock clock;

    public KtoSnapshotCatalogIngest(CanonicalCatalogStore catalog, SourceRegistryQuery registry,
            Clock clock) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    @Transactional
    public CatalogPlace ingest(KtoPlaceSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        String category = requiredSnapshotValue("categoryCode", snapshot.categoryCode());
        String region = requiredSnapshotValue("areaCode", snapshot.areaCode());
        String externalType = KTO_CONTENT_TYPE_PREFIX + snapshot.contentTypeId();
        return catalog.findByExternalReference(KTO_SOURCE_CODE, snapshot.contentId(), externalType)
                .orElseGet(() -> create(snapshot, externalType, category, region));
    }

    private CatalogPlace create(KtoPlaceSnapshot snapshot, String externalType, String category, String region) {
        requireCurrentSourceRevision(snapshot);
        java.util.UUID placeId = UuidV7.create(clock);
        CatalogPlace place = new CatalogPlace(placeId, null, snapshot.title(), category, snapshot.latitude(),
                snapshot.longitude(), region, CatalogPlaceStatus.ACTIVE, snapshot.fetchedAt(), snapshot.fetchedAt());
        // KorService2 publishes Korean, so source_locale is the locale the row is stored under and
        // this text is not a translation (V047). It is stamped all the same: the revision pin is
        // what stops the text going out after the source's reviewed contract moves on, and that is
        // a licence question about republished prose, not a question about translation.
        CatalogPlaceLocalization.Provenance provenance = new CatalogPlaceLocalization.Provenance(
                KTO_SOURCE_CODE, snapshot.sourceRegistryVersion(), KOREAN_LOCALE, snapshot.fetchedAt());
        CatalogPlaceLocalization localization = new CatalogPlaceLocalization(UuidV7.create(clock), placeId,
                KOREAN_LOCALE, snapshot.title(), null, snapshot.address(), snapshot.fetchedAt(), provenance);
        CatalogExternalReference externalReference = new CatalogExternalReference(UuidV7.create(clock), placeId,
                KTO_SOURCE_CODE, snapshot.sourceRegistryVersion(), snapshot.contentId(), externalType,
                snapshot.fetchedAt());
        return catalog.createIfAbsent(place, localization, externalReference);
    }

    /**
     * BA-086-T12: a snapshot collected under a revision the source has since moved past is not fit
     * to be promoted, and the reason is that promoting it writes a provenance pin the read path
     * will refuse on sight - the row would be withdrawn at birth and nothing would say so.
     *
     * <p>The check lives here rather than in the snapshot cache on purpose. {@code findFresh} asks
     * "is there still-fresh cached evidence", and for a snapshot taken under the previous revision
     * the honest answer to THAT question is yes; KTO's freshness window is seven days, so a bump
     * leaves genuinely fresh rows behind. Teaching the store the registry would also give a
     * persistence port a dependency on another module's read model. What is actually being asked
     * at this seam is "may this be promoted to canonical", and that is a question about both.
     *
     * <p>It refuses rather than re-fetching. Re-fetching would put an outbound provider call on the
     * ingest path, which is a separate decision; refusing leaves the collector to bring a current
     * snapshot on its next cycle.
     *
     * <p><strong>It is called from create, not from ingest, and the first draft had it the other
     * way round.</strong> The justification above is entirely about the row this would WRITE; it
     * says nothing about the branch where the external reference is already claimed and ingest
     * returns the existing place having written nothing. Guarding there refuses a call that was
     * already a no-op - and {@link io.nullnull.catalog.infrastructure.kto.KtoDemoRefresh#detail}
     * makes exactly that call for every place on its list, relying on the documented idempotence,
     * with its loop turning any RuntimeException into a failed outcome. Since findFresh filters
     * only on staleness, every already-mapped place would have been reported as failed for a whole
     * freshness window after a revision bump. BA-086-T14 holds that path open.
     */
    private void requireCurrentSourceRevision(KtoPlaceSnapshot snapshot) {
        long current = registry.find(KTO_SOURCE_CODE)
                .orElseThrow(() -> new IllegalStateException(
                        "the catalog source " + KTO_SOURCE_CODE + " is not registered"))
                .currentRevision();
        if (snapshot.sourceRegistryVersion() != current) {
            throw new IllegalArgumentException("KTO snapshot sourceRegistryVersion "
                    + snapshot.sourceRegistryVersion() + " is not the current reviewed revision "
                    + current + "; it must be recollected before it can become canonical");
        }
    }

    private static String requiredSnapshotValue(String field, String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("KTO snapshot " + field + " is required for canonical mapping");
        }
        return value;
    }
}
