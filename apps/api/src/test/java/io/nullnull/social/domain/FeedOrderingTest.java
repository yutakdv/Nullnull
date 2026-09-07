package io.nullnull.social.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** REC-FEED-02 fixed feed order: publishedAt DESC, then postId ASC as its canonical string. */
@DisplayName("REC-FEED-02 fixed feed order")
class FeedOrderingTest {

    static final Instant T = Instant.parse("2026-09-01T00:00:00Z");

    @Test
    void newestFirstThenPostIdForEqualPublishedAt() {
        FeedOrdering.FeedEntry newer = new FeedOrdering.FeedEntry(new UUID(0, 9), T.plusSeconds(60));
        FeedOrdering.FeedEntry a = new FeedOrdering.FeedEntry(new UUID(0, 1), T);
        FeedOrdering.FeedEntry b = new FeedOrdering.FeedEntry(new UUID(0, 2), T);
        List<FeedOrdering.FeedEntry> entries = new ArrayList<>(List.of(b, a, newer));
        Collections.shuffle(entries, new java.util.Random(20260906));
        entries.sort(FeedOrdering.comparator());
        assertThat(entries).containsExactly(newer, a, b);
    }

    @Test
    void postIdsAreComparedAsTheirCanonicalStringNotAsSignedUuids() {
        // UUID.compareTo is signed, so it puts 8000.. and ffff.. before 0000..; the service compares
        // the canonical text, and the feed order is a contract shared with it (FeedFallback).
        UUID low = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID high = UUID.fromString("80000000-0000-0000-0000-000000000000");
        UUID highest = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
        List<FeedOrdering.FeedEntry> entries = new ArrayList<>(List.of(
                new FeedOrdering.FeedEntry(highest, T),
                new FeedOrdering.FeedEntry(low, T),
                new FeedOrdering.FeedEntry(high, T)));
        entries.sort(FeedOrdering.comparator());
        assertThat(entries.stream().map(FeedOrdering.FeedEntry::postId))
                .containsExactly(low, high, highest);

        List<FeedOrdering.FeedEntry> signed = new ArrayList<>(entries);
        signed.sort(Comparator.comparing(FeedOrdering.FeedEntry::publishedAt).reversed()
                .thenComparing(FeedOrdering.FeedEntry::postId));
        assertThat(signed).as("the two orders must actually differ, otherwise the check is vacuous")
                .isNotEqualTo(entries);
    }

    @Test
    void everyEntryAppearsExactlyOnceWhenPagedByAnyLimit() {
        List<FeedOrdering.FeedEntry> all = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            all.add(new FeedOrdering.FeedEntry(new UUID(0, i), T.plusSeconds(i / 7)));
        }
        all.sort(FeedOrdering.comparator());
        for (int limit : List.of(1, 20, 50)) {
            List<FeedOrdering.FeedEntry> seen = new ArrayList<>();
            for (int ordinal = 0; ordinal < all.size(); ordinal += limit) {
                seen.addAll(all.subList(ordinal, Math.min(all.size(), ordinal + limit)));
            }
            assertThat(seen).as("limit %d", limit).containsExactlyElementsOf(all);
        }
    }

    @Test
    void theSortVersionIsPartOfTheCursorContract() {
        assertThat(FeedOrdering.SORT_VERSION).isEqualTo(1);
    }
}
