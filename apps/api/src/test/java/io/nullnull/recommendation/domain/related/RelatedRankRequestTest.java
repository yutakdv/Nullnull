package io.nullnull.recommendation.domain.related;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Both transported lists carry an explicit cap ({@code maxItems: 2000} on the service side): a request
 * over either one is a hydration bug on this side, so it fails here rather than coming back from the
 * service as an opaque 422.
 */
@DisplayName("RelatedRankRequest transport bounds")
class RelatedRankRequestTest {

    static final Instant AT = Instant.parse("2026-09-06T00:00:00Z");
    static final UUID SOURCE = UUID.fromString("018f3f8e-9b67-7a21-8d31-31d315b93f01");
    static final String TAXONOMY = "taxonomy-test-1";

    @Test
    void listsOfExactlyTheContractSizeAreAccepted() {
        RelatedRankRequest request = request(candidates(RelatedRankRequest.MAX_CANDIDATES),
                categories(RelatedRankRequest.MAX_CATEGORIES));

        assertThat(request.candidates()).hasSize(RelatedRankRequest.MAX_CANDIDATES);
        assertThat(request.categories()).hasSize(RelatedRankRequest.MAX_CATEGORIES);
    }

    @Test
    void oneRelationOverTheContractSizeIsRefused() {
        List<RelationCandidateIn> tooMany = candidates(RelatedRankRequest.MAX_CANDIDATES + 1);

        assertThatThrownBy(() -> request(tooMany, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most " + RelatedRankRequest.MAX_CANDIDATES + " candidates per request");
    }

    @Test
    void oneCategoryOverTheContractSizeIsRefused() {
        List<PlaceCategoryIn> tooMany = categories(RelatedRankRequest.MAX_CATEGORIES + 1);

        assertThatThrownBy(() -> request(List.of(), tooMany))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most " + RelatedRankRequest.MAX_CATEGORIES + " categories per request");
    }

    private static RelatedRankRequest request(List<RelationCandidateIn> candidates, List<PlaceCategoryIn> categories) {
        return new RelatedRankRequest(AT, SOURCE, new PlaceCategoryIn(SOURCE, "PALACE", "HERITAGE", TAXONOMY),
                candidates, categories, RelatedRankRequest.LookupOutcome.COMPLETE);
    }

    private static List<RelationCandidateIn> candidates(int size) {
        List<RelationCandidateIn> candidates = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            candidates.add(new RelationCandidateIn(SOURCE, new UUID(3L, i), RelationCandidateIn.RelationTier.EXACT,
                    "KTO_RELATED_PLACES", "kto-direct", new BigDecimal("0.7"), AT, null,
                    RelationCandidateIn.MappingCertainty.CERTAIN));
        }
        return candidates;
    }

    /** Two rows for the same place are refused separately, so every row names a place of its own. */
    private static List<PlaceCategoryIn> categories(int size) {
        List<PlaceCategoryIn> categories = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            categories.add(new PlaceCategoryIn(new UUID(4L, i), "PALACE", "HERITAGE", TAXONOMY));
        }
        return categories;
    }
}
