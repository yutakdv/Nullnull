package io.nullnull.catalog.domain;

/** A canonical POI is public-ready only while ACTIVE; old IDs resolve through DEPRECATED rows. */
public enum CatalogPlaceStatus {
    ACTIVE,
    DEPRECATED
}
