package io.nullnull.catalog.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for BA-086's English text: the owner-reviewed links (place_localization_sources) and the
 * {@code en} localization rows built from linked KTO EngService records. Fixed to one source and one
 * locale on purpose - there is no other approved English source, and a store that took any locale would
 * be a capability nothing has reviewed.
 */
public interface EngTextStore {

    /** The locale English provider text is stored under. EngService publishes English, not en-US. */
    String LOCALE = "en";

    /** An owner-reviewed decision that this EngService record is that canonical place. */
    record Link(UUID placeId, String externalId, String contentTypeId, Instant reviewedAt) {
        public KtoPlaceRequest request() {
            return new KtoPlaceRequest(externalId, contentTypeId);
        }
    }

    /** The canonical side of the link rule, and whether the place has a Korean KTO record at all. */
    record PlaceSide(EngLinkRule.PlaceFacts facts, boolean hasKoreanRecord) {
    }

    List<Link> links();

    /** Reads and locks the place's link, so an import and a refresh cannot interleave on it. */
    Optional<Link> lockLink(UUID placeId);

    /** The place already holding this English record, if any. */
    Optional<UUID> linkedPlace(String externalId, String contentTypeId);

    /** Reads and locks an ACTIVE canonical place; empty for an unknown, deprecated or merged one. */
    Optional<PlaceSide> lockActivePlace(UUID placeId);

    void saveLink(Link link, Instant now);

    /**
     * Writes the English name and address under the given source revision. Returns false, writing
     * nothing, when the place's {@code en} row was not written by this source - that row is not ours to
     * replace.
     */
    boolean writeText(UUID placeId, KtoEngRecord record, long sourceRegistryVersion, Instant observedAt, Instant now);

    /** Removes the English text this source wrote for the place. Returns whether a row was removed. */
    boolean removeText(UUID placeId);
}
