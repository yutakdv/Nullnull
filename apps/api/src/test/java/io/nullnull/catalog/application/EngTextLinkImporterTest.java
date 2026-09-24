package io.nullnull.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("BA-086 owner-reviewed English link import")
class EngTextLinkImporterTest {

    private static final Instant NOW = Instant.parse("2026-09-24T00:00:00Z");

    @Test
    @DisplayName("a plan locks its places in id order whatever order it lists them in, so two imports cannot deadlock")
    void placesAreLockedInIdOrder() {
        UUID low = UUID.fromString("00000000-0000-7000-8000-000000000001");
        UUID high = UUID.fromString("00000000-0000-7000-8000-000000000002");
        RecordingStore store = new RecordingStore();
        EngTextLinkImporter importer = new EngTextLinkImporter(store, Clock.fixed(NOW, ZoneOffset.UTC));

        importer.importPlan(new EngTextLinkImporter.Plan(List.of(
                new EngTextLinkImporter.Link(high, "1942577", "76", NOW.minusSeconds(60), "https://example.org/a"),
                new EngTextLinkImporter.Link(low, "264329", "76", NOW.minusSeconds(60), "https://example.org/b"))));

        assertThat(store.locked).containsExactly(low, high);
        assertThat(store.saved).containsExactly(low, high);
    }

    /** Records the order the importer takes place locks in; every place is an active Korean KTO place. */
    private static final class RecordingStore implements EngTextStore {
        private final List<UUID> locked = new ArrayList<>();
        private final List<UUID> saved = new ArrayList<>();

        @Override
        public List<Link> links() {
            return List.of();
        }

        @Override
        public Optional<Link> lockLink(UUID placeId) {
            return Optional.empty();
        }

        @Override
        public Optional<UUID> linkedPlace(String externalId, String contentTypeId) {
            return Optional.empty();
        }

        @Override
        public Optional<PlaceSide> lockActivePlace(UUID placeId) {
            locked.add(placeId);
            return Optional.of(new PlaceSide(new EngLinkRule.PlaceFacts(null, null, "HS", "11", "110"), true));
        }

        @Override
        public void saveLink(Link link, Instant now) {
            saved.add(link.placeId());
        }

        @Override
        public boolean writeText(UUID placeId, KtoEngRecord record, long sourceRegistryVersion, Instant observedAt,
                Instant now) {
            throw new AssertionError("an import writes no English text");
        }

        @Override
        public boolean removeText(UUID placeId) {
            return false;
        }
    }
}
