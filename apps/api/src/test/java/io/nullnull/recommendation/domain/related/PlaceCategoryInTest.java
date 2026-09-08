package io.nullnull.recommendation.domain.related;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A canonical category and the taxonomy revision it belongs to are both capped at 64 characters by the
 * service, and the taxonomy version is additionally declared non-empty. The two category codes stay
 * nullable: a missing category is an unknown match and never a mismatch, so absence keeps travelling.
 */
@DisplayName("PlaceCategoryIn code bounds")
class PlaceCategoryInTest {

    static final UUID ID = UUID.fromString("018f3f8e-9b67-7a21-8d31-31d315b93c01");
    static final String TAXONOMY = "taxonomy-test-1";

    @Test
    void codesOfExactlyTheContractLengthAreAccepted() {
        String code = "C".repeat(PlaceCategoryIn.MAX_CODE);
        String version = "v".repeat(PlaceCategoryIn.MAX_TAXONOMY_VERSION);

        PlaceCategoryIn category = new PlaceCategoryIn(ID, code, code, version);

        assertThat(category.categoryCode()).hasSize(PlaceCategoryIn.MAX_CODE);
        assertThat(category.parentCategoryCode()).hasSize(PlaceCategoryIn.MAX_CODE);
        assertThat(category.taxonomyVersion()).hasSize(PlaceCategoryIn.MAX_TAXONOMY_VERSION);
    }

    @Test
    void aCodeOneCharacterOverTheContractLengthIsRefused() {
        String tooLong = "C".repeat(PlaceCategoryIn.MAX_CODE + 1);
        assertThatThrownBy(() -> new PlaceCategoryIn(ID, tooLong, null, TAXONOMY))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("categoryCode must be at most");
        assertThatThrownBy(() -> new PlaceCategoryIn(ID, null, tooLong, TAXONOMY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("parentCategoryCode must be at most");
        assertThatThrownBy(() -> new PlaceCategoryIn(ID, null, null,
                "v".repeat(PlaceCategoryIn.MAX_TAXONOMY_VERSION + 1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("taxonomyVersion must be at most");
    }

    @Test
    void aTaxonomyVersionThatNamesNoRevisionIsRefused() {
        // minLength 1 on the service side: without a revision the codes belong to no taxonomy, and a
        // category match would be computed across two revisions that never agreed on the codes.
        assertThatThrownBy(() -> new PlaceCategoryIn(ID, "PALACE", "HERITAGE", ""))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("taxonomyVersion must not be blank");
        assertThatThrownBy(() -> new PlaceCategoryIn(ID, "PALACE", "HERITAGE", "   "))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("taxonomyVersion must not be blank");
    }

    @Test
    void aMissingCategoryStillTravelsAsMissing() {
        // The contract declares both codes nullable and puts no lower bound on them; tightening them
        // would turn "unknown" into a rejected request and lose the distinction the ranker needs.
        PlaceCategoryIn category = new PlaceCategoryIn(ID, null, null, TAXONOMY);

        assertThat(category.categoryCode()).isNull();
        assertThat(category.parentCategoryCode()).isNull();
    }
}
