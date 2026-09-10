package io.nullnull.catalog.application;

import io.nullnull.catalog.domain.CatalogExternalReference;
import io.nullnull.catalog.domain.CatalogPlace;
import io.nullnull.catalog.domain.CatalogPlaceLocalization;
import java.util.Optional;

/** Catalog persistence port. Provider DTOs never cross this module boundary. */
public interface CanonicalCatalogStore {
    Optional<CatalogPlace> findByExternalReference(String sourceCode, String externalId, String externalType);

    /**
     * Atomically creates an active canonical place if its source identity is still unclaimed, or
     * returns the winning existing canonical place if another transaction claimed it first.
     */
    CatalogPlace createIfAbsent(CatalogPlace place, CatalogPlaceLocalization localization,
            CatalogExternalReference externalReference);
}
