package io.nullnull.catalog.application;

import io.nullnull.catalog.domain.CatalogPlace;
import io.nullnull.catalog.domain.KtoPlaceSnapshot;

/**
 * Internal-only ingestion seam from an accepted normalized provider snapshot to canonical catalog
 * data. This does not register a public place route or trigger provider I/O.
 */
public interface CatalogIngest {
    CatalogPlace ingest(KtoPlaceSnapshot snapshot);
}
