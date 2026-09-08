package io.nullnull.recommendation.domain.related;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The registry code of the source and the evidence channel are the provenance a related place is
 * ranked with, and the service declares both as {@code minLength: 1, maxLength: 64}. A row whose
 * provenance is unnamed or overlong is a mapping bug here, not a user input.
 */
@DisplayName("RelationCandidateIn provenance bounds")
class RelationCandidateInTest {

    static final UUID SOURCE = UUID.fromString("018f3f8e-9b67-7a21-8d31-31d315b93d01");
    static final UUID TARGET = UUID.fromString("018f3f8e-9b67-7a21-8d31-31d315b93d02");

    @Test
    void provenanceOfExactlyTheContractLengthIsAccepted() {
        RelationCandidateIn candidate = relation("S".repeat(RelationCandidateIn.MAX_SOURCE_CODE),
                "c".repeat(RelationCandidateIn.MAX_CHANNEL));

        assertThat(candidate.sourceCode()).hasSize(RelationCandidateIn.MAX_SOURCE_CODE);
        assertThat(candidate.channel()).hasSize(RelationCandidateIn.MAX_CHANNEL);
    }

    @Test
    void provenanceOneCharacterOverTheContractLengthIsRefused() {
        assertThatThrownBy(() -> relation("S".repeat(RelationCandidateIn.MAX_SOURCE_CODE + 1), "kto-direct"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("sourceCode must be at most");
        assertThatThrownBy(() -> relation("KTO_RELATED_PLACES", "c".repeat(RelationCandidateIn.MAX_CHANNEL + 1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("channel must be at most");
    }

    @Test
    void provenanceThatNamesNoSourceOrChannelIsRefused() {
        // minLength 1 on the service side. An empty code passes requireNonNull and would be sent as
        // provenance nobody can attribute, which is the comparison §9 refuses to make.
        assertThatThrownBy(() -> relation("", "kto-direct")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sourceCode must not be blank");
        assertThatThrownBy(() -> relation("   ", "kto-direct")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sourceCode must not be blank");
        assertThatThrownBy(() -> relation("KTO_RELATED_PLACES", "")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("channel must not be blank");
        assertThatThrownBy(() -> relation("KTO_RELATED_PLACES", "   ")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("channel must not be blank");
    }

    private static RelationCandidateIn relation(String sourceCode, String channel) {
        return new RelationCandidateIn(SOURCE, TARGET, RelationCandidateIn.RelationTier.EXACT, sourceCode, channel,
                new BigDecimal("0.7"), Instant.parse("2026-09-05T00:00:00Z"), null,
                RelationCandidateIn.MappingCertainty.CERTAIN);
    }
}
