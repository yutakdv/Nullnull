package io.nullnull.importer;

import static org.assertj.core.api.Assertions.assertThat;

import io.nullnull.importer.application.RemapImportCommand;
import io.nullnull.importer.domain.ImportDraft;
import io.nullnull.importer.domain.ImportDraftContent;
import io.nullnull.importer.domain.UnresolvedToken;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RemapImportCommandTest {
    @Test
    void anUnresolvedPlaceBecomesAnItemOnlyAfterExplicitPlaceAndDateSelection() {
        UUID place = UUID.randomUUID();
        var content = new ImportDraftContent(null, null, null, "Asia/Seoul", List.of());
        var token = new UnresolvedToken("t:1", UnresolvedToken.Kind.DATE, 1,
                "10월 3일", List.of(place));
        LocalDate chosen = LocalDate.parse("2026-10-03");

        var result = new RemapImportCommand(List.of(new RemapImportCommand.Update(
                "t:1", place, chosen, null, null, false)))
                .applyTo(content, List.of(token));

        assertThat(result.unresolved()).isEmpty();
        assertThat(result.content().items()).hasSize(1);
        assertThat(result.content().items().getFirst().placeId()).isEqualTo(place);
        assertThat(result.content().items().getFirst().date()).isEqualTo(chosen);
        assertThat(result.content().items().getFirst().originalLabel()).isNull();
        assertThat(result.content().startDate()).isEqualTo(chosen);
        assertThat(result.content().endDate()).isEqualTo(chosen);
        assertThat(ImportDraft.statusFor(result.content(), result.unresolved()))
                .isEqualTo(ImportDraft.Status.READY);
    }
}
