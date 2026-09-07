package io.nullnull.recommendation.domain.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A preview is ranked from 1 ({@code minimum: 1} on the service side). Rank 0 would let the gateway's
 * 1..n check read a returned list as starting one place early, so a proposal that names no rank at all
 * is refused where it is built rather than persisted as the top preview.
 */
@DisplayName("ItemProposalOut rank bound")
class ItemProposalOutTest {

    static final UUID ID = UUID.fromString("018f3f8e-9b67-7a21-8d31-31d315b93e01");
    static final LocalDate D12 = LocalDate.of(2026, 9, 12);
    static final Instant AT = Instant.parse("2026-09-12T01:00:00Z");

    @Test
    void theFirstRankIsAccepted() {
        assertThat(proposal(1).rank()).isEqualTo(1);
    }

    @Test
    void aRankBelowTheFirstOneIsRefused() {
        assertThatThrownBy(() -> proposal(0)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rank starts at 1");
        assertThatThrownBy(() -> proposal(-1)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rank starts at 1");
    }

    private static ItemProposalOut proposal(int rank) {
        return new ItemProposalOut(rank, D12, LocalTime.of(12, 0), AT, AT.plusSeconds(7200), new BigDecimal("0.8"),
                new BigDecimal("20"), new BigDecimal("15"), new BigDecimal("5"), ID, ID, Map.of());
    }
}
