package io.nullnull.trip.application;

import io.nullnull.catalog.application.CatalogPlaceQuery.CatalogPlaceSummary;
import io.nullnull.trip.domain.TripItem;
import java.util.Objects;

/**
 * One scheduled item together with the place the response has to show for it.
 *
 * <p>The two arrive from different places and only one of them is the trip's own data: the item is
 * this module's, and the place comes from the catalog's gated projection. Holding them in one record
 * keeps the controller from reaching for a second lookup - {@code TripItem.place} is required by the
 * contract, so an item without one is not a response this server may send.
 */
public record TripItemView(TripItem item, CatalogPlaceSummary place) {
    public TripItemView {
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(place, "place");
    }
}
