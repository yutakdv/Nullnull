package io.nullnull.catalog.application;

import io.nullnull.catalog.domain.CatalogExternalReference;
import io.nullnull.catalog.domain.CatalogPlace;
import io.nullnull.catalog.domain.CatalogPlaceLocalization;
import io.nullnull.catalog.domain.CatalogPlaceStatus;
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
    private final Clock clock;

    public KtoSnapshotCatalogIngest(CanonicalCatalogStore catalog, Clock clock) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
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
        java.util.UUID placeId = UuidV7.create(clock);
        CatalogPlace place = new CatalogPlace(placeId, null, snapshot.title(), category, snapshot.latitude(),
                snapshot.longitude(), region, CatalogPlaceStatus.ACTIVE, snapshot.fetchedAt(), snapshot.fetchedAt());
        CatalogPlaceLocalization localization = new CatalogPlaceLocalization(UuidV7.create(clock), placeId,
                KOREAN_LOCALE, snapshot.title(), null, snapshot.address(), snapshot.fetchedAt());
        CatalogExternalReference externalReference = new CatalogExternalReference(UuidV7.create(clock), placeId,
                KTO_SOURCE_CODE, snapshot.sourceRegistryVersion(), snapshot.contentId(), externalType,
                snapshot.fetchedAt());
        return catalog.createIfAbsent(place, localization, externalReference);
    }

    private static String requiredSnapshotValue(String field, String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("KTO snapshot " + field + " is required for canonical mapping");
        }
        return value;
    }
}
