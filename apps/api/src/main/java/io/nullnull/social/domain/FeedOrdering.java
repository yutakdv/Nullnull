package io.nullnull.social.domain;

import java.time.Instant;
import java.util.Comparator;
import java.util.Objects;
import java.util.UUID;

/**
 * §5.1 fixed feed order: {@code publishedAt DESC, postId ASC}. The selected trip, saved state and
 * feedback never change it, so a cursor page is stable for the life of its snapshot.
 *
 * <p>{@code postId} is compared as its canonical string, not with {@link UUID#compareTo(UUID)}: that
 * comparison treats the two halves as signed longs, so it would order {@code ffffffff-…} before
 * {@code 00000000-…} and diverge from the recommendation service, which compares the text.
 */
public final class FeedOrdering {

    /** Bound into every cursor; a change to this order invalidates outstanding cursors. */
    public static final int SORT_VERSION = 1;

    public record FeedEntry(UUID postId, Instant publishedAt) {

        public FeedEntry {
            Objects.requireNonNull(postId, "postId");
            Objects.requireNonNull(publishedAt, "publishedAt");
        }
    }

    private FeedOrdering() {
    }

    public static Comparator<FeedEntry> comparator() {
        return Comparator.comparing(FeedEntry::publishedAt).reversed()
                .thenComparing(entry -> entry.postId().toString());
    }
}
