package io.nullnull.catalog.infrastructure.kto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nullnull.catalog.application.EngTextStore;
import io.nullnull.catalog.application.KtoEngTextRefresh;
import io.nullnull.catalog.application.KtoGatewayException;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("BA-086 operator commands for English links and text")
class KtoEngCommandsTest {

    private static final UUID FIRST = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID SECOND = UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static final UUID THIRD = UUID.fromString("00000000-0000-4000-8000-000000000003");

    @Test
    @DisplayName("an owner plan keeps each reviewed field together")
    void planParsesReviewedLinks() {
        var plan = KtoEngLinkImportMain.parse("""
                {"links":[{"placeId":"00000000-0000-4000-8000-000000000001","contentId":"264329",
                  "contentTypeId":"76","reviewedAt":"2026-09-24T01:00:00Z",
                  "evidenceUrl":"https://english.visitkorea.or.kr/review"}]}
                """);

        assertThat(plan.links()).hasSize(1);
        assertThat(plan.links().get(0).placeId()).isEqualTo(FIRST);
        assertThat(plan.links().get(0).contentId()).isEqualTo("264329");
        assertThat(plan.links().get(0).reviewedAt()).isEqualTo(Instant.parse("2026-09-24T01:00:00Z"));
    }

    @Test
    @DisplayName("a plan without HTTPS evidence cannot be imported")
    void planWithoutHttpsEvidenceIsRefused() {
        assertThatThrownBy(() -> KtoEngLinkImportMain.parse("""
                {"links":[{"placeId":"00000000-0000-4000-8000-000000000001","contentId":"264329",
                  "contentTypeId":"76","reviewedAt":"2026-09-24T01:00:00Z","evidenceUrl":"http://x.test/r"}]}
                """)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the refresh prints ids, outcomes and failure codes only, and stops when the source is quarantined")
    void refreshReportsOutcomesAndStopsOnQuarantine() {
        List<EngTextStore.Link> links = List.of(link(FIRST), link(SECOND), link(THIRD));
        List<UUID> asked = new ArrayList<>();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        int failed = KtoEngTextRefreshMain.refreshAll(links, link -> {
            asked.add(link.placeId());
            if (link.placeId().equals(SECOND)) {
                throw new KtoGatewayException(KtoGatewayException.Code.SOURCE_QUARANTINED);
            }
            return KtoEngTextRefresh.Outcome.UPDATED;
        }, new PrintStream(bytes, true, StandardCharsets.UTF_8));

        String out = bytes.toString(StandardCharsets.UTF_8);
        assertThat(failed).isOne();
        assertThat(asked).containsExactly(FIRST, SECOND);
        assertThat(out).contains("KTO_ENG_TEXT_REFRESH placeId=" + FIRST + " outcome=UPDATED",
                "KTO_ENG_TEXT_REFRESH placeId=" + SECOND + " failure=SOURCE_QUARANTINED",
                "KTO_ENG_TEXT_REFRESH_DONE links=3 attempted=2 failed=1");
        assertThat(out).doesNotContain("264329");
    }

    private static EngTextStore.Link link(UUID place) {
        return new EngTextStore.Link(place, "264329", "76", Instant.parse("2026-09-24T01:00:00Z"));
    }
}
