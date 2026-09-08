package io.nullnull.recommendation.domain.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The feed page this API hydrates carries an explicit cap, and the sort it asks for carries an
 * explicit lower bound ({@code maxItems: 2000}, {@code minimum: 1} on the service side). An oversized
 * page or a sort version below the first published one is a hydration bug here, so it fails on this
 * side rather than coming back from the service as an opaque 422.
 */
@DisplayName("FeedRankRequest transport bounds")
class FeedRankRequestTest {

    static final Instant AT = Instant.parse("2026-09-06T00:00:00Z");

    @Test
    void aPageOfExactlyTheContractSizeIsAccepted() {
        FeedRankRequest request = new FeedRankRequest(AT, "ko", 1, candidates(FeedRankRequest.MAX_CANDIDATES));

        assertThat(request.candidates()).hasSize(FeedRankRequest.MAX_CANDIDATES);
    }

    @Test
    void aPageOneCandidateOverTheContractSizeIsRefused() {
        List<FeedCandidateIn> tooMany = candidates(FeedRankRequest.MAX_CANDIDATES + 1);

        assertThatThrownBy(() -> new FeedRankRequest(AT, "ko", 1, tooMany))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most " + FeedRankRequest.MAX_CANDIDATES + " candidates per request");
    }

    @Test
    void theFirstPublishedSortVersionIsAcceptedAndAnythingBelowItIsRefused() {
        // minimum 1 on the service side: version 0 names no published order, and a run fingerprinted
        // with it would claim an order the service never shipped.
        assertThat(new FeedRankRequest(AT, "ko", 1, List.of()).sortVersion()).isEqualTo(1);
        assertThatThrownBy(() -> new FeedRankRequest(AT, "ko", 0, List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("sortVersion must be >= 1");
        assertThatThrownBy(() -> new FeedRankRequest(AT, "ko", -1, List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("sortVersion must be >= 1");
    }

    private static List<FeedCandidateIn> candidates(int size) {
        List<FeedCandidateIn> candidates = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            candidates.add(new FeedCandidateIn(new UUID(1L, i), AT, FeedCandidateIn.PostStatus.PUBLISHED,
                    new UUID(2L, i)));
        }
        return candidates;
    }
}
